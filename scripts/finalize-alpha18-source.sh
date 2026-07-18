#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

git fetch origin v2-alpha18
git checkout -B v2-alpha18 origin/v2-alpha18

PATCH=v2/scripts/alpha18-source-patch.py
[ -f "$PATCH" ] || { echo "Alpha 18 source already finalized"; exit 0; }

python3 - <<'PY'
from pathlib import Path

patch = Path('v2/scripts/alpha18-source-patch.py')
text = patch.read_text(encoding='utf-8')
start = text.find('# The theme page itself must also rebuild after changing Monet variants/standards.')
end = text.find('# Manual clean always uses a safe intelligent profile.', start)
if start >= 0 and end > start:
    text = text[:start] + text[end:]
text = text.replace(
    '已生成 Alpha 17 BOX 风格主题、完整清理引擎',
    '已生成 Alpha 17 BOX 风格主题系统、完整清理引擎',
)
patch.write_text(text, encoding='utf-8')
PY

python3 "$PATCH"

python3 - <<'PY'
from pathlib import Path

path = Path('v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt')
text = path.read_text(encoding='utf-8')
old = '''        val previewSwitches = listOf(
            binding.scheduleSwitch,
            binding.dailySwitch,
            binding.cacheScheduleSwitch,
            binding.emptyScheduleSwitch,
            binding.rulesScheduleSwitch,
            binding.fragmentScheduleSwitch,
            binding.deepScheduleSwitch,
            binding.screenOffSwitch,
            binding.chargingSwitch,
            binding.deviceIdleSwitch
        )
        previewSwitches.forEach { toggle ->
            toggle.setOnCheckedChangeListener { _, _ -> if (!loadingConfig) updatePlanPreview() }
        }
'''
new = '''        binding.scheduleSwitch.setOnCheckedChangeListener { _, _ ->
            if (!loadingConfig) updatePlanPreview()
        }
        val previewSwitches = listOf(
            binding.dailySwitch,
            binding.cacheScheduleSwitch,
            binding.emptyScheduleSwitch,
            binding.rulesScheduleSwitch,
            binding.fragmentScheduleSwitch,
            binding.deepScheduleSwitch,
            binding.screenOffSwitch,
            binding.chargingSwitch,
            binding.deviceIdleSwitch
        )
        previewSwitches.forEach { toggle ->
            toggle.setOnCheckedChangeListener { _, _ -> if (!loadingConfig) updatePlanPreview() }
        }
'''
if old not in text:
    raise SystemExit('missing mixed switch listener block')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
PY

rm -f v2/scripts/alpha18-source-patch.py v2/alpha18-trigger.txt

git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'

git add \
  config/default.conf \
  v2/app/build.gradle.kts \
  v2/app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt \
  v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt \
  v2/app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt \
  v2/app/src/main/res/layout/activity_dashboard.xml \
  v2/module/module.prop \
  v2/module/customize.sh \
  v2/scripts/package-module.sh

git add -u v2/scripts/alpha18-source-patch.py v2/alpha18-trigger.txt 2>/dev/null || true

if git diff --cached --quiet; then
  echo "No Alpha 18 source changes to commit"
else
  git commit -m 'Finalize Alpha 18 global Monet and smart automatic cleaning'
  git push origin HEAD:v2-alpha18
fi
