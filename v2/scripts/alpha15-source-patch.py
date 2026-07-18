from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"missing patch start: {label} in {path}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"missing patch end: {label} in {path}")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


root = Path("v2")
dashboard = root / "app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
layout = root / "app/src/main/res/layout/activity_dashboard.xml"
manifest = root / "app/src/main/AndroidManifest.xml"
root_service = root / "app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
polish = root / "app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt"
themes = root / "app/src/main/res/values/themes.xml"
night_themes = root / "app/src/main/res/values-night/themes.xml"
build_gradle = root / "app/build.gradle.kts"
module_prop = root / "module/module.prop"
customize = root / "module/customize.sh"
package_script = root / "scripts/package-module.sh"
default_config = Path("config/default.conf")
cleaner = Path("cleaner.sh")

# Dashboard: full theme settings screen and root-shell cleanup controls.
replace_once(dashboard, '        binding.versionText.text = "Alpha 14"\n', '        binding.versionText.text = "Alpha 15"\n', "dashboard version")
replace_between(
    dashboard,
    "    private fun setupThemePicker() {\n",
    "    private fun showLargeFileDialog(current: Int) {\n",
    '''    private fun setupThemePicker() {
        renderThemeSummary()
        binding.themeButton.setOnClickListener {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }
    }

    private fun renderThemeSummary() {
        binding.themeSummaryText.text = ThemeManager.themeSummary(this)
    }

''',
    "theme settings navigation",
)
replace_once(
    dashboard,
    '''        binding.installerDaysSlider.addOnChangeListener { _, value, _ ->
            binding.installerDaysText.text = "安装临时文件保留 ${value.toInt()} 天"
        }
''',
    '''        binding.installerDaysSlider.addOnChangeListener { _, value, _ ->
            binding.installerDaysText.text = "安装临时文件保留 ${value.toInt()} 天"
        }
        binding.rootShellDaysSlider.addOnChangeListener { _, value, _ ->
            binding.rootShellDaysText.text = "根目录空壳保留 ${value.toInt()} 天"
        }
''',
    "root shell retention listener",
)
replace_once(
    dashboard,
    '''            binding.installerDaysSlider.value = json.optInt("installer_temp_days", 7).coerceIn(1, 30).toFloat()
            binding.cleanInternalCacheSwitch.isChecked = json.optInt("clean_app_cache", 1) == 1
''',
    '''            binding.installerDaysSlider.value = json.optInt("installer_temp_days", 7).coerceIn(1, 30).toFloat()
            binding.rootShellDaysSlider.value = json.optInt("root_shell_days", 14).coerceIn(1, 90).toFloat()
            binding.cleanInternalCacheSwitch.isChecked = json.optInt("clean_app_cache", 1) == 1
''',
    "load root shell retention",
)
replace_once(
    dashboard,
    '''            binding.cleanEmptyDirsSwitch.isChecked = json.optInt("clean_empty_dirs", 1) == 1
            binding.cleanFragmentsSwitch.isChecked = json.optInt("clean_fragments", 1) == 1
''',
    '''            binding.cleanEmptyDirsSwitch.isChecked = json.optInt("clean_empty_dirs", 1) == 1
            binding.cleanRootShellsSwitch.isChecked = json.optInt("clean_root_shells", 1) == 1
            binding.cleanFragmentsSwitch.isChecked = json.optInt("clean_fragments", 1) == 1
''',
    "load root shell toggle",
)
replace_once(
    dashboard,
    '''            binding.installerDaysText.text = "安装临时文件保留 ${binding.installerDaysSlider.value.toInt()} 天"
            updatePlanPreview()
''',
    '''            binding.installerDaysText.text = "安装临时文件保留 ${binding.installerDaysSlider.value.toInt()} 天"
            binding.rootShellDaysText.text = "根目录空壳保留 ${binding.rootShellDaysSlider.value.toInt()} 天"
            updatePlanPreview()
''',
    "render root shell retention",
)
replace_once(
    dashboard,
    '''            .put("clean_empty_dirs", flag(binding.cleanEmptyDirsSwitch.isChecked))
            .put("clean_fragments", flag(binding.cleanFragmentsSwitch.isChecked))
''',
    '''            .put("clean_empty_dirs", flag(binding.cleanEmptyDirsSwitch.isChecked))
            .put("clean_root_shells", flag(binding.cleanRootShellsSwitch.isChecked))
            .put("clean_fragments", flag(binding.cleanFragmentsSwitch.isChecked))
''',
    "save root shell toggle",
)
replace_once(
    dashboard,
    '''            .put("installer_temp_days", binding.installerDaysSlider.value.toInt())
''',
    '''            .put("installer_temp_days", binding.installerDaysSlider.value.toInt())
            .put("root_shell_days", binding.rootShellDaysSlider.value.toInt())
''',
    "save root shell retention",
)

