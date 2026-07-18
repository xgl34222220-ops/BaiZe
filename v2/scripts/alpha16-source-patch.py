from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 0:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new), encoding="utf-8")


root = Path("v2")
dashboard_layout = root / "app/src/main/res/layout/activity_dashboard.xml"
dashboard_activity = root / "app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
build_gradle = root / "app/build.gradle.kts"
module_prop = root / "module/module.prop"
customize = root / "module/customize.sh"
package_script = root / "scripts/package-module.sh"

# Turn every normal content card into a solid surface. The runtime polish only adds a tonal hero.
replace_all(
    dashboard_layout,
    'style="@style/Widget.BaiZe.GlassCard"',
    'style="@style/Widget.BaiZe.SimpleCard"',
    "solid dashboard cards",
)
replace_once(
    dashboard_layout,
    'android:tag="glass:hero"\n                    style="@style/Widget.BaiZe.SimpleCard"',
    'android:tag="hero"\n                    style="@style/Widget.BaiZe.HeroCard"',
    "home hero card",
)
replace_once(
    dashboard_layout,
    'android:text="Alpha 8"',
    'android:text="Alpha 16"',
    "dashboard version placeholder",
)
replace_once(
    dashboard_layout,
    'android:textSize="30sp"\n                            android:textStyle="bold"',
    'android:textSize="36sp"\n                            android:textStyle="normal"',
    "home page title",
)
replace_once(
    dashboard_layout,
    'android:paddingTop="12dp"\n                android:paddingEnd="18dp"\n                android:paddingBottom="126dp"',
    'android:paddingTop="20dp"\n                android:paddingEnd="18dp"\n                android:paddingBottom="112dp"',
    "home page spacing",
)
replace_all(
    dashboard_layout,
    'android:paddingTop="14dp"\n                android:paddingEnd="18dp"\n                android:paddingBottom="126dp"',
    'android:paddingTop="20dp"\n                android:paddingEnd="18dp"\n                android:paddingBottom="112dp"',
    "secondary page spacing",
)
replace_once(
    dashboard_layout,
    'android:layout_height="64dp"\n                    android:layout_marginTop="13dp"',
    'android:layout_height="58dp"\n                    android:layout_marginTop="14dp"',
    "primary clean button height",
)
replace_all(
    dashboard_layout,
    'android:background="#16FFFFFF"',
    'android:background="?attr/colorOutlineVariant"',
    "tool row dividers",
)
replace_once(
    dashboard_layout,
    '''        android:layout_height="84dp"
        android:layout_gravity="bottom"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp" />''',
    '''        android:layout_height="76dp"
        android:layout_gravity="bottom"
        android:layout_marginStart="18dp"
        android:layout_marginEnd="18dp"
        android:layout_marginBottom="18dp" />''',
    "floating navigation dimensions",
)

replace_once(
    dashboard_activity,
    '        binding.versionText.text = "Alpha 15"\n',
    '        binding.versionText.text = "Alpha 16"\n',
    "dashboard activity version",
)

replace_once(
    build_gradle,
    '''        versionCode = 20040
        versionName = "2.0.0-alpha15"
''',
    '''        versionCode = 20050
        versionName = "2.0.0-alpha16"
''',
    "app version",
)

replace_once(
    module_prop,
    '''version=v2.0.0-alpha15
versionCode=20040
''',
    '''version=v2.0.0-alpha16
versionCode=20050
''',
    "module version",
)
replace_once(
    module_prop,
    'description=白泽 v2 Alpha 15：修复系统栏布局，新增严格根目录空壳与 Android/media 残留识别，重做高对比 MIUIx 界面，并支持可选 Monet、明暗模式和纯黑主题。\n',
    'description=白泽 v2 Alpha 16：按参考 App 重构简约 MIUIx 组件层，采用实心高对比卡片、大标题、克制单色强调和可选 Monet，仅底栏保留轻量玻璃。\n',
    "module description",
)
replace_once(
    customize,
    'ui_print "- 安装白泽 v2 Alpha 15"\n',
    'ui_print "- 安装白泽 v2 Alpha 16 UI 重构版"\n',
    "installer title",
)
replace_once(
    package_script,
    'OUTPUT="$OUT/BaiZe-v2-Alpha15-Module.zip"\n',
    'OUTPUT="$OUT/BaiZe-v2-Alpha16-Module.zip"\n',
    "package output",
)
replace_once(
    package_script,
    'echo "已生成 Alpha 15 根目录空壳清理、完整白名单、Monet MIUIx UI 与规则库一体化模块：$OUTPUT"\n',
    'echo "已生成 Alpha 16 简约 MIUIx 组件层、完整清理引擎、白名单、Monet 与规则库一体化模块：$OUTPUT"\n',
    "package message",
)
