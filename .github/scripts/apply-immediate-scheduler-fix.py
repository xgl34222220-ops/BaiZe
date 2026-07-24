from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def find_unique(name: str, marker: str) -> Path:
    matches = [p for p in ROOT.rglob(name) if p.is_file() and marker in read(p)]
    if len(matches) != 1:
        raise RuntimeError(f"{name}: expected one file containing {marker!r}, found {matches}")
    return matches[0]


# Root scheduler: no hidden post-boot delay, quicker condition recovery, no hidden temperature gate.
scheduler = find_unique("scheduler.sh", "BaiZe v2.4 unified Root scheduler")
text = read(scheduler)
text = replace_once(
    text,
    'CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-60}',
    'CONDITION_RETRY_SECONDS=${BAIZE_CONDITION_RETRY_SECONDS:-15}',
    "scheduler retry cadence",
)
text = replace_once(
    text,
    '''if [ "${BAIZE_SKIP_BOOT_WAIT:-0}" != 1 ]; then
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 10; done
  # Wait for storage/package/media services to settle. Tests opt out with BAIZE_SKIP_BOOT_WAIT=1.
  sleep 120
fi''',
    '''if [ "${BAIZE_SKIP_BOOT_WAIT:-0}" != 1 ]; then
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 2; done
fi''',
    "scheduler boot delay",
)
text = replace_once(
    text,
    '''  ca_max_temp=$(uint_value max_battery_temp 45 30 60)
  ca_temp=$(printf '%s\\n' "$ca_battery_dump" | sed -n 's/^[[:space:]]*temperature: //p' | head -n 1)
  case "$ca_temp" in ''|*[!0-9]*) ca_temp=0 ;; esac
  if [ "$ca_temp" -gt $((ca_max_temp * 10)) ]; then
    ca_temp_text=$(awk -v t="$ca_temp" 'BEGIN {printf "%.1f", t/10}')
    SCHEDULE_REASON="等待电池降温（当前 ${ca_temp_text}°C，上限 ${ca_max_temp}°C）"; return 1
  fi
''',
    "",
    "hidden temperature condition",
)
text = text.replace('rn_reason="${rn_group}:待执行"', 'rn_reason="${rn_group}:等待自动重试"')
text = text.replace('write_scheduler_state waiting "$hr_group" "待执行"', 'write_scheduler_state waiting "$hr_group" "等待自动重试"')
write(scheduler, text)

# Module defaults: automatic means runnable immediately unless the user explicitly enables conditions.
default_conf = find_unique("default.conf", "schedule_cache_enabled=1")
text = read(default_conf)
text = replace_once(text, "screen_off_only=1", "screen_off_only=0", "default screen-off condition")
text = replace_once(text, "max_battery_temp=45", "max_battery_temp=0", "unused hidden temperature default")
text = replace_once(text, "organize_screen_off_only=1", "organize_screen_off_only=0", "default organizer screen-off condition")
write(default_conf, text)

# App defaults/fallbacks must match the module defaults.
app_state = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
text = read(app_state)
for old, new, label in [
    ("val organizeScreenOffOnly: Boolean = true", "val organizeScreenOffOnly: Boolean = false", "organizer UI default"),
    ("val screenOffOnly: Boolean = true", "val screenOffOnly: Boolean = false", "scheduler UI default"),
    ('json.optInt("organize_screen_off_only", 1) == 1', 'json.optInt("organize_screen_off_only", 0) == 1', "organizer JSON fallback"),
    ('json.optInt("screen_off_only", 1) == 1', 'json.optInt("screen_off_only", 0) == 1', "scheduler JSON fallback"),
]:
    text = replace_once(text, old, new, label)
write(app_state, text)

# Scheduler public reason should never hide automatic recovery behind a generic due label.
repo_file = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
text = read(repo_file)
text = replace_once(
    text,
    '        raw.contains("已有") || raw.contains("当前任务") -> "等待当前任务完成"\n        raw.contains("没有到期") || raw.contains("下次") -> "等待下次执行"',
    '        raw.contains("已有") || raw.contains("当前任务") -> "等待当前任务完成"\n        raw.contains("重试") -> "等待自动重试"\n        raw.contains("没有到期") || raw.contains("下次") -> "等待下次执行"',
    "public retry reason",
)
write(repo_file, text)

