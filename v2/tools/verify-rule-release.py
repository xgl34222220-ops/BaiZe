#!/usr/bin/env python3
"""Cross-check a signed BaiZe rule pack and its signed channel index payloads.

Cryptographic JAR verification is intentionally performed by jarsigner/keytool in the workflow.
This tool verifies the payload contract after that cryptographic gate: hashes, sizes, versions,
URLs, rule metrics, ordering, expiry and exact pack/index linkage.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import time
import urllib.parse
import zipfile

SHA256 = re.compile(r"^[0-9a-f]{64}$")
VERSION = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
MANAGED = ("app.rules", "external.rules", "hidden.rules", "deep.rules")
ALLOWED_HOSTS = {
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "raw.githubusercontent.com",
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def effective_rules(data: bytes, name: str) -> int:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(f"{name} is not UTF-8") from error
    from importlib.util import module_from_spec, spec_from_file_location
    spec = spec_from_file_location("rule_pack_builder", pathlib.Path(__file__).with_name("build-rule-pack.py"))
    if spec is None or spec.loader is None:
        raise ValueError("unable to load rule syntax validator")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return int(module.effective_rules(text, name))


def payload_entries(archive: zipfile.ZipFile) -> set[str]:
    result: set[str] = set()
    for info in archive.infolist():
        name = info.filename
        if info.is_dir() or name.upper().startswith("META-INF/"):
            continue
        if name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts:
            raise ValueError(f"unsafe archive entry: {name}")
        if name in result:
            raise ValueError(f"duplicate archive entry: {name}")
        result.add(name)
    return result


def read_pack(path: pathlib.Path, expected_version: str, expected_code: int) -> tuple[dict[str, object], str, int]:
    raw = path.read_bytes()
    if not raw:
        raise ValueError("rule pack is empty")
    with zipfile.ZipFile(path) as archive:
        entries = payload_entries(archive)
        if "rule-pack.json" not in entries:
            raise ValueError("rule pack manifest is missing")
        manifest = json.loads(archive.read("rule-pack.json"))
        files = manifest.get("files")
        if manifest.get("schema") != 1 or manifest.get("mode") != "full" or not isinstance(files, list):
            raise ValueError("unsupported rule pack manifest")
        if manifest.get("version") != expected_version or int(manifest.get("versionCode", 0)) != expected_code:
            raise ValueError("rule pack version does not match release inputs")
        if not VERSION.fullmatch(str(manifest.get("packId", ""))):
            raise ValueError("invalid rule pack id")

        expected_entries = {"rule-pack.json"}
        seen: set[str] = set()
        for item in files:
            if not isinstance(item, dict):
                raise ValueError("invalid rule file metadata")
            rule_path = str(item.get("path", ""))
            if not rule_path.startswith("rules/") or rule_path.count("/") != 1:
                raise ValueError(f"invalid rule path: {rule_path}")
            name = rule_path.removeprefix("rules/")
            if name not in MANAGED or name in seen:
                raise ValueError(f"unmanaged or duplicate rule file: {name}")
            seen.add(name)
            expected_entries.add(rule_path)
            data = archive.read(rule_path)
            if digest(data) != str(item.get("sha256", "")).lower():
                raise ValueError(f"rule hash mismatch: {name}")
            if len(data) != int(item.get("bytes", -1)):
                raise ValueError(f"rule byte count mismatch: {name}")
            if effective_rules(data, name) != int(item.get("rules", -1)):
                raise ValueError(f"rule count mismatch: {name}")
        if "deep.rules" not in seen:
            raise ValueError("official rule pack must contain deep.rules")
        if entries != expected_entries:
            raise ValueError(f"unexpected rule pack payload entries: {sorted(entries - expected_entries)}")
    return manifest, digest(raw), len(raw)


def validate_url(raw: str) -> None:
    parsed = urllib.parse.urlparse(raw)
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in ALLOWED_HOSTS:
        raise ValueError("rule pack URL is not an approved GitHub HTTPS URL")
    if parsed.username or parsed.password or parsed.fragment or parsed.port not in (None, 443):
        raise ValueError("rule pack URL contains unsafe components")


def read_index(
    path: pathlib.Path,
    channel: str,
    version: str,
    version_code: int,
    pack_url: str,
    pack_sha: str,
    pack_bytes: int,
    pack_id: str,
) -> tuple[dict[str, object], dict[str, object], str]:
    raw = path.read_bytes()
    if not raw:
        raise ValueError("rule index is empty")
    with zipfile.ZipFile(path) as archive:
        entries = payload_entries(archive)
        if entries != {"rule-index.json"}:
            raise ValueError(f"unexpected rule index payload entries: {sorted(entries)}")
        manifest = json.loads(archive.read("rule-index.json"))
    if manifest.get("schema") != 1 or manifest.get("channel") != channel:
        raise ValueError("rule index channel or schema mismatch")
    generated = int(manifest.get("generatedAt", 0))
    expires = int(manifest.get("expiresAt", 0))
    now = int(time.time() * 1000)
    if generated <= 0 or expires <= generated or expires - generated > 45 * 24 * 60 * 60 * 1000:
        raise ValueError("rule index validity window is invalid")
    if generated > now + 10 * 60 * 1000:
        raise ValueError("rule index generation time is in the future")

    releases = manifest.get("releases")
    if not isinstance(releases, list) or not releases or len(releases) > 50:
        raise ValueError("rule index release list is invalid")
    codes = [int(item.get("versionCode", 0)) for item in releases if isinstance(item, dict)]
    if len(codes) != len(releases) or len(codes) != len(set(codes)) or codes != sorted(codes, reverse=True):
        raise ValueError("rule index versions must be unique and descending")
    target = next((item for item in releases if int(item.get("versionCode", 0)) == version_code), None)
    if not isinstance(target, dict):
        raise ValueError("new rule release is missing from the signed index")
    validate_url(str(target.get("url", "")))
    expected = {
        "channel": channel,
        "packId": pack_id,
        "version": version,
        "versionCode": version_code,
        "url": pack_url,
        "sha256": pack_sha,
        "bytes": pack_bytes,
    }
    for key, value in expected.items():
        if target.get(key) != value:
            raise ValueError(f"index release field mismatch: {key}")
    if not SHA256.fullmatch(str(target.get("sha256", ""))):
        raise ValueError("index release SHA-256 is invalid")
    return manifest, target, digest(raw)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", required=True, type=pathlib.Path)
    parser.add_argument("--index", required=True, type=pathlib.Path)
    parser.add_argument("--channel", required=True, choices=("stable", "beta"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--pack-url", required=True)
    parser.add_argument("--expected-signer-sha256", default="")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    if not VERSION.fullmatch(args.version) or args.version_code <= 0:
        raise SystemExit("invalid release version or version code")
    validate_url(args.pack_url)
    signer = args.expected_signer_sha256.lower().replace(":", "").strip()
    if signer and not SHA256.fullmatch(signer):
        raise SystemExit("invalid expected signer fingerprint")

    pack_manifest, pack_sha, pack_bytes = read_pack(args.pack, args.version, args.version_code)
    index_manifest, release, index_sha = read_index(
        args.index,
        args.channel,
        args.version,
        args.version_code,
        args.pack_url,
        pack_sha,
        pack_bytes,
        str(pack_manifest["packId"]),
    )
    report = {
        "success": True,
        "channel": args.channel,
        "version": args.version,
        "versionCode": args.version_code,
        "packId": pack_manifest["packId"],
        "packSha256": pack_sha,
        "packBytes": pack_bytes,
        "packUrl": args.pack_url,
        "indexSha256": index_sha,
        "indexGeneratedAt": index_manifest["generatedAt"],
        "indexExpiresAt": index_manifest["expiresAt"],
        "mandatory": bool(release.get("mandatory", False)),
        "signerSha256": signer,
    }
    text = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
