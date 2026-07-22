#!/usr/bin/env python3
"""Build a deterministic BaiZe rule-pack JAR before jarsigner signing."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import zipfile

MANAGED = ("app.rules", "external.rules", "hidden.rules", "deep.rules")
FORBIDDEN = (
    "/data/adb", "/metadata", "/proc", "/sys", "/dev", "/system",
    "/vendor", "/product", "/odm", "/apex",
)
PACK_ID = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
FIXED_TIME = (2026, 1, 1, 0, 0, 0)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def effective_rules(text: str, name: str) -> int:
    count = 0
    for number, raw in enumerate(text.splitlines(), 1):
        if len(raw) > 4096:
            raise ValueError(f"{name}:{number}: line exceeds 4096 characters")
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        path = line.split("|", 1)[0].split("#", 1)[0].strip()
        if not path.startswith("/") or "\x00" in path or any(part == ".." for part in path.split("/")):
            raise ValueError(f"{name}:{number}: unsafe rule syntax")
        if path in ("/", "/data") or any(path == root or path.startswith(root + "/") for root in FORBIDDEN):
            raise ValueError(f"{name}:{number}: forbidden rule root {path}")
        segments = [part for part in path.split("/") if part]
        if not segments or any(part in {"*", "**", "?"} for part in segments[:2]):
            raise ValueError(f"{name}:{number}: top-level wildcard is forbidden")
        count += 1
        if count > 20_000:
            raise ValueError(f"{name}: more than 20,000 effective rules")
    return count


def write_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rules-dir", required=True, type=pathlib.Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--pack-id", default="baize-official")
    parser.add_argument("--created-by", default="BaiZe GitHub Actions")
    parser.add_argument("--release-notes", default="")
    parser.add_argument("--min-app-version-code", type=int, default=24001)
    args = parser.parse_args()

    if not PACK_ID.fullmatch(args.pack_id):
        raise SystemExit("invalid --pack-id")
    if not args.version.strip() or len(args.version) > 80:
        raise SystemExit("invalid --version")
    if not args.rules_dir.is_dir():
        raise SystemExit("--rules-dir is not a directory")

    payloads: dict[str, bytes] = {}
    files: list[dict[str, object]] = []
    for name in MANAGED:
        source = args.rules_dir / name
        if not source.is_file():
            continue
        data = source.read_bytes()
        if len(data) > 16 * 1024 * 1024:
            raise SystemExit(f"{name} exceeds 16MB")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError as error:
            raise SystemExit(f"{name} is not UTF-8: {error}") from error
        count = effective_rules(text, name)
        payloads[name] = data
        files.append(
            {
                "path": f"rules/{name}",
                "sha256": sha256(data),
                "rules": count,
                "bytes": len(data),
            }
        )

    if "deep.rules" not in payloads:
        raise SystemExit("deep.rules is required for an official full pack")

    manifest = {
        "schema": 1,
        "mode": "full",
        "packId": args.pack_id,
        "version": args.version.strip(),
        "createdBy": args.created_by.strip(),
        "releaseNotes": args.release_notes.strip(),
        "minAppVersionCode": max(0, args.min_app_version_code),
        "files": files,
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w") as archive:
        write_entry(archive, "META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nCreated-By: BaiZe Rule Pack Builder\r\n\r\n")
        write_entry(archive, "rule-pack.json", manifest_bytes)
        for name, data in payloads.items():
            write_entry(archive, f"rules/{name}", data)

    print(f"built unsigned rule pack: {args.output}")
    print(f"manifest sha256: {sha256(manifest_bytes)}")
    print("sign with the same keystore alias used for the production BaiZe APK:")
    print(f"jarsigner -keystore <keystore> -signedjar <signed.jar> {args.output} <alias>")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print(error, file=sys.stderr)
        raise SystemExit(2) from error
