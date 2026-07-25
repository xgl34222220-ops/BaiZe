from pathlib import Path
from textwrap import dedent


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old!r}")
    file.write_text(text.replace(old, new, 1))


app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(app, "    val cacheMinutes: Int = 60,", "    val cacheMinutes: Int = 1_440,")
replace_once(app, "    val emptyMinutes: Int = 60,", "    val emptyMinutes: Int = 1_440,")
replace_once(app, "    val rulesMinutes: Int = 360,", "    val rulesMinutes: Int = 1_440,")
replace_once(app, "    val fragmentMinutes: Int = 720,", "    val fragmentMinutes: Int = 4_320,")
replace_once(app, "    val screenOffOnly: Boolean = false,", "    val screenOffOnly: Boolean = true,")
replace_once(app, 'json.optInt("schedule_cache_hours", 1) * 60', 'json.optInt("schedule_cache_hours", 24) * 60')
replace_once(app, 'json.optInt("schedule_empty_hours", 1) * 60', 'json.optInt("schedule_empty_hours", 24) * 60')
replace_once(app, 'json.optInt("schedule_rules_hours", 6) * 60', 'json.optInt("schedule_rules_hours", 24) * 60')
replace_once(app, 'json.optInt("schedule_fragment_hours", 12) * 60', 'json.optInt("schedule_fragment_hours", 72) * 60')
replace_once(app, 'screenOffOnly = json.optInt("screen_off_only", 0) == 1,', 'screenOffOnly = json.optInt("screen_off_only", 1) == 1,')

repository = "v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
replace_once(
    repository,
    '            reason.contains("充电") -> "WAIT_CHARGING"\n',
    '            reason.contains("充电") -> "WAIT_CHARGING"\n'
    '            reason.contains("温度") || reason.contains("过热") -> "WAIT_TEMPERATURE"\n',
)
replace_once(
    repository,
    '        raw.contains("充电") -> "等待充电后执行"\n',
    '        raw.contains("充电") -> "等待充电后执行"\n'
    '        raw.contains("温度") || raw.contains("过热") -> "等待设备降温后执行"\n',
)
replace_once(
    repository,
    '        val dailyEnabled = config.optInt("daily_schedule_enabled", 0) == 1',
    dedent(
        """\
        val dailyEnabled = when {
            config.has("schedule_mode") -> config.optInt("schedule_mode", 0).coerceIn(0, 2) == 2
            else -> config.optInt("daily_schedule_enabled", 0) == 1
        }"""
    ).replace("\n", "\n        ", 1),
)
replace_once(repository, '                "rules" -> 360', '                "cache", "empty", "rules" -> 1_440')
replace_once(repository, '                "fragment" -> 720', '                "fragment" -> 4_320')
replace_once(repository, '                else -> 60', '                else -> 1_440')

scheduler = "v2/module/scheduler-v2.5.sh"
replace_once(
    scheduler,
    "  battery=$(dumpsys battery 2>/dev/null)\n",
    dedent(
        """\
          battery=$(dumpsys battery 2>/dev/null)
          maximum_temp=$(uint_value max_battery_temp 42 30 60)
          temperature=$(printf '%s\\n' "$battery" | sed -n 's/^[[:space:]]*temperature: //p' | head -n 1)
          case "$temperature" in ''|*[!0-9]*) temperature=0 ;; esac
          if [ "$temperature" -gt 0 ] && [ "$temperature" -ge $((maximum_temp * 10)) ]; then
            temp_whole=$((temperature / 10)); temp_decimal=$((temperature % 10))
            SCHEDULE_REASON="等待电池温度降低（当前 ${temp_whole}.${temp_decimal}°C，上限 ${maximum_temp}°C）"
            return 1
          fi
        """
    ),
)
replace_once(scheduler, "SPEC_FALLBACK=1; SPEC_MODE=cache-auto", "SPEC_FALLBACK=24; SPEC_MODE=cache-auto")
replace_once(scheduler, "SPEC_FALLBACK=1; SPEC_MODE=empty-clean", "SPEC_FALLBACK=24; SPEC_MODE=empty-clean")
replace_once(scheduler, "SPEC_FALLBACK=6; SPEC_MODE=rules-clean", "SPEC_FALLBACK=24; SPEC_MODE=rules-clean")
replace_once(scheduler, "SPEC_FALLBACK=12; SPEC_MODE=fragment-clean", "SPEC_FALLBACK=72; SPEC_MODE=fragment-clean")
replace_once(
    scheduler,
    '    if [ "$group" != organize ] && [ "$(bool_value daily_schedule_enabled)" = 1 ]; then continue; fi',
    '    if [ "$group" != organize ] && [ "$(daily_mode_enabled)" = 1 ]; then continue; fi',
)
replace_once(
    scheduler,
    '  if [ "$(bool_value daily_schedule_enabled)" = 1 ]; then',
    '  if [ "$(daily_mode_enabled)" = 1 ]; then',
)

activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
replace_once(
    activity,
    '                        reason.contains("空闲") || reason.contains("当前任务") || reason.contains("自动重试") || reason.contains("自动恢复")',
    '                        reason.contains("温度") || reason.contains("过热") || reason.contains("空闲") ||\n'
    '                        reason.contains("当前任务") || reason.contains("自动重试") || reason.contains("自动恢复")',
)

