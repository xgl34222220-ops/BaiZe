#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[2]
repo = (root / "v2/app/src/main/java/io/github/xgl34222220/baize/root/WhitelistRepository.kt").read_text()
aidl = (root / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl").read_text()

for field in ("id", "type", "value", "scopes", "matchMode", "userId", "reason", "source", "createdAt", "expiresAt", "builtIn", "enabled"):
    assert f'.put("{field}"' in repo, f"typed exclusion field missing: {field}"
for scope in ("cache", "deep", "corpses", "apk", "organizer", "storage"):
    assert f'"{scope}"' in repo, f"exclusion scope missing: {scope}"
assert "unsupported_match_mode" in repo
assert "CRITICAL_ROOTS" in repo
assert "whitelist.$scope.conf" in repo
assert "@Synchronized fun addExclusion" in repo
for method in ("getExclusions", "addExclusion", "removeExclusion"):
    assert method in aidl, f"typed exclusion API missing: {method}"
print("typed scoped exclusion contract: ok")
