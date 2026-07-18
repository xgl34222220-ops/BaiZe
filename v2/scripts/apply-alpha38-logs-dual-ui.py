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
build = "v2/app/build.gradle.kts"
module_prop = "v2/module/module.prop"
package_script = "v2/scripts/package-module.sh"
customize = "v2/module/customize.sh"
miuix_components = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/miuix/MiuixLiquidComponents.kt"

replace_once(
    app,
    "import androidx.compose.material.icons.rounded.DeleteSweep\n",
    "import androidx.compose.material.icons.rounded.DeleteSweep\nimport androidx.compose.material.icons.rounded.Description\n",
)
replace_once(
    app,
    "import io.github.xgl34222220.baize.ui.history.HistoryRoute\n",
    "import io.github.xgl34222220.baize.ui.history.HistoryRoute\nimport io.github.xgl34222220.baize.ui.logs.LogsRoute\n",
)
replace_once(
    app,
    '    Records("记录", Icons.Rounded.History),\n    Settings("设置", Icons.Rounded.Settings)\n',
    '    Records("记录", Icons.Rounded.History),\n    Logs("日志", Icons.Rounded.Description),\n    Settings("设置", Icons.Rounded.Settings)\n',
)
replace_once(
    app,
    "                        BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state, actions)\n                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)\n",
    "                        BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state, actions)\n                        BaiZePage.Logs -> LogsRoute(UiStyle.MATERIAL, state, actions)\n                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)\n",
)
replace_once(
    app,
    "                        BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state, actions)\n                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)\n",
    "                        BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state, actions)\n                        BaiZePage.Logs -> LogsRoute(UiStyle.MIUIX, state, actions)\n                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)\n",
)

app_path = Path(app)
app_text = app_path.read_text(encoding="utf-8").replace("Alpha 37", "Alpha 38")
app_path.write_text(app_text, encoding="utf-8")

replace_once(build, "versionCode = 21700", "versionCode = 21800")
replace_once(build, 'versionName = "2.0.0-alpha37"', 'versionName = "2.0.0-alpha38"')
replace_once(module_prop, "version=v2.0.0-alpha37", "version=v2.0.0-alpha38")
replace_once(module_prop, "versionCode=21700", "versionCode=21800")
replace_once(package_script, "BaiZe-v2-Alpha37-Module.zip", "BaiZe-v2-Alpha38-Module.zip")
replace_once(
    package_script,
    "已生成 Alpha 37 清理结果与历史双皮肤模块",
    "已生成 Alpha 38 日志双皮肤模块",
)
replace_once(
    customize,
    "安装白泽 v2 Alpha 37 清理结果与历史双皮肤版",
    "安装白泽 v2 Alpha 38 日志双皮肤版",
)

replace_once(
    miuix_components,
    "        val itemWidth = maxWidth / items.size.toFloat()\n        val targetIndex = selectedIndex.coerceIn(items.indices)\n",
    "        val itemWidth = maxWidth / items.size.toFloat()\n        val compact = items.size > 4\n        val targetIndex = selectedIndex.coerceIn(items.indices)\n",
)
replace_once(
    miuix_components,
    "                        modifier = Modifier.size(if (active) 23.dp else 21.dp),\n",
    "                        modifier = Modifier.size(if (compact) 20.dp else if (active) 23.dp else 21.dp),\n",
)
replace_once(
    miuix_components,
    "                        fontSize = 10.sp,\n                        lineHeight = 12.sp,\n",
    "                        fontSize = if (compact) 9.sp else 10.sp,\n                        lineHeight = if (compact) 11.sp else 12.sp,\n",
)
