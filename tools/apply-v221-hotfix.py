from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]

def rep(path, old, new):
    p = root / path
    text = p.read_text()
    if old in text:
        p.write_text(text.replace(old, new, 1))
    elif new not in text:
        raise SystemExit(f"missing anchor: {path}")

history = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/miuix/HistoryScreenMiuix.kt"
rep(history, "import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\n", "import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\nimport io.github.xgl34222220.baize.ui.common.AppPackageIcon\n")
rep(history, "icon = Icons.Rounded.Apps,\n                title = item.label,", "icon = Icons.Rounded.Apps,\n                packageName = item.packageName,\n                title = item.label,")
rep(history, "private fun ResultHeader(\n    icon: ImageVector,\n    title: String,", "private fun ResultHeader(\n    icon: ImageVector,\n    packageName: String? = null,\n    title: String,")
rep(history, "Row(verticalAlignment = Alignment.CenterVertically) {\n        IconTile(icon, error)\n        Spacer(Modifier.width(12.dp))", "Row(verticalAlignment = Alignment.CenterVertically) {\n        if (packageName.isNullOrBlank()) IconTile(icon, error)\n        else AppPackageIcon(packageName, title, size = 50.dp, corner = 15.dp)\n        Spacer(Modifier.width(12.dp))")

activity = root / "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
text = activity.read_text()
start = text.index("            val successfulScan = (cacheOk || safeOk) && !cancelled")
end = text.index("            if (!cleanAfterScan) notifyScanResult", start)
old = text[start:end]
new = '''            val successfulScan = (cacheOk || safeOk) && !cancelled
            if (successfulScan && total > 0) snapshotExpiresAtElapsed = SystemClock.elapsedRealtime() + SNAPSHOT_TTL_MS
            else clearSnapshotHandles()
            val scanResultLine = when {
                cancelled -> "安全扫描已停止"
                !successfulScan -> "安全扫描失败：${safeJson.optString("message", cacheJson.optString("message", "引擎没有返回有效快照"))}"
                total == 0 -> "扫描完成，没有发现可安全清理的项目"
                else -> "扫描完成，发现 $total 项；快照 30 分钟内有效"
            }
            dashboardState.value = dashboardState.value.copy(
                running = false, scanCompleted = successfulScan, scanBytes = knownBytes,
                scanFiles = total.toLong(), scanEmptyFiles = emptyFiles, scanEmptyDirs = emptyDirs,
                scanFragments = fragments, scanErrors = failures, scanElapsed = elapsed / 1000L,
                taskPhase = scanResultLine
            )
            val scanCategories = buildList {
                if (total > 0) add("安全扫描|$knownBytes|$total")
                if (emptyFiles > 0) add("空文件|0|$emptyFiles")
                if (emptyDirs > 0) add("空目录|0|$emptyDirs")
                if (fragments > 0) add("残留碎片|0|$fragments")
            }.joinToString(";")
            withContext(Dispatchers.IO) {
                runCatching {
                    profiles.recordNativeTask(JSONObject()
                        .put("mode", "native-scan").put("success", successfulScan)
                        .put("cancelled", cancelled).put("bytes", knownBytes).put("files", total)
                        .put("emptyFiles", emptyFiles).put("emptyDirs", emptyDirs)
                        .put("fragments", fragments).put("errors", failures)
                        .put("elapsedSeconds", elapsed / 1000L).put("result", scanResultLine)
                        .put("categorySummary", scanCategories).toString())
                }
            }
            refreshHistory()
            refreshRawLog()
            refreshModuleState()
'''
activity.write_text(text[:start] + new + text[end:])
rep(str(activity.relative_to(root)), '        "scan" -> "垃圾扫描"\n', '        "scan" -> "垃圾扫描"\n        "native-scan" -> "安全扫描"\n')

service = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
rep(service, 'require(mode in setOf("smart-clean", "snapshot-clean")) { "unsupported_native_mode" }', 'require(mode in setOf("native-scan", "smart-clean", "snapshot-clean")) { "unsupported_native_mode" }\n        val cleaned = mode != "native-scan" && !mode.endsWith("-scan")')
rep(service, '        if (success && !cancelled) {\n', '''        val logDir = File(stateDir, "logs").apply { mkdirs() }
        val logFile = File(logDir, "app-$mode-${System.currentTimeMillis()}.log")
        logFile.writeText(buildString {
            append("白泽原生任务日志\\n时间：").append(timestamp).append('\\n')
            append("任务：").append(mode).append("\\n结果：").append(result).append('\\n')
            append("状态：").append(if (cancelled) "已停止" else if (success) "成功" else "失败").append('\\n')
            append("项目：").append(files).append(" · 大小：").append(humanBytes(bytes)).append('\\n')
            append("空文件：").append(emptyFiles).append(" · 空目录：").append(emptyDirs).append('\\n')
            append("碎片：").append(fragments).append(" · 异常：").append(errors).append('\\n')
            append("耗时：").append(elapsedSeconds).append(" 秒\\n")
            if (categorySummary.isNotBlank()) append("分类：").append(categorySummary.replace(';', '，')).append('\\n')
        })
        logFile.setReadable(true, true)

        if (cleaned && success && !cancelled) {
''')

rep("v2/app/build.gradle.kts", 'versionCode = 22600\n        versionName = "2.2.0"', 'versionCode = 22601\n        versionName = "2.2.1"')
(root / "v2/module/module.prop").write_text("id=baize_v2\nname=白泽 v2\nversion=v2.2.1\nversionCode=22601\nauthor=惜故里丶\ndescription=白泽 v2.2.1：修复安全扫描日志与记录页真实应用图标。\n")
for path in ["v2/scripts/package-module.sh", ".github/workflows/v2.2.0-core-rework.yml"]:
    p = root / path
    value = p.read_text().replace("v2.2.0", "v2.2.1").replace("22600", "22601")
    value = value.replace("feat/v2.2.0-core-rework", "fix/v2.2.1-logs-icons")
    value = value.replace("RELEASE_NOTES_V2.2.0.md", "RELEASE_NOTES_V2.2.1.md")
    p.write_text(value)
rep("v2/module/customize.sh", "安装白泽 v2.1.0 Alpha 4 有限并发预览版", "安装白泽 v2.2.1 日志与应用图标修复测试版")
(root / "RELEASE_NOTES_V2.2.1.md").write_text("# 白泽 v2.2.1\n\n- 安全扫描写入运行日志和任务历史。\n- 记录页应用垃圾卡片显示真实应用图标。\n- 使用兼容 Android 16/OEM 主题图标的 Canvas 渲染与缓存。\n")
print("v2.2.1 hotfix applied")
