#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
AIDL="$ROOT/v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
ENGINE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt"
REPO="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/QuarantineRepository.kt"
SERVICE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
WORKBENCH="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ScanWorkbenchActivity.kt"
CENTER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/CleanCenterActivity.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"

for file in "$AIDL" "$ENGINE" "$REPO" "$SERVICE" "$WORKBENCH" "$CENTER" "$MANIFEST"; do
  test -f "$file" || { echo "missing quarantine contract file: $file" >&2; exit 1; }
done

grep -Fq 'String quarantineProfileSelected(String snapshotId, String selectionJson, String optionsJson);' "$AIDL"
grep -Fq 'String restoreQuarantineItem(String id);' "$AIDL"
grep -Fq 'String purgeQuarantineItem(String id);' "$AIDL"

# UI authorizes only a candidate ID from the current profile snapshot. It never sends a new path.
grep -Fq 'item.id.removePrefix("profile:")' "$WORKBENCH"
grep -Fq 'service.quarantineProfileSelected(profileSnapshotId' "$WORKBENCH"
! grep -Fq 'quarantineProfileSelected(item.path' "$WORKBENCH"

# The engine must resolve the candidate from an unexpired server-side snapshot and only accept high risk.
grep -Fq 'val snapshot = validSnapshot(snapshotId)' "$ENGINE"
grep -Fq 'candidate.risk == "high"' "$ENGINE"
grep -Fq 'val reason = validate(candidate, options, mounts)' "$ENGINE"
grep -Fq 'if (candidate.risk == "critical") return "关键风险只允许审计"' "$ENGINE"
grep -Fq 'private val quarantineRepository: QuarantineRepository' "$ENGINE"

# Transactional move: source is renamed before metadata publish, and metadata failure attempts rollback.
grep -Fq 'source.renameTo(destination)' "$REPO"
grep -Fq 'writeEntry(entry)' "$REPO"
grep -Fq 'destination.renameTo(source)' "$REPO"
grep -Fq '原文件未删除' "$REPO"

# Restore must never overwrite an existing original path and must use a marked conflict copy.
grep -Fq '.baize-restored-' "$REPO"
grep -Fq 'if (!original.exists())' "$REPO"
grep -Fq 'if (destination.exists()) return error("restore_conflict"' "$REPO"

# Controlled namespaces, retention and scan exclusion are mandatory.
grep -Fq 'private const val RETENTION_DAYS = 7' "$REPO"
grep -Fq '.baize-quarantine' "$REPO"
grep -Fq '".baize-quarantine"' "$ENGINE"
grep -Fq 'safeOriginalPath' "$REPO"
grep -Fq 'isStoredPayload' "$REPO"

# Service, UI entry and manifest registration stay connected.
grep -Fq 'override fun quarantineProfileSelected' "$SERVICE"
grep -Fq 'QuarantineActivity::class.java' "$CENTER"
grep -Fq '<activity android:name=".QuarantineActivity"' "$MANIFEST"

echo "quarantine contract ok"