replace_once(
    layout,
    '''                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanEmptyDirsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="安全范围内的空目录"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanFragmentsSwitch"
''',
    '''                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanEmptyDirsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="安全范围内的空目录"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanRootShellsSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="共享存储根目录空壳（严格保护）"
                            android:textColor="?attr/colorOnSurface" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/cleanFragmentsSwitch"
''',
    "root shell switch UI",
)
replace_once(
    layout,
    '''                        <com.google.android.material.slider.Slider
                            android:id="@+id/installerDaysSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:stepSize="1"
                            android:value="7"
                            android:valueFrom="1"
                            android:valueTo="30" />
''',
    '''                        <com.google.android.material.slider.Slider
                            android:id="@+id/installerDaysSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:stepSize="1"
                            android:value="7"
                            android:valueFrom="1"
                            android:valueTo="30" />

                        <TextView
                            android:id="@+id/rootShellDaysText"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:text="根目录空壳保留 14 天"
                            android:textColor="?attr/colorOnSurface"
                            android:textStyle="bold" />

                        <com.google.android.material.slider.Slider
                            android:id="@+id/rootShellDaysSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:stepSize="1"
                            android:value="14"
                            android:valueFrom="1"
                            android:valueTo="90" />
''',
    "root shell retention UI",
)
replace_once(layout, 'android:text="选择主题配色" />', 'android:text="主题模式、Monet 与配色" />', "theme button label")

# Manifest registration.
replace_once(
    manifest,
    '        <activity android:name=".WhitelistActivity" android:exported="false" android:windowSoftInputMode="adjustResize" />\n',
    '        <activity android:name=".WhitelistActivity" android:exported="false" android:windowSoftInputMode="adjustResize" />\n        <activity android:name=".ThemeSettingsActivity" android:exported="false" />\n',
    "theme settings activity manifest",
)

# Root config API accepts root-shell controls.
replace_once(
    root_service,
    '''            "clean_empty_dirs" to 0..1,
            "clean_app_rules" to 0..1,
''',
    '''            "clean_empty_dirs" to 0..1,
            "clean_root_shells" to 0..1,
            "clean_app_rules" to 0..1,
''',
    "root shell config toggle",
)
replace_once(
    root_service,
    '''            "installer_temp_days" to 1..30,
            "max_file_mb" to 16..16_384
''',
    '''            "installer_temp_days" to 1..30,
            "root_shell_days" to 1..90,
            "max_file_mb" to 16..16_384
''',
    "root shell retention config",
)

# Default configuration.
replace_once(
    default_config,
    '''clean_empty_dirs=1
clean_app_rules=1
''',
    '''clean_empty_dirs=1
clean_root_shells=1
clean_app_rules=1
''',
    "default root shell toggle",
)
replace_once(
    default_config,
    '''installer_temp_days=7
max_file_mb=256
''',
    '''installer_temp_days=7
root_shell_days=14
max_file_mb=256
''',
    "default root shell retention",
)

