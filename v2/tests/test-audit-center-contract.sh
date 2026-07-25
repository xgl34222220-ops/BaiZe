#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
AIDL="$ROOT/v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
SERVICE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
AUDIT="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/AuditRepository.kt"
HISTORY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/HistoryRepository.kt"
ACTIVITY="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/AuditActivity.kt"
ROUTE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/HistoryRoute.kt"
MANIFEST="$ROOT/v2/app/src/main/AndroidManifest.xml"
SCHEDULER="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"

for file in "$AIDL" "$SERVICE" "$AUDIT" "$HISTORY" "$ACTIVITY" "$ROUTE" "$MANIFEST" "$SCHEDULER"; do
  test -f "$file" || { echo "missing audit contract file: $file" >&2; exit 1; }
done

grep -Fq 'String getAuditTimelinePage(int offset, int limit);' "$AIDL"
grep -Fq 'String clearAuditTimeline();' "$AIDL"
grep -Fq 'private val auditRepository = AuditRepository()' "$SERVICE"
grep -Fq 'auditRepository.timelinePageJson(offset, limit)' "$SERVICE"
grep -Fq 'auditRepository.recordNativeTask(raw, result)' "$SERVICE"

# Every important mutation family is written by the Root service rather than trusted to App UI.
for operation in profile-scan profile-clean profile-quarantine quarantine-restore quarantine-purge quarantine-expire instant-cache file-organizer-scan file-organizer-apply file-organizer-undo; do
  grep -Fq "audited(\"$operation\"" "$SERVICE"
done

# Audit storage is bounded, backward compatible and path-minimized.
grep -Fq 'private const val MAX_EVENTS = 500' "$AUDIT"
grep -Fq 'readLegacyEvents(clearEpoch)' "$AUDIT"
grep -Fq 'history.tsv' "$AUDIT"
grep -Fq '.put("pathTail"' "$AUDIT"
! grep -Fq '.put("path", path)' "$AUDIT"
grep -Fq 'segments.takeLast(4)' "$AUDIT"
grep -Fq 'cleared_at=' "$AUDIT"
grep -Fq '累计统计和清理历史未修改' "$AUDIT"

# Workbench-native results must no longer be rejected by the legacy history writer.
grep -Fq '"workbench-clean"' "$HISTORY"
grep -Fq 'require(mode in NATIVE_MODES)' "$HISTORY"

# The audit backend stays available for compatibility, but no persistent floating entry covers the history page.
! grep -Fq 'AuditActivity::class.java' "$ROUTE"
! grep -Fq 'Text("审计中心")' "$ROUTE"
grep -Fq '<activity android:name=".AuditActivity"' "$MANIFEST"
grep -Fq 'getAuditTimelinePage(0, 100)' "$ACTIVITY"
grep -Fq '只隐藏当前时间点之前的审计事件' "$ACTIVITY"

# This step must not tighten any scheduler interval ranges.
for pattern in \
  'cacheMinutes.coerceIn(5, 43_200)' \
  'emptyMinutes.coerceIn(5, 43_200)' \
  'rulesMinutes.coerceIn(5, 43_200)' \
  'fragmentMinutes.coerceIn(5, 43_200)' \
  'deepMinutes.coerceIn(5, 43_200)' \
  'organizeMinutes.coerceIn(15, 43_200)'; do
  grep -Fq "$pattern" "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
done

echo "audit center contract passed"
