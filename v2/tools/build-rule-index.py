#!/usr/bin/env python3
"""Build a deterministic BaiZe rule-index JAR before signing it with jarsigner."""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import time
import urllib.parse
import zipfile

SHA256 = re.compile(r"^[0-9a-f]{64}$")
PACK_ID = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
ALLOWED_HOSTS = {
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "raw.githubusercontent.com",
}
FIXED_TIME = (2026, 1, 1, 0, 0, 0)
MAX_PACK_BYTES = 32 * 1024 * 1024


def write_entry(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def validate_release(item: dict[str, object], channel: str) -> dict[str, object]:
    pack_id = str(item.get("packId", "")).strip()
    version = str(item.get("version", "")).strip()
    version_code = int(item.get("versionCode", 0))
    min_app = int(item.get("minAppVersionCode", 0))
    size = int(item.get("bytes", 0))
    digest = str(item.get("sha256", "")).strip().lower()
    published = int(item.get("publishedAt", 0))
    url = str(item.get("url", "")).strip()
    parsed = urllib.parse.urlparse(url)
    if not PACK_ID.fullmatch(pack_id):
        raise ValueError(f"invalid packId: {pack_id!r}")
    if not version or len(version) > 80:
        raise ValueError(f"invalid version: {version!r}")
    if version_code <= 0 or min_app < 0:
        raise ValueError(f"invalid versionCode/minAppVersionCode for {version}")
    if not 0 < size <= MAX_PACK_BYTES:
        raise ValueError(f"invalid bytes for {version}")
    if not SHA256.fullmatch(digest):
        raise ValueError(f"invalid sha256 for {version}")
    if published <= 0:
        raise ValueError(f"invalid publishedAt for {version}")
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in ALLOWED_HOSTS:
        raise ValueError(f"non-official HTTPS URL for {version}")
    if parsed.username or parsed.password or parsed.fragment or parsed.port not in (None, 443):
        raise ValueError(f"unsafe URL components for {version}")
    return {
        "channel": channel,
        "packId": pack_id,
        "version": version,
        "versionCode": version_code,
        "minAppVersionCode": min_app,
        "url": url,
        "sha256": digest,
        "bytes": size,
        "publishedAt": published,
        "mandatory": bool(item.get("mandatory", False)),
        "releaseNotes": str(item.get("releaseNotes", ""))[:4000],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--releases-json", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--generated-at", type=int, default=0, help="Unix epoch milliseconds; defaults to now")
    parser.add_argument("--valid-days", type=int, default=14)
    args = parser.parse_args()

    source = json.loads(args.releases_json.read_text(encoding="utf-8"))
    raw_releases = source.get("releases", source) if isinstance(source, dict) else source
    if not isinstance(raw_releases, list) or len(raw_releases) > 50:
        raise SystemExit("releases JSON must contain a list of at most 50 releases")
    releases = [validate_release(item, args.channel) for item in raw_releases]
    codes = [int(item["versionCode"]) for item in releases]
    if len(codes) != len(set(codes)):
        raise SystemExit("duplicate versionCode in releases")
    releases.sort(key=lambda item: int(item["versionCode"]), reverse=True)

    generated_at = args.generated_at or int(time.time() * 1000)
    valid_days = max(1, min(args.valid_days, 45))
    manifest = {
        "schema": 1,
        "channel": args.channel,
        "generatedAt": generated_at,
        "expiresAt": generated_at + valid_days * 24 * 60 * 60 * 1000,
        "releases": releases,
    }
    payload = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w") as archive:
        write_entry(archive, "META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nCreated-By: BaiZe Rule Index Builder\r\n\r\n")
        write_entry(archive, "rule-index.json", payload)
    print(f"built unsigned {args.channel} index: {args.output}")
    print("sign with the same production APK certificate:")
    print(f"jarsigner -keystore <keystore> -signedjar <signed.jar> {args.output} <alias>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