# Cleaner: strictly empty root shells and Android/media orphan coverage.
root_shell_functions = r'''
is_reserved_shared_root() {
  name=${1##*/}
  case "$name" in
    ''|.*|Android|DCIM|Download|Documents|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|Recordings|Fonts|MIUI|ColorOS|HeyTap|oplus|Tencent|WeChat|QQ|backups|Backup|LOST.DIR) return 0 ;;
  esac
  return 1
}

is_mount_target() {
  awk -v target="$1" '$2 == target { found=1; exit } END { exit found ? 0 : 1 }' /proc/mounts 2>/dev/null
}

root_shell_old_enough() {
  [ "$ROOT_SHELL_DAYS" -le 0 ] && return 0
  find "$1" -maxdepth 0 -mtime "+$ROOT_SHELL_DAYS" -print -quit 2>/dev/null | grep -q .
}

root_shell_effectively_empty() {
  dir=$1
  [ -d "$dir" ] || return 1
  [ -L "$dir" ] && return 1
  is_mount_target "$dir" && return 1
  LIST_SEQ=$((LIST_SEQ + 1))
  probe="$TMP_DIR/root-shell-probe.$LIST_SEQ"
  run_limited_command 6 find "$dir" -mindepth 1 \
    ! -type d \
    ! \( -type f -size 0c \( -name '.nomedia' -o -name '.keep' -o -name '.gitkeep' -o -name '.placeholder' \) \) \
    -print -quit >"$probe" 2>/dev/null
  probe_code=$?
  if [ "$probe_code" -ne 0 ]; then
    PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
    log_line "[根目录保护:扫描超时或异常] $dir"
    report_line protected slow 根目录空壳 1 0 "$dir"
    rm -f "$probe"
    return 1
  fi
  if [ -s "$probe" ]; then
    rm -f "$probe"
    return 1
  fi
  rm -f "$probe"
  return 0
}

run_shared_root_shells() {
  [ -d /data/media ] || return 0
  for userdir in /data/media/[0-9]*; do
    [ -d "$userdir" ] || continue
    for dir in "$userdir"/*; do
      should_stop && return 9
      [ -d "$dir" ] || continue
      [ -L "$dir" ] && continue
      is_reserved_shared_root "$dir" && continue
      root_shell_old_enough "$dir" || continue
      if is_whitelisted "$dir" || deep_conflicts_whitelist "$dir"; then
        SKIPPED=$((SKIPPED + 1))
        log_line "[根目录跳过:白名单] $dir"
        continue
      fi
      root_shell_effectively_empty "$dir" || continue

      if [ "$MODE" = "scan" ]; then
        EMPTY_DIRS=$((EMPTY_DIRS + 1))
        log_line "[根目录空壳候选] $dir（保留 ${ROOT_SHELL_DAYS} 天）"
        report_line candidate medium 根目录空壳 1 0 "$dir"
        continue
      fi

      find "$dir" -type f -size 0c \
        \( -name '.nomedia' -o -name '.keep' -o -name '.gitkeep' -o -name '.placeholder' \) \
        -delete 2>/dev/null
      run_limited_command 10 find "$dir" -depth -type d -empty -exec rmdir {} \; >/dev/null 2>&1
      if [ ! -e "$dir" ]; then
        EMPTY_DIRS=$((EMPTY_DIRS + 1))
        log_line "[根目录空壳已清理] $dir"
        report_line cleaned medium 根目录空壳 1 0 "$dir"
      else
        ERRORS=$((ERRORS + 1))
        log_line "[根目录空壳未清理] $dir（目录状态发生变化或系统拒绝）"
        report_line failed medium 根目录空壳 1 0 "$dir"
      fi
    done
  done
  return 0
}

'''
replace_once(cleaner, "\nis_protected_hidden_path() {\n", root_shell_functions + "is_protected_hidden_path() {\n", "root shell cleaner functions")
replace_once(
    cleaner,
    '''        /data/media/[0-9]*/Android/data/*|/data/media/[0-9]*/Android/obb/*) ;;
''',
    '''        /data/media/[0-9]*/Android/data/*|/data/media/[0-9]*/Android/obb/*|/data/media/[0-9]*/Android/media/*) ;;
''',
    "corpse clean Android media",
)
replace_once(
    cleaner,
    '''    for root in "$userdir/Android/data" "$userdir/Android/obb"; do
''',
    '''    for root in "$userdir/Android/data" "$userdir/Android/obb" "$userdir/Android/media"; do
''',
    "corpse scan Android media",
)
replace_once(
    cleaner,
    '''INSTALLER_TEMP_DAYS=$(get_uint installer_temp_days 7 1 30)
if [ "$FRAGMENT_DAYS" -eq 0 ]; then
''',
    '''INSTALLER_TEMP_DAYS=$(get_uint installer_temp_days 7 1 30)
ROOT_SHELL_DAYS=$(get_uint root_shell_days 14 1 90)
if [ "$FRAGMENT_DAYS" -eq 0 ]; then
''',
    "root shell runtime retention",
)
replace_once(
    cleaner,
    '''CLEAN_EMPTY_DIRS=$(get_bool clean_empty_dirs)
RUN_EMPTY=0
''',
    '''CLEAN_EMPTY_DIRS=$(get_bool clean_empty_dirs)
CLEAN_ROOT_SHELLS=$(get_bool clean_root_shells)
RUN_EMPTY=0
''',
    "root shell runtime toggle",
)
replace_once(
    cleaner,
    '''if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_EMPTY_DIRS" = "1" ]; then
  set_phase "清理共享存储空目录"
  scan_shared_empty_dirs || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_CACHE" = "1" ] && [ "$(get_bool clean_app_cache)" = "1" ]; then
''',
    '''if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_EMPTY_DIRS" = "1" ]; then
  set_phase "清理共享存储空目录"
  scan_shared_empty_dirs || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_EMPTY" = "1" ] && [ "$CLEAN_ROOT_SHELLS" = "1" ]; then
  set_phase "识别共享存储根目录空壳"
  run_shared_root_shells || STOPPED=1
fi

if [ "$STOPPED" = "0" ] && [ "$RUN_CACHE" = "1" ] && [ "$(get_bool clean_app_cache)" = "1" ]; then
''',
    "root shell invocation",
)

