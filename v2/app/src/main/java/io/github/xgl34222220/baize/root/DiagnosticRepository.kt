package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class DiagnosticRepository(
    private val moduleDir: File = File(RootPaths.MODULE_DIR),
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    fun runMaintenanceTool(tool: String?, optionsJson: String?): String {
        val normalized = tool.orEmpty().trim()
        val script = when (normalized) {
            "storage-analysis" -> "storage-analyzer.sh"
            "large-files" -> "large-file-scanner.sh"
            "duplicates" -> "duplicate-scanner.sh"
            "diagnostics" -> "diagnostics-export.sh"
            "rules-validate" -> "rules-validator.sh"
            else -> return JSONObject().put("success", false).put("error", "unsupported_tool").toString()
        }
        return runCatching {
            val file = File(moduleDir, script)
            require(file.isFile) { "tool_missing" }
            val arg = JSONObject(optionsJson.orEmpty().ifBlank { "{}" }).optInt("value", 0)
            val command = mutableListOf("/system/bin/sh", file.absolutePath)
            if (normalized == "large-files" && arg > 0) command += arg.coerceIn(16, 16384).toString()
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().takeLast(16_000) }
            val code = process.waitFor()
            JSONObject().put("success", code == 0).put("exitCode", code).put("output", output).toString()
        }.getOrElse { error ->
            JSONObject().put("error", "maintenance_failed")
                .put("message", error.message ?: error.javaClass.simpleName)
                .toString()
        }
    }

    fun scanCoverageJson(): String = scanCoverage().toString()

    fun scanCoverage(): JSONArray {
        val result = JSONArray()
        val file = File(stateDir, "index/coverage.tsv")
        if (!file.isFile) return result
        file.useLines { lines ->
            lines.drop(1).take(300).forEach { raw ->
                val columns = raw.split('\t', limit = 8)
                if (columns.size < 5) return@forEach
                result.put(
                    JSONObject()
                        .put("status", columns[0])
                        .put("group", columns[1])
                        .put("user", columns.getOrNull(2).orEmpty())
                        .put("volume", columns.getOrNull(3).orEmpty())
                        .put("files", columns.getOrNull(4)?.toLongOrNull() ?: 0L)
                        .put("bytes", columns.getOrNull(5)?.toLongOrNull() ?: 0L)
                        .put("path", columns.getOrNull(6).orEmpty())
                        .put("reason", columns.getOrNull(7).orEmpty())
                )
            }
        }
        return result
    }

    fun rawLogJson(maxChars: Int): String {
        val safeLimit = maxChars.coerceIn(2_000, 64_000)
        val logDir = File(stateDir, "logs")
        val latest = logDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
        if (latest == null) {
            return JSONObject().put("success", true).put("name", "").put("text", "").toString()
        }
        return JSONObject()
            .put("success", true)
            .put("name", latest.name)
            .put("modified", latest.lastModified())
            .put("text", RootFileStore.tailText(latest, safeLimit))
            .toString()
    }

    fun clearRawLogsJson(): String {
        val logDir = File(stateDir, "logs")
        var removed = 0
        var failed = 0
        logDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.forEach { file ->
                if (runCatching { file.delete() }.getOrDefault(false)) removed += 1 else failed += 1
            }
        return JSONObject()
            .put("success", failed == 0)
            .put("removed", removed)
            .put("failed", failed)
            .toString()
    }

    fun resetScanWorkerProfileJson(): String = runCatching {
        val profile = File(stateDir, "root-worker-profile.env")
        val deleted = !profile.exists() || profile.delete()
        if (!deleted) error("无法删除本机性能基准")
        JSONObject()
            .put("success", true)
            .put("message", "性能基准已清除，下次自动扫描将从串行重新学习")
            .toString()
    }.getOrElse { error ->
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: error.javaClass.simpleName)
            .toString()
    }
}