# Home labels use real scheduler health/queue/reason instead of time alone.
model = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeTaskPresentation.kt"
text = read(model)
replacement = '''internal fun taskCountdownLabel(
    task: HomeTaskPresentation?,
    nowEpoch: Long,
    scheduler: SchedulerUiState
): String {
    if (task == null || !task.enabled) return "自动任务已关闭"
    if (scheduler.runtimeState == "running" && scheduler.runtimeGroup == task.id) return "正在后台执行"
    if (scheduler.runtimeStale) return "后台调度正在自动恢复"

    val remaining = task.nextEpoch - nowEpoch
    if (task.nextEpoch <= 0L) return "正在计算执行时间"
    if (remaining <= 30L) {
        val reason = scheduler.runtimeReason.trim()
        return when {
            reason.contains("息屏") || reason.contains("充电") || reason.contains("电量") ||
                reason.contains("空闲") || reason.contains("当前任务") || reason.contains("重试") -> reason
            scheduler.queueCount > 0 && scheduler.nextTask == task.id -> "已进入队列，即将执行"
            scheduler.queueCount > 0 -> "等待当前任务完成"
            else -> "即将启动后台任务"
        }
    }
    val minutes = (remaining + 59L) / 60L
    if (minutes < 60L) return "还有 ${minutes} 分钟执行"
    val hours = minutes / 60L
    val minutePart = minutes % 60L
    if (hours < 24L) {
        return if (minutePart == 0L) {
            "还有 ${hours} 小时执行"
        } else {
            "还有 ${hours} 小时 ${minutePart} 分钟执行"
        }
    }
    val days = hours / 24L
    val hourPart = hours % 24L
    return if (hourPart == 0L) {
        "还有 ${days} 天执行"
    } else {
        "还有 ${days} 天 ${hourPart} 小时执行"
    }
}
'''
text, count = re.subn(
    r'internal fun taskCountdownLabel\(.*?\n}\n\n@Composable',
    replacement + '\n@Composable',
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError("home countdown function not found")
write(model, text)

# Thread scheduler state through both theme layouts.
material = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt"
text = read(material)
text = replace_once(text, "taskCountdownLabel(nextTask, nowEpoch)", "taskCountdownLabel(nextTask, nowEpoch, scheduler)", "material next task label")
text = replace_once(text, "MaterialTaskScheduleCard(tasks, nowEpoch, onOpenClean)", "MaterialTaskScheduleCard(tasks, nowEpoch, scheduler, onOpenClean)", "material schedule call")
text = replace_once(text, "    nowEpoch: Long,\n    onClick: () -> Unit", "    nowEpoch: Long,\n    scheduler: SchedulerUiState,\n    onClick: () -> Unit", "material schedule signature")
text = replace_once(text, "MaterialTaskRow(task, nowEpoch)", "MaterialTaskRow(task, nowEpoch, scheduler)", "material row call")
text = replace_once(text, "private fun MaterialTaskRow(task: HomeTaskPresentation, nowEpoch: Long)", "private fun MaterialTaskRow(task: HomeTaskPresentation, nowEpoch: Long, scheduler: SchedulerUiState)", "material row signature")
text = replace_once(text, "taskCountdownLabel(task, nowEpoch)", "taskCountdownLabel(task, nowEpoch, scheduler)", "material row label")
write(material, text)

miuix = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/miuix/HomeScreenMiuix.kt"
text = read(miuix)
text = replace_once(text, "MiuixNextTaskPanel(nextTask, nowEpoch, scheduler.enabled, onOpenClean)", "MiuixNextTaskPanel(nextTask, nowEpoch, scheduler, onOpenClean)", "miuix next task call")
text = replace_once(text, "MiuixTaskGroup(tasks, nowEpoch, onOpenClean)", "MiuixTaskGroup(tasks, nowEpoch, scheduler, onOpenClean)", "miuix schedule call")
text = replace_once(text, "    automaticEnabled: Boolean,\n    onClick: () -> Unit", "    scheduler: SchedulerUiState,\n    onClick: () -> Unit", "miuix next task signature")
text = replace_once(text, "if (automaticEnabled) taskCountdownLabel(task, nowEpoch) else \"自动任务已关闭\"", "if (scheduler.enabled) taskCountdownLabel(task, nowEpoch, scheduler) else \"自动任务已关闭\"", "miuix next task label")
text = replace_once(text, "    nowEpoch: Long,\n    onClick: () -> Unit", "    nowEpoch: Long,\n    scheduler: SchedulerUiState,\n    onClick: () -> Unit", "miuix schedule signature")
text = replace_once(text, "MiuixTaskRow(task, nowEpoch)", "MiuixTaskRow(task, nowEpoch, scheduler)", "miuix row call")
text = replace_once(text, "private fun MiuixTaskRow(task: HomeTaskPresentation, nowEpoch: Long)", "private fun MiuixTaskRow(task: HomeTaskPresentation, nowEpoch: Long, scheduler: SchedulerUiState)", "miuix row signature")
text = replace_once(text, "taskCountdownLabel(task, nowEpoch)", "taskCountdownLabel(task, nowEpoch, scheduler)", "miuix row label")
write(miuix, text)

# Remove old build-only trigger files that should never remain in the product PR.
for relative in [
    ".github/history-details-build-trigger",
    ".github/organizer-safety-build-trigger.txt",
]:
    path = ROOT / relative
    if path.exists():
        path.unlink()

print(f"patched scheduler: {scheduler.relative_to(ROOT)}")
print(f"patched defaults: {default_conf.relative_to(ROOT)}")
