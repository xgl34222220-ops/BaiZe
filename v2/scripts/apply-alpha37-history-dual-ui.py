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

replace_once(
    app,
    "import io.github.xgl34222220.baize.ui.home.HomeRoute\n",
    "import io.github.xgl34222220.baize.ui.home.HomeRoute\n"
    "import io.github.xgl34222220.baize.ui.history.HistoryRoute\n",
)
replace_once(
    app,
    "BaiZePage.Records -> RecordsPage(state, actions)",
    "BaiZePage.Records -> HistoryRoute(UiStyle.MATERIAL, state, actions)",
)
replace_once(
    app,
    "BaiZePage.Records -> RecordsPage(state, actions)",
    "BaiZePage.Records -> HistoryRoute(UiStyle.MIUIX, state, actions)",
)
replace_once(app, "private fun RecordsPage(", "private fun LegacyRecordsPage(")

app_path = Path(app)
app_text = app_path.read_text(encoding="utf-8").replace("Alpha 36", "Alpha 37")
app_path.write_text(app_text, encoding="utf-8")

replace_once(build, "versionCode = 21600", "versionCode = 21700")
replace_once(build, 'versionName = "2.0.0-alpha36"', 'versionName = "2.0.0-alpha37"')
replace_once(module_prop, "version=v2.0.0-alpha36", "version=v2.0.0-alpha37")
replace_once(module_prop, "versionCode=21600", "versionCode=21700")
replace_once(package_script, "BaiZe-v2-Alpha36-Module.zip", "BaiZe-v2-Alpha37-Module.zip")
replace_once(
    package_script,
    "已生成 Alpha 36 清理类别双皮肤第一步模块",
    "已生成 Alpha 37 清理结果与历史双皮肤模块",
)
replace_once(
    customize,
    "安装白泽 v2 Alpha 36 清理类别双皮肤版",
    "安装白泽 v2 Alpha 37 清理结果与历史双皮肤版",
)
