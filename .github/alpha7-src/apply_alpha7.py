from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"missing marker in {path}: {old[:80]!r}")
    write(path, text.replace(old, new, 1))


# Root-service contract and implementation.
replace_once(
    "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl",
    "    String resetScanWorkerProfile();\n\n    String getInstalledPackageCatalog();",
    "    String resetScanWorkerProfile();\n    String clearPackageCaches(String requestJson);\n\n    String getInstalledPackageCatalog();",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    " * Persistent root service for the Alpha 6 automatic module path and advanced native audits.",
    " * Persistent root service for the Alpha 7 automatic module path, audits and explicit cache-only requests.",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    "    private val engine by lazy { NativeProfileEngine(this, cancelled) }\n",
    "    private val engine by lazy { NativeProfileEngine(this, cancelled) }\n" \
    "    private val instantCacheEngine by lazy {\n" \
    "        InstantCacheEngine(cancelled) { taskStateJson = it.toString() }\n" \
    "    }\n",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt",
    "        override fun resetScanWorkerProfile(): String = resetScanWorkerProfileJson()\n\n" \
    "        override fun getInstalledPackageCatalog(): String =",
    "        override fun resetScanWorkerProfile(): String = resetScanWorkerProfileJson()\n\n" \
    "        override fun clearPackageCaches(requestJson: String?): String {\n" \
    "            if (!taskRunning.compareAndSet(false, true)) return busy(\"instant-cache\")\n" \
    "            cancelled.set(false)\n" \
    "            val started = SystemClock.elapsedRealtime()\n" \
    "            return try {\n" \
    "                instantCacheEngine.run(requestJson.orEmpty(), started)\n" \
    "            } catch (error: Throwable) {\n" \
    "                failure(\"instant_cache_failed\", error)\n" \
    "            } finally {\n" \
    "                taskRunning.set(false)\n" \
    "                taskStateJson = idleState()\n" \
    "            }\n" \
    "        }\n\n" \
    "        override fun getInstalledPackageCatalog(): String =",
)

# Native App route and actions.
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanContract.kt",
    "    val onApkScan: () -> Unit,\n    val onDeepClean: () -> Unit,",
    "    val onApkScan: () -> Unit,\n    val onInstantCache: () -> Unit,\n    val onDeepClean: () -> Unit,",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt",
    "import io.github.xgl34222220.baize.DashboardUiState\n",
    "import io.github.xgl34222220.baize.DashboardUiState\nimport io.github.xgl34222220.baize.InstantCacheActivity\n",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/CleanRoute.kt",
    "        onApkScan = { context.startActivity(Intent(context, ApkScanActivity::class.java)) },\n" \
    "        onDeepClean = dashboardActions.deep,",
    "        onApkScan = { context.startActivity(Intent(context, ApkScanActivity::class.java)) },\n" \
    "        onInstantCache = { context.startActivity(Intent(context, InstantCacheActivity::class.java)) },\n" \
    "        onDeepClean = dashboardActions.deep,",
)
for path, ctor in [
    ("v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt", "MiuixQuickAction"),
    ("v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/material/CleanScreenMaterial.kt", "MaterialQuickAction"),
]:
    replace_once(path, "import androidx.compose.material.icons.rounded.AutoAwesome\n", "import androidx.compose.material.icons.rounded.AutoAwesome\nimport androidx.compose.material.icons.rounded.Bolt\n")
    old = f'        {ctor}(Icons.Rounded.InstallMobile, "安装包扫描",'
    text = read(path)
    index = text.find(old)
    if index < 0:
        raise SystemExit(f"missing quick action marker in {path}")
    line_end = text.find("\n", index)
    insert = (
        f'        {ctor}(Icons.Rounded.Bolt, "系统即时清缓存", '
        f'"手动选择应用，直接调用系统 cache-only", actions.onInstantCache),\n'
    )
    write(path, text[: line_end + 1] + insert + text[line_end + 1 :])

replace_once(
    "v2/app/src/main/AndroidManifest.xml",
    '        <activity android:name=".ApkScanActivity" android:exported="false" />\n',
    '        <activity android:name=".ApkScanActivity" android:exported="false" />\n' \
    '        <activity android:name=".InstantCacheActivity" android:exported="false" />\n',
)

# Version consistency.
for path in [
    "v2/app/build.gradle.kts",
    "v2/module/module.prop",
    "v2/scripts/package-module.sh",
]:
    text = read(path).replace("2.1.0-alpha6", "2.1.0-alpha7").replace("22406", "22407")
    write(path, text)
for path in [
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeRootService.kt",
    "v2/module/one-pass-scan.sh",
    "v2/module/service.sh",
]:
    text = read(path).replace("43.5-alpha6-performance-panel", "43.6-alpha7-system-cache")
    write(path, text)

module_prop = read("v2/module/module.prop")
module_prop = module_prop.replace(
    "description=白泽 v2.1.0 Alpha 6：原生 App 展示并控制本机扫描策略，可重置基准重新学习。",
    "description=白泽 v2.1.0 Alpha 7：新增与精准快照分离的手动系统即时清缓存工具。",
)
write("v2/module/module.prop", module_prop)

plan = """# 白泽 v2.1.0 Alpha 7\n\n## 系统即时清缓存\n\n- 独立手动入口，不进入后台自动清理。\n- 用户明确选择应用并二次确认后，调用 PackageManager `clear --cache-only`。\n- 默认显示用户应用；系统应用需要主动切换。\n- 单次最多 30 个应用，并阻止 Android、SystemUI 和白泽自身。\n- 不清除账号、设置和应用数据。\n- 不创建、消费或修改白泽精准扫描快照。\n- 系统不支持 `--cache-only` 时直接拒绝，绝不回退为 `pm clear`。\n"""
write("v2/V2.1-ALPHA7-PLAN.md", plan)

release = read("RELEASE_NOTES_V2.md")
header = """# BaiZe v2.1.0 Alpha 7\n\n- 新增原生“系统即时清缓存”手动页面，支持搜索、用户/系统应用筛选和最多 30 个应用多选。\n- 二次确认后使用 Android PackageManager `clear --cache-only`，与白泽精准快照完全分离。\n- 增加逐包超时、停止、核心包保护和系统能力检测；不支持时不执行任何回退清理。\n- App / 模块版本：2.1.0-alpha7 / 22407。\n\n"""
if not release.startswith("# BaiZe v2.1.0 Alpha 7"):
    write("RELEASE_NOTES_V2.md", header + release)
