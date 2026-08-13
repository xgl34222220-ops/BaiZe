package io.github.xgl34222220.baize.root

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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
            if (normalized in setOf("storage-analysis", "large-files", "duplicates")) ensureStorageIndex()
            val arg = JSONObject(optionsJson.orEmpty().ifBlank { "{}" }).optInt("value", 0)
            val command = mutableListOf("/system/bin/sh", file.absolutePath)
            if (normalized == "large-files" && arg > 0) command += arg.coerceIn(16, 16384).toString()
            val log = maintenanceLog(normalized)
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(MAINTENANCE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroy()
                if (!process.waitFor(2L, TimeUnit.SECONDS)) process.destroyForcibly()
                error("maintenance_timeout")
            }
            val output = RootFileStore.tailText(log, 16_000)
            log.delete()
            val code = process.exitValue()
            JSONObject().put("success", code == 0).put("exitCode", code).put("output", output)
                .put("tool", normalized)
                .put("items", if (code == 0) maintenanceItems(normalized) else JSONArray())
                .put("coverage", if (code == 0) scanCoverage() else JSONArray())
                .toString()
        }.getOrElse { error ->
            JSONObject().put("error", "maintenance_failed")
                .put("message", error.message ?: error.javaClass.simpleName)
                .toString()
        }
    }

    private fun ensureStorageIndex() {
        val indexer = File(moduleDir, "storage-index.sh")
        require(indexer.isFile) { "storage_index_missing" }
        val log = maintenanceLog("storage-index")
        val process = ProcessBuilder("/system/bin/sh", indexer.absolutePath, "ensure", "app-storage-tools")
            .redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(MAINTENANCE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroy()
            if (!process.waitFor(2L, TimeUnit.SECONDS)) process.destroyForcibly()
            error("storage_index_timeout")
        }
        val output = RootFileStore.tailText(log, 2_000)
        log.delete()
        require(process.exitValue() == 0) { output.ifBlank { "storage_index_failed" } }
    }

    private fun maintenanceLog(name: String): File =
        File(stateDir, "logs/maintenance-$name-${android.os.Process.myPid()}-${System.nanoTime()}.log")
            .apply { parentFile?.mkdirs() }

    private fun maintenanceItems(tool: String): JSONArray = when (tool) {
        "storage-analysis" -> parseStorageAnalysis(File(stateDir, "reports/storage-analysis.tsv"))
        "large-files" -> parseLargeFiles(File(stateDir, "reports/large-files.tsv"))
        "duplicates" -> parseDuplicates(File(stateDir, "reports/duplicates.tsv"))
        else -> JSONArray()
    }

    private fun parseStorageAnalysis(file: File): JSONArray = parseTsv(file, 3, 500) { columns ->
        JSONObject().put("group", columns[0]).put("files", columns[1].toLongOrNull() ?: 0L)
            .put("bytes", columns[2].toLongOrNull() ?: 0L)
    }

    private fun parseLargeFiles(file: File): JSONArray = parseTsv(file, 3, 1_000) { columns ->
        JSONObject().put("size", columns[0].toLongOrNull() ?: 0L)
            .put("mtime", columns[1].toLongOrNull() ?: 0L)
            .put("path", decodePath(columns[2]))
    }

    private fun parseDuplicates(file: File): JSONArray = parseTsv(file, 6, 2_000) { columns ->
        JSONObject().put("group", columns[0]).put("size", columns[1].toLongOrNull() ?: 0L)
            .put("sha256", columns[2]).put("keeper", decodePath(columns[3]))
            .put("duplicate", decodePath(columns[4])).put("reclaimable", columns[5].toLongOrNull() ?: 0L)
    }

    private fun parseTsv(file: File, fields: Int, limit: Int, mapper: (List<String>) -> JSONObject): JSONArray {
        val result = JSONArray()
        if (!file.isFile) return result
        file.useLines { lines ->
            lines.drop(1).take(limit).forEach { raw ->
                val columns = raw.split('\t')
                if (columns.size >= fields) result.put(mapper(columns))
            }
        }
        return result
    }

    private fun decodePath(encoded: String): String = runCatching {
        String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault("")

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

    private companion object {
        const val MAINTENANCE_TIMEOUT_MINUTES = 15L
    }
}
