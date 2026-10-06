#!/usr/bin/env python3
"""Build resource-only APKs that collide with the host's package/resource IDs.

Usage: create-apk-artwork-fixtures.py INPUT_HOST_APK OUTPUT_DIR [--sdk-root SDK]
The fixtures are read-only parsing inputs. Do not install or sign them. Only
Android's official SDK aapt2 is invoked; Python's standard library makes the PNGs.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib


PACKAGE = "io.github.xgl34222220.baize"
RESOURCE_NAMES = ("mipmap/ic_baize", "string/app_name")
ICON_PATH = "res/mipmap-mdpi-v4/ic_baize.png"
FIXTURES = (
    ("first", "归档红色样例", "fixture-red", 1, (255, 0, 0, 255)),
    ("second", "归档蓝色样例", "fixture-blue", 2, (0, 0, 255, 255)),
)


def run(*arguments):
    result = subprocess.run(
        [str(value) for value in arguments], check=True, capture_output=True,
        text=True, encoding="utf-8", timeout=60,
    )
    return result.stdout


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version_key(path):
    return tuple(int(part) for part in re.findall(r"\d+", path.parent.name))


def sdk_tool(root, explicit, pattern, label):
    if explicit:
        path = Path(explicit).expanduser().resolve()
    else:
        require(root is not None, "Set ANDROID_HOME or --sdk-root, or specify both --aapt2 and --android-jar")
        candidates = sorted(root.glob(pattern), key=version_key)
        require(candidates, f"No {label} found under the selected Android SDK")
        path = candidates[-1]
    require(path.is_file(), f"Missing {label}: {path}")
    return path


def resource_ids(dump):
    require(re.search(rf"^Package name={re.escape(PACKAGE)} id=7f\s*$", dump, re.M),
            f"Expected the host package {PACKAGE} with resource package ID 0x7f")
    ids = {}
    for name in RESOURCE_NAMES:
        matches = re.findall(rf"^\s*resource (0x[0-9a-fA-F]{{8}}) {re.escape(name)}\s*$", dump, re.M)
        require(len(matches) == 1, f"Expected one host resource named {name}; found {len(matches)}")
        ids[name] = matches[0].lower()
    return ids


def solid_png(rgba, size=96):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    rows = (b"\0" + bytes(rgba) * size) * size
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def verify_apk(aapt2, apk, ids, label, version, code, png):
    dump = run(aapt2, "dump", "resources", apk)
    require(resource_ids(dump) == ids, "Fixture resource IDs differ from the host")
    require(f'"{label}"' in dump, "Compiled resource table lost the fixture label")
    manifest = run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    badging = run(aapt2, "dump", "badging", apk)
    require(f"package: name='{PACKAGE}' versionCode='{code}' versionName='{version}'" in badging,
            "Binary manifest has unexpected package/version metadata")
    require(f"application-label:'{label}'" in badging, "Binary manifest resolves the wrong application label")
    for attribute, name in (("icon", "mipmap/ic_baize"), ("label", "string/app_name")):
        require(re.search(rf":{attribute}\(0x[0-9a-fA-F]+\)=@{ids[name]}\s*$", manifest, re.M),
                f"Binary manifest does not reference the host's {attribute} resource ID")
    elements = re.findall(r"^\s*E: ([\w-]+)", manifest, re.M)
    require(elements == ["manifest", "uses-sdk", "application"], "Fixture unexpectedly contains manifest components")
    require(re.search(r":hasCode\(0x[0-9a-fA-F]+\)=false\s*$", manifest, re.M),
            "Fixture must declare android:hasCode=false")
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        require(sorted(entries) == sorted(["AndroidManifest.xml", "resources.arsc", ICON_PATH]),
                f"Unexpected APK entries (code, signatures and extra assets are forbidden): {entries}")
        require(archive.read(ICON_PATH) == png, "Compiled PNG is not the exact expected solid-color fixture")
        require(archive.read("AndroidManifest.xml")[:4] == b"\x03\x00\x08\x00", "Manifest is not compiled binary XML")
    return {
        "file": apk.name, "packageName": PACKAGE, "appName": label,
        "versionName": version, "versionCode": code, "resourceIds": ids,
        "entries": sorted(entries), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "bytes": apk.stat().st_size,
    }


def build_fixture(aapt2, android_jar, workspace, ids_file, output, ids, fixture):
    name, label, version, code, rgba = fixture
    root = workspace / name
    resources = root / "res"
    (resources / "values").mkdir(parents=True)
    (resources / "mipmap-mdpi").mkdir()
    (resources / "values" / "strings.xml").write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">{label}</string></resources>\n', encoding="utf-8")
    png = solid_png(rgba)
    (resources / "mipmap-mdpi" / "ic_baize.png").write_bytes(png)
    manifest = root / "AndroidManifest.xml"
    manifest.write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{PACKAGE}" android:versionCode="{code}" android:versionName="{version}">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <application android:hasCode="false" android:allowBackup="false"
        android:label="@string/app_name" android:icon="@mipmap/ic_baize" />
</manifest>
''', encoding="utf-8")
    compiled = root / "compiled.zip"
    run(aapt2, "compile", "--dir", resources, "--no-crunch", "-o", compiled)
    apk = root / f"{name}.apk"
    run(aapt2, "link", "-o", apk, "--manifest", manifest, "-I", android_jar,
        "--stable-ids", ids_file, "--no-compile-sdk-metadata", compiled)
    report = verify_apk(aapt2, apk, ids, label, version, code, png)
    report["iconArgb"] = f"0x{rgba[3]:02x}{rgba[0]:02x}{rgba[1]:02x}{rgba[2]:02x}"
    shutil.copyfile(apk, output / apk.name)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_host_apk", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--sdk-root", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--aapt2", help="Optional explicit official SDK aapt2 executable")
    parser.add_argument("--android-jar", help="Optional explicit SDK platforms/android-*/android.jar")
    args = parser.parse_args()
    host = args.input_host_apk.expanduser().resolve()
    output = args.output_dir.expanduser().resolve()
    require(host.is_file(), "Input host APK does not exist")
    require(host not in (output / "first.apk", output / "second.apk"), "Output must not overwrite the input host APK")
    sdk = Path(args.sdk_root).expanduser().resolve() if args.sdk_root else None
    aapt2 = sdk_tool(sdk, args.aapt2, "build-tools/*/aapt2", "aapt2")
    android_jar = sdk_tool(sdk, args.android_jar, "platforms/android-*/android.jar", "android.jar")
    ids = resource_ids(run(aapt2, "dump", "resources", host))
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="baize-artwork-fixtures-") as temporary:
        workspace = Path(temporary)
        stable_ids = workspace / "stable-ids.txt"
        stable_ids.write_text("".join(f"{PACKAGE}:{name} = {ids[name]}\n" for name in RESOURCE_NAMES), encoding="utf-8")
        reports = [build_fixture(aapt2, android_jar, workspace, stable_ids, output, ids, fixture) for fixture in FIXTURES]
    result = {"verified": True, "resourceOnly": True, "installed": False, "fixtures": reports}
    (output / "fixture-metadata.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        detail = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        print(f"APK artwork fixture build failed: {detail}", file=sys.stderr)
        sys.exit(1)