# Opaque content cards; do not clear MaterialCardView's internal background drawable.
replace_once(polish, "                view.background = null\n                view.setCardBackgroundColor(surface)\n", "                view.setCardBackgroundColor(surface)\n", "preserve Material card shape")

# Explicit activity background attributes improve dynamic and fixed palette readability.
replace_once(themes, '        <item name="android:windowBackground">#F1F2FB</item>\n', '        <item name="android:colorBackground">#F1F2FB</item>\n        <item name="android:windowBackground">#F1F2FB</item>\n', "light color background")
replace_once(night_themes, '        <item name="android:windowBackground">#12131A</item>\n', '        <item name="android:colorBackground">#12131A</item>\n        <item name="android:windowBackground">#12131A</item>\n', "dark color background")

# Version and packaging.
replace_once(build_gradle, '        versionCode = 20030\n        versionName = "2.0.0-alpha14"\n', '        versionCode = 20040\n        versionName = "2.0.0-alpha15"\n', "app version")
replace_once(module_prop, 'version=v2.0.0-alpha14\nversionCode=20030\n', 'version=v2.0.0-alpha15\nversionCode=20040\n', "module version")
replace_once(
    module_prop,
    'description=白泽 v2 Alpha 14：Root 完整应用白名单、完整清理范围与保留策略、过期安装临时文件、安全明暗主题和精简 MIUI X 玻璃界面。\n',
    'description=白泽 v2 Alpha 15：修复系统栏布局，新增严格根目录空壳与 Android/media 残留识别，重做高对比 MIUIx 界面，并支持可选 Monet、明暗模式和纯黑主题。\n',
    "module description",
)
replace_once(customize, 'ui_print "- 安装白泽 v2 Alpha 14"\n', 'ui_print "- 安装白泽 v2 Alpha 15"\n', "installer title")
replace_once(package_script, 'OUTPUT="$OUT/BaiZe-v2-Alpha14-Module.zip"\n', 'OUTPUT="$OUT/BaiZe-v2-Alpha15-Module.zip"\n', "package output")
replace_once(
    package_script,
    'echo "已生成 Alpha 14 完整白名单、可配置清理引擎、明暗 MIUI X UI 与规则库一体化模块：$OUTPUT"\n',
    'echo "已生成 Alpha 15 根目录空壳清理、完整白名单、Monet MIUIx UI 与规则库一体化模块：$OUTPUT"\n',
    "package message",
)
