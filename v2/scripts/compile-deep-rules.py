#!/usr/bin/env python3
"""Compile the immutable legacy rule source into the normalized runtime pack."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

EXPECTED_SOURCE_SHA = "73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c"
HARD_ROOTS = ("/data/adb", "/metadata", "/proc", "/sys", "/dev")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("meta", type=Path)
    parser.add_argument("--index", type=Path, help="structured rule provenance index")
    args = parser.parse_args()

    source_bytes = args.source.read_bytes()
    source_sha = hashlib.sha256(source_bytes).hexdigest()
    if source_sha != EXPECTED_SOURCE_SHA:
        raise SystemExit(f"legacy deep rule source SHA mismatch: {source_sha}")

    seen: set[str] = set()
    active: list[str] = []
    rejected = duplicates = raw = 0
    for raw_line in source_bytes.decode("utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        raw += 1
        valid = line.startswith("/") and not any(part in {".", ".."} for part in line.split("/"))
        valid = valid and not line.startswith(HARD_ROOTS)
        if not valid:
            rejected += 1
            continue
        if line in seen:
            duplicates += 1
            continue
        seen.add(line)
        active.append(line)

    payload = ("\n".join(active) + "\n").encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    runtime_sha = hashlib.sha256(payload).hexdigest()
    args.meta.write_text(
        "schema=baize-compiled-rules-v1\n"
        f"source_sha256={source_sha}\n"
        f"rules_sha256={runtime_sha}\n"
        f"raw_count={raw}\nactive_unique_count={len(active)}\n"
        f"rejected_count={rejected}\nduplicate_count={duplicates}\n",
        encoding="utf-8",
    )
    if args.index:
        args.index.parent.mkdir(parents=True, exist_ok=True)
        with args.index.open("w", encoding="utf-8") as handle:
            for path in active:
                components = {part.lower() for part in path.split("/") if part}
                if components & {"download", "downloads", "documents", "dcim", "pictures", "movies", "music", "obb", "databases", "shared_prefs", "backup", "draft"}:
                    risk, category = "critical", "user-data"
                elif components & {"files", "app_webview", "webview", "user_data", "profile"}:
                    risk, category = "high", "app-data"
                elif components & {"tombstone", "minidump", "heapdump", "crash", "trace", "dump", "debug", "logs", "log"}:
                    risk, category = "medium", "diagnostic"
                elif components & {"cache", "code_cache", "gpucache", "tmp", "temp", ".cache", ".thumbnails"}:
                    risk, category = "low", "cache"
                else:
                    risk, category = "high", "unclassified"
                rule_id = "legacy-" + hashlib.sha256(path.encode()).hexdigest()[:20]
                handle.write(json.dumps({
                    "schema": 1,
                    "ruleId": rule_id,
                    "path": path,
                    "category": category,
                    "risk": risk,
                    "minAgeDays": 0,
                    "scopes": ["deep"],
                    "androidMin": 26,
                    "androidMax": None,
                    "roms": ["generic"],
                    "source": "BaiZe legacy deep.rules 2026.07.1",
                    "license": "repository-license",
                    "reviewState": "legacy-imported",
                    "enabled": True,
                }, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"compiled {len(active)} unique rules ({rejected} rejected, {duplicates} duplicates), sha256={runtime_sha}")


if __name__ == "__main__":
    main()
