#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found: {target}\n--- expected ---\n{old}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(
    app,
    "import io.github.xgl34222220.baize.ui.appearance.UiStyle\nimport io.github.xgl34222220.baize.ui.home.HomeRoute\n",
    "import io.github.xgl34222220.baize.ui.appearance.UiStyle\nimport io.github.xgl34222220.baize.ui.clean.CleanRoute\nimport io.github.xgl34222220.baize.ui.home.HomeRoute\n",
)
replace_once(
    app,
    '''private enum class BaiZePage(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    Plan("计划", Icons.Rounded.CalendarMonth),
    Records("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings)
}''',
    '''private enum class BaiZePage(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    Clean("清理", Icons.Rounded.CleaningServices),
    Records("记录", Icons.Rounded.History),
    Settings("设置", Icons.Rounded.Settings)
}''',
)
replace_once(
    app,
    "BaiZePage.Plan -> PlanPage(scheduler, actions)",
    "BaiZePage.Clean -> CleanRoute(UiStyle.MATERIAL, state, scheduler, actions)",
)
replace_once(
    app,
    "BaiZePage.Plan -> PlanPage(scheduler, actions)",
    "BaiZePage.Clean -> CleanRoute(UiStyle.MIUIX, state, scheduler, actions)",
)

miuix = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt"
replace_once(
    miuix,
    "import androidx.compose.foundation.layout.Column\n",
    "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.ColumnScope\n",
)
replace_once(
    miuix,
    "content: @Composable Column.() -> Unit",
    "content: @Composable ColumnScope.() -> Unit",
)

replace_once(
    "v2/app/build.gradle.kts",
    'versionCode = 21400\n        versionName = "2.0.0-alpha34"',
    'versionCode = 21500\n        versionName = "2.0.0-alpha35"',
)
replace_once(
    "v2/module/module.prop",
    "version=v2.0.0-alpha34\nversionCode=21400",
    "version=v2.0.0-alpha35\nversionCode=21500",
)
replace_once(
    "v2/scripts/package-module.sh",
    'OUTPUT="$OUT/BaiZe-v2-Alpha34-Module.zip"',
    'OUTPUT="$OUT/BaiZe-v2-Alpha35-Module.zip"',
)
replace_once(
    "v2/scripts/package-module.sh",
    'echo "已生成 Alpha 34 APK 安装包扫描、纯黑主题与精简结果卡片模块：$OUTPUT"',
    'echo "已生成 Alpha 35 清理类别双皮肤第一步模块：$OUTPUT"',
)
replace_once(
    "v2/module/customize.sh",
    'ui_print "- 安装白泽 v2 Alpha 34 安装包扫描与纯黑主题版"',
    'ui_print "- 安装白泽 v2 Alpha 35 清理类别双皮肤版"',
)

changes = Path("v2/ALPHA35-CLEAN-DUAL-UI-STEP1.md")
if not changes.exists():
    changes.write_text(
        """# 白泽 v2 Alpha 35：清理类别双皮肤第一步

- 新增共享 `CleanUiState`、`CleanUiActions` 和清理类别映射。
- 第二个底栏入口由“计划”改为“清理”。
- Material 使用独立的 Material 3 清理类别页。
- Miuix 使用独立的紧凑分组、SuperSwitch 清理类别页。
- 自动清理类别、周期、APK 设置继续复用原 `SchedulerUiState`。
- 垃圾扫描、安装包扫描、深度清理、卸载残留和明细继续复用原业务动作。
- RootService、cleaner.sh、历史记录和扫描状态机未修改。
""",
        encoding="utf-8",
    )
