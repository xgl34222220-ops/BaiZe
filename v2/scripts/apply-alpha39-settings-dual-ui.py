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
    "import io.github.xgl34222220.baize.ui.logs.LogsRoute\n",
    "import io.github.xgl34222220.baize.ui.logs.LogsRoute\nimport io.github.xgl34222220.baize.ui.settings.SettingsRoute\n",
)
replace_once(
    app,
    "                        BaiZePage.Logs -> LogsRoute(UiStyle.MATERIAL, state, actions)\n                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)\n",
    "                        BaiZePage.Logs -> LogsRoute(UiStyle.MATERIAL, state, actions)\n                        BaiZePage.Settings -> SettingsRoute(UiStyle.MATERIAL, state, scheduler, appearance, actions)\n",
)
replace_once(
    app,
    "                        BaiZePage.Logs -> LogsRoute(UiStyle.MIUIX, state, actions)\n                        BaiZePage.Settings -> SettingsPage(state, scheduler, actions)\n",
    "                        BaiZePage.Logs -> LogsRoute(UiStyle.MIUIX, state, actions)\n                        BaiZePage.Settings -> SettingsRoute(UiStyle.MIUIX, state, scheduler, appearance, actions)\n",
)

app_path = Path(app)
app_text = app_path.read_text(encoding="utf-8").replace("Alpha 38", "Alpha 39")
app_path.write_text(app_text, encoding="utf-8")

replace_once(build, "versionCode = 21800", "versionCode = 21900")
replace_once(build, 'versionName = "2.0.0-alpha38"', 'versionName = "2.0.0-alpha39"')
replace_once(module_prop, "version=v2.0.0-alpha38", "version=v2.0.0-alpha39")
replace_once(module_prop, "versionCode=21800", "versionCode=21900")
replace_once(package_script, "BaiZe-v2-Alpha38-Module.zip", "BaiZe-v2-Alpha39-Module.zip")
replace_once(
    package_script,
    "已生成 Alpha 38 日志双皮肤模块",
    "已生成 Alpha 39 设置双皮肤模块",
)
replace_once(
    customize,
    "安装白泽 v2 Alpha 38 日志双皮肤版",
    "安装白泽 v2 Alpha 39 设置双皮肤版",
)
