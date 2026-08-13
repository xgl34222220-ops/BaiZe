#!/usr/bin/env python3
"""Fail-closed gate for formal releases: require two distinct real ARM64 devices."""
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
tag = sys.argv[2]
data = json.loads(path.read_text(encoding="utf-8"))
if data.get("schema") != 1 or data.get("tag") != tag:
    raise SystemExit("release evidence schema/tag mismatch")
devices = data.get("devices")
if not isinstance(devices, list) or len(devices) < 2:
    raise SystemExit("at least two real-device reports are required")
fingerprints = set()
apis = set()
required = {"install", "scan_only", "snapshot_clean", "cancel_resume", "reboot_restore", "quarantine_restore", "multi_user_guard"}
for device in devices:
    if device.get("kind") != "physical" or device.get("abi") != "arm64-v8a":
        raise SystemExit("evidence must come from physical ARM64 devices")
    api = device.get("api")
    if not isinstance(api, int) or not 26 <= api <= 36:
        raise SystemExit("device API is outside the supported range")
    fingerprint = device.get("buildFingerprint", "")
    if len(fingerprint) < 12:
        raise SystemExit("missing Android build fingerprint")
    if not required.issubset({key for key, value in device.get("checks", {}).items() if value is True}):
        raise SystemExit("device safety checklist is incomplete")
    fingerprints.add(fingerprint)
    apis.add(api)
if len(fingerprints) != len(devices):
    raise SystemExit("device evidence contains duplicate devices")
if 26 not in apis:
    raise SystemExit("Android 8 / API 26 evidence is mandatory")
print(f"validated {len(devices)} physical devices for {tag}")