labels = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SchedulerText.kt"
replace_once(
    labels,
    '        value.contains("充电") -> "等待充电后执行"\n',
    '        value.contains("充电") -> "等待充电后执行"\n'
    '        value.contains("温度") || value.contains("过热") -> "等待设备降温后执行"\n',
)

test = Path("v2/tests/test-scheduler-thermal-contract.sh")
test.write_text(
    dedent(
        r'''\
        #!/usr/bin/env bash
        set -euo pipefail

        ROOT=$(cd "$(dirname "$0")/../.." && pwd)
        TMP=${TMPDIR:-/tmp}/baize-scheduler-thermal-$$
        trap 'rm -rf "$TMP"' EXIT
        mkdir -p "$TMP/module" "$TMP/state" "$TMP/bin"
        cp "$ROOT/v2/module/scheduler-v2.5.sh" "$TMP/module/scheduler.sh"

        cat >"$TMP/module/task-worker.sh" <<'EOF_WORKER'
        #!/bin/sh
        echo "$*" >>"${BAIZE_STATE_DIR}/worker-invocations.log"
        exit 0
        EOF_WORKER
        chmod +x "$TMP/module/task-worker.sh"

        cat >"$TMP/bin/dumpsys" <<'EOF_DUMPSYS'
        #!/bin/sh
        case "${1:-}" in
          power) echo 'mInteractive=false' ;;
          deviceidle) echo 'mState=IDLE' ;;
          battery)
            cat <<EOF_BATTERY
        AC powered: false
        USB powered: false
        Wireless powered: false
        status: 3
        level: 80
        temperature: ${BAIZE_TEST_TEMP:-430}
        EOF_BATTERY
            ;;
        esac
        EOF_DUMPSYS
        chmod +x "$TMP/bin/dumpsys"

        cat >"$TMP/state/config.conf" <<'EOF_CONFIG'
        enabled=1
        schedule_mode=1
        autopilot_enabled=0
        daily_schedule_enabled=0
        screen_off_only=0
        charging_only=0
        device_idle_only=0
        min_battery=0
        max_battery_temp=42
        schedule_cache_enabled=1
        schedule_cache_minutes=5
        schedule_empty_enabled=0
        schedule_rules_enabled=0
        schedule_fragment_enabled=0
        schedule_deep_enabled=0
        schedule_organize_enabled=0
        EOF_CONFIG

        PATH="$TMP/bin:$PATH" BAIZE_TEST_TEMP=430 BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
          BAIZE_MODULE_DIR="$TMP/module" BAIZE_STATE_DIR="$TMP/state" BAIZE_CONFIG_PATH="$TMP/state/config.conf" \
          sh "$TMP/module/scheduler.sh"
        test ! -s "$TMP/state/worker-invocations.log"
        grep -q '温度' "$TMP/state/scheduler.env"

        PATH="$TMP/bin:$PATH" BAIZE_TEST_TEMP=410 BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
          BAIZE_MODULE_DIR="$TMP/module" BAIZE_STATE_DIR="$TMP/state" BAIZE_CONFIG_PATH="$TMP/state/config.conf" \
          sh "$TMP/module/scheduler.sh"
        grep -q 'cache-auto scheduler:interval' "$TMP/state/worker-invocations.log"

        echo 'scheduler thermal contract passed'
        '''
    )
)
test.chmod(0o755)

contract = "v2/tests/test-schedule-modes-contract.sh"
replace_once(
    contract,
    "grep -q 'daily_mode_enabled' \"$SCHEDULER\"\n",
    "grep -q 'daily_mode_enabled' \"$SCHEDULER\"\n"
    "grep -q 'SPEC_FALLBACK=24; SPEC_MODE=cache-auto' \"$SCHEDULER\"\n"
    "grep -q 'SPEC_FALLBACK=72; SPEC_MODE=fragment-clean' \"$SCHEDULER\"\n"
    "grep -q 'maximum_temp=$(uint_value max_battery_temp 42 30 60)' \"$SCHEDULER\"\n"
    "grep -q 'val cacheMinutes: Int = 1_440' \"$APP_STATE\"\n"
    "grep -q 'val fragmentMinutes: Int = 4_320' \"$APP_STATE\"\n"
    "grep -q 'val screenOffOnly: Boolean = true' \"$APP_STATE\"\n",
)

ci = ".github/workflows/v2.5-concurrent-scheduler-ci.yml"
replace_once(
    ci,
    "      - name: Explicit schedule modes contract\n        run: bash v2/tests/test-schedule-modes-contract.sh\n",
    "      - name: Explicit schedule modes contract\n        run: bash v2/tests/test-schedule-modes-contract.sh\n"
    "      - name: Scheduler thermal guard contract\n        run: bash v2/tests/test-scheduler-thermal-contract.sh\n",
)

for helper in (
    ".github/workflows/apply-scheduler-hardening.yml",
    ".github/workflows/apply-scheduler-hardening-debug.yml",
    "v2/scripts/apply-scheduler-hardening.py",
):
    Path(helper).unlink(missing_ok=True)
