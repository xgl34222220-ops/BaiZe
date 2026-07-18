#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

if [ -f v2/scripts/alpha18-source-patch.py ]; then
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
  python3 v2/scripts/alpha18-source-patch.py

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
fi

python3 v2/scripts/validate-rules.py

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
if command -v sdkmanager >/dev/null 2>&1; then
  yes | sdkmanager 'platforms;android-36' 'build-tools;36.0.0' >/dev/null
fi

GRADLE_CMD=""
if command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=$(command -v gradle)
else
  GRADLE_HOME="$ROOT/.ci-gradle-8.13"
  if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    ARCHIVE="$ROOT/.ci-gradle-8.13-bin.zip"
    curl -fsSL --retry 3 -o "$ARCHIVE" https://services.gradle.org/distributions/gradle-8.13-bin.zip
    rm -rf "$GRADLE_HOME" "$ROOT/gradle-8.13"
    unzip -q "$ARCHIVE" -d "$ROOT"
    mv "$ROOT/gradle-8.13" "$GRADLE_HOME"
  fi
  GRADLE_CMD="$GRADLE_HOME/bin/gradle"
fi

(
  cd v2
  "$GRADLE_CMD" --no-daemon :app:assembleDebug
  sh scripts/package-module.sh
)

OUT="$ROOT/dist"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -f v2/dist/BaiZe-v2-Alpha18-Module.zip "$OUT/BaiZe-v2-Alpha18-Module.zip"
sha256sum "$OUT/BaiZe-v2-Alpha18-Module.zip" > "$OUT/BaiZe-v2-Alpha18-Module.zip.sha256.txt"

unzip -tq "$OUT/BaiZe-v2-Alpha18-Module.zip" >/dev/null
unzip -p "$OUT/BaiZe-v2-Alpha18-Module.zip" module.prop | grep -q 'version=v2.0.0-alpha18'
unzip -l "$OUT/BaiZe-v2-Alpha18-Module.zip" | grep -q 'app/baize.apk'
grep -q 'KEY_REVISION' v2/app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt
grep -q 'smartSchedulerPayload' v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt
grep -q 'confirmRiskAction' v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt
grep -q '智能安全模式' v2/app/src/main/res/layout/activity_dashboard.xml

echo "已生成: $OUT/BaiZe-v2-Alpha18-Module.zip"
