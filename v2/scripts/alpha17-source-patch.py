from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


root = Path("v2")
replace_once(
    root / "app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt",
    '''    private fun normalizeAccent(value: String): String = when (value) {
        "aurora" -> "purple"
        "jade" -> "light_blue"
        "sunset" -> "red"
        "blue" -> "default"
        else -> palettes.firstOrNull { it.id == value }?.id ?: "default"
    }
''',
    '''    private fun normalizeAccent(value: String): String {
        palettes.firstOrNull { it.id == value }?.let { return it.id }
        return when (value) {
            "aurora" -> "purple"
            "jade" -> "light_blue"
            "sunset" -> "red"
            else -> "default"
        }
    }
''',
    "accent normalization",
)
replace_once(
    root / "app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt",
    '            else -> editor.putString(KEY_ACCENT, normalizeAccent(oldPalette))\n',
    '            else -> editor.putString(KEY_ACCENT, if (oldPalette == "blue") "default" else normalizeAccent(oldPalette))\n',
    "legacy blue migration",
)
replace_once(
    root / "app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt",
    '        binding.versionText.text = "Alpha 16"\n',
    '        binding.versionText.text = "Alpha 17"\n',
    "dashboard version",
)
replace_once(
    root / "app/src/main/res/layout/activity_dashboard.xml",
    'android:text="Alpha 16"',
    'android:text="Alpha 17"',
    "dashboard placeholder",
)
replace_once(
    root / "module/module.prop",
    '''version=v2.0.0-alpha16
versionCode=20050
''',
    '''version=v2.0.0-alpha17
versionCode=20060
''',
    "module version",
)
replace_once(
    root / "module/module.prop",
    'description=白泽 v2 Alpha 16：按参考 App 重构简约 MIUIx 组件层，采用实心高对比卡片、大标题、克制单色强调和可选 Monet，仅底栏保留轻量玻璃。\n',
    'description=白泽 v2 Alpha 17：真正重做 BOX 风格主题系统，加入 MIUIx 自绘开关、锚点悬浮菜单、Monet 风格、色彩标准、强调色和完整视觉效果开关。\n',
    "module description",
)
replace_once(
    root / "module/customize.sh",
    'ui_print "- 安装白泽 v2 Alpha 16 UI 重构版"\n',
    'ui_print "- 安装白泽 v2 Alpha 17 BOX 主题重构版"\n',
    "installer title",
)
replace_once(
    root / "scripts/package-module.sh",
    'OUTPUT="$OUT/BaiZe-v2-Alpha16-Module.zip"\n',
    'OUTPUT="$OUT/BaiZe-v2-Alpha17-Module.zip"\n',
    "package output",
)
replace_once(
    root / "scripts/package-module.sh",
    'echo "已生成 Alpha 16 简约 MIUIx 组件层、完整清理引擎、白名单、Monet 与规则库一体化模块：$OUTPUT"\n',
    'echo "已生成 Alpha 17 BOX 风格主题系统、完整清理引擎、白名单、Monet 与规则库一体化模块：$OUTPUT"\n',
    "package message",
)
