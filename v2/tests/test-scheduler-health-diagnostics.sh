#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WORK=${TMPDIR:-/tmp}/baize-scheduler-health-diagnostics-test
MODULE="$WORK/module"
STATE="$WORK/state"
PUBLIC="$WORK/public"
rm -rf "$WORK"
mkdir -p "$MODULE/config" "$STATE/logs" "$STATE/reports" "$PUBLIC"
cp "$ROOT/module/diagnostics-export.sh" "$MODULE/diagnostics-export.sh"
chmod +x "$MODULE/diagnostics-export.sh"

cat >"$MODULE/module.prop" <<'EOF'
id=baize_v2
name=BaiZe
version=v-test
versionCode=1
EOF
cat >"$MODULE/config/rules.meta.env" <<'EOF'
rules_version=test
EOF
cat >"$STATE/scheduler.env" <<'EOF'
state=waiting
reason=cache:等待息屏
queue_count=1
blocked_groups=cache:等待息屏
queue_schema=fixed-seven-fields-v1
EOF
cat >"$STATE/supervisor.env" <<'EOF'
status=running
reason=scheduler_running
EOF
cat >"$STATE/config.conf" <<'EOF'
enabled=1
custom_path=/storage/emulated/0/Private/Secret
schedule_cache_minutes=5
EOF
printf '0\t1\tcache\tcache-auto\tmanual\t%s\t-\n' "$STATE/scheduler-requests/private-cache.env" >"$STATE/scheduler-queue.tsv"
printf 'action\trisk\tcategory\titems\tbytes\tpath\ncleaned\tlow\tcache\t1\t128\t/storage/emulated/0/Private/secret.log\n' >"$STATE/reports/latest.tsv"
printf 'package=com.example.private path=/storage/emulated/0/Private/secret.log\n' >"$STATE/logs/scheduler-cache.log"

OUTPUT=$(BAIZE_STATE_DIR="$STATE" BAIZE_DIAG_PUBLIC_DIR="$PUBLIC" sh "$MODULE/diagnostics-export.sh")
[ -f "$OUTPUT" ]
case "$OUTPUT" in "$PUBLIC"/BaiZe-diagnostics-*.zip) ;; *) echo "unexpected export path: $OUTPUT" >&2; exit 1 ;; esac

LIST=$(unzip -Z1 "$OUTPUT")
printf '%s\n' "$LIST" | grep -qx 'scheduler.env'
printf '%s\n' "$LIST" | grep -qx 'supervisor.env'
printf '%s\n' "$LIST" | grep -qx 'config-redacted.conf'
printf '%s\n' "$LIST" | grep -qx 'scheduler-queue-redacted.tsv'
printf '%s\n' "$LIST" | grep -qx 'latest-redacted.tsv'

CONFIG=$(unzip -p "$OUTPUT" config-redacted.conf)
printf '%s\n' "$CONFIG" | grep -q '^custom_path=<redacted>$'
! printf '%s\n' "$CONFIG" | grep -q '/Private/Secret'

QUEUE=$(unzip -p "$OUTPUT" scheduler-queue-redacted.tsv)
printf '%s\n' "$QUEUE" | grep -q '<request-file>'
! printf '%s\n' "$QUEUE" | grep -q 'private-cache.env'

REPORT=$(unzip -p "$OUTPUT" latest-redacted.tsv)
printf '%s\n' "$REPORT" | grep -q 'secret.log#'
! printf '%s\n' "$REPORT" | grep -q '/storage/emulated/0/Private'

LOG=$(unzip -p "$OUTPUT" scheduler-cache.log)
printf '%s\n' "$LOG" | grep -q 'package=<redacted-package>'
printf '%s\n' "$LOG" | grep -q '<redacted-path>'
! printf '%s\n' "$LOG" | grep -q 'com.example.private'

echo "scheduler health diagnostics regression passed"
