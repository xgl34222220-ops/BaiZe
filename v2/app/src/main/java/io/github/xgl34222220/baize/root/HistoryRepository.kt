package io.github.xgl34222220.baize.root

import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class HistoryRepository(
    private val moduleDir: File = File(RootPaths.MODULE_DIR),
    private val stateDir: File = File(RootPaths.STATE_DIR)
) {
    fun taskHistoryJson(requestedLimit: Int): String {
        val limit = requestedLimit.coerceIn(1, 100)
        val historyFile = File(stateDir, "history.tsv")
        val entries = JSONArray()
        var totalReleased = 0L
        var cleanedRuns = 0
        val totals = RootFileStore.readEnv(File(stateDir, "totals.env"))

        val lines = runCatching {
            if (historyFile.isFile) historyFile.readLines().takeLast(limit).asReversed() else emptyList()
        }.getOrDefault(emptyList())

        lines.forEach { raw ->
            val columns = raw.split('\t', limit = 10)
            if (columns.size < 7) return@forEach
            val mode = columns[1].trim()
            val bytes = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val cleaned = mode != "scan" && !mode.endsWith("-scan")
            if (cleaned) {
                totalReleased += bytes
                cleanedRuns += 1
            }
            entries.put(
                JSONObject()
                    .put("time", columns[0].trim())
                    .put("mode", mode)
                    .put("bytes", bytes)
                    .put("files", columns[3].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("emptyDirs", columns[4].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("errors", columns[5].toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    .put("result", columns[6].trim())
                    .put("trigger", columns.getOrNull(7)?.trim().orEmpty())
                    .put("categoryDetails", parseHistoryCategoryDetails(columns.getOrNull(8).orEmpty()))
                    .put("appDetails", parseHistoryAppDetails(columns.getOrNull(9).orEmpty()))
                    .put("cleaned", cleaned)
            )
        }

        return JSONObject()
            .put("success", true)
            .put("count", entries.length())
            .put("cleanedRuns", cleanedRuns)
            .put("totalReleased", totalReleased)
            .put("lifetimeRuns", totals.optLong("runs", cleanedRuns.toLong()).coerceAtLeast(0L))
            .put("lifetimeReleased", totals.optLong("bytes", totalReleased).coerceAtLeast(0L))
            .put("lifetimeFiles", totals.optLong("regular_files", 0L).coerceAtLeast(0L))
            .put("lifetimeEmptyFiles", totals.optLong("empty_files", 0L).coerceAtLeast(0L))
            .put("lifetimeEmptyDirs", totals.optLong("empty_dirs", 0L).coerceAtLeast(0L))
            .put("lifetimeFragments", totals.optLong("fragment_files", 0L).coerceAtLeast(0L))
            .put("lifetimeElapsed", totals.optLong("elapsed", 0L).coerceAtLeast(0L))
            .put("entries", entries)
            .toString()
    }

    fun taskHistoryPageJson(offset: Int, requestedLimit: Int): String {
        val source = JSONObject(taskHistoryJson(100))
        val entries = source.optJSONArray("entries") ?: JSONArray()
        val safeOffset = offset.coerceIn(0, entries.length())
        val safeLimit = requestedLimit.coerceIn(1, 30)
        val end = (safeOffset + safeLimit).coerceAtMost(entries.length())
        val page = JSONArray()
        for (index in safeOffset until end) page.put(entries.optJSONObject(index))
        return source.put("entries", page)
            .put("offset", safeOffset)
            .put("nextOffset", end)
            .put("total", entries.length())
            .put("hasMore", end < entries.length())
            .put("count", page.length())
            .toString()
    }

    fun clearTaskHistoryJson(): String = runCatching {
        File(stateDir, "history.tsv").writeText("")
        File(stateDir, "latest.env").delete()
        File(stateDir, "reports/latest.tsv").delete()
        File(stateDir, "reports/apps-latest.tsv").delete()
        File(stateDir, "reports/app-items-latest.tsv").delete()
        JSONObject().put("success", true).toString()
    }.getOrElse { error ->
        JSONObject().put("success", false).put("error", error.message ?: error.javaClass.simpleName).toString()
    }

    fun recordNativeTaskJson(raw: String): String = runCatching {
        val input = JSONObject(raw)
        val mode = input.optString("mode").trim()
        require(mode in NATIVE_MODES) { "unsupported_native_mode" }
        val success = input.optBoolean("success", true)
        val cancelled = input.optBoolean("cancelled", false)
        val bytes = input.optLong("bytes", 0L).coerceIn(0L, Long.MAX_VALUE / 4)
        val files = input.optLong("files", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val emptyFiles = input.optLong("emptyFiles", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val emptyDirs = input.optLong("emptyDirs", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val fragments = input.optLong("fragments", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val errors = input.optLong("errors", 0L).coerceIn(0L, Int.MAX_VALUE.toLong())
        val elapsedSeconds = input.optLong("elapsedSeconds", 0L).coerceIn(0L, 24L * 60L * 60L)
        val result = input.optString("result", "原生智能清理完成")
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(500)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        stateDir.mkdirs()
        val history = File(stateDir, "history.tsv")
        val categorySummary = input.optString("categorySummary")
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(1000)
        val appSummary = input.optString("appSummary")
            .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(1000)
        history.appendText("$timestamp\t$mode\t$bytes\t$files\t$emptyDirs\t$errors\t$result\tapp-native\t$categorySummary\t$appSummary\n")
        val retained = history.readLines().takeLast(100)
        RootFileStore.writeAtomic(history, retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"))

        val actualMutations = bytes > 0L || files > 0L || emptyFiles > 0L || emptyDirs > 0L || fragments > 0L
        if (actualMutations || (success && !cancelled)) {
            val eventId = input.optString("eventId").trim().takeIf { EVENT_ID.matches(it) }
                ?: "native-${System.currentTimeMillis()}-${Process.myPid()}"
            val updated = commitCleanEvent(
                eventId, mode, bytes, files, emptyFiles, emptyDirs, fragments, elapsedSeconds, "app-native"
            )
            updateModuleDescription(updated)
        }

        RootFileStore.writeAtomic(File(stateDir, "latest.env"), buildString {
            append("mode=").append(mode).append('\n')
            append("time=").append(timestamp).append('\n')
            append("files=").append(files).append('\n')
            append("regular_files=").append(files).append('\n')
            append("empty_files=").append(emptyFiles).append('\n')
            append("empty_dirs=").append(emptyDirs).append('\n')
            append("fragment_files=").append(fragments).append('\n')
            append("bytes=").append(bytes).append('\n')
            append("errors=").append(errors).append('\n')
            append("elapsed=").append(elapsedSeconds).append('\n')
            append("result=").append(result).append('\n')
        })
        JSONObject().put("success", true).toString()
    }.getOrElse { error ->
        JSONObject().put("success", false).put("error", error.message ?: error.javaClass.simpleName).toString()
    }

    private fun parseHistoryCategoryDetails(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { token ->
            val columns = token.split('|', limit = 3)
            if (columns.size < 3) return@forEach
            val name = columns[0].trim().take(80)
            if (name.isBlank()) return@forEach
            result.put(JSONObject().put("name", name)
                .put("bytes", columns[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                .put("files", columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L))
        }
        return result
    }

    private fun parseHistoryAppDetails(raw: String): JSONArray {
        val result = JSONArray()
        raw.split(';').asSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { token ->
            val columns = token.split('|', limit = 4)
            if (columns.size < 3) return@forEach
            val packageName = columns[0].trim()
            if (!RootValidation.packageName.matches(packageName)) return@forEach
            result.put(JSONObject().put("packageName", packageName)
                .put("bytes", columns[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                .put("files", columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
                .put("category", columns.getOrNull(3)?.trim().orEmpty().take(80)))
        }
        return result
    }

    private fun updateModuleDescription(totals: Map<String, Long>) = runCatching {
        val moduleProp = File(moduleDir, "module.prop")
        if (!moduleProp.isFile) return@runCatching
        val description = buildString {
            append("description=累计清理 ").append(humanBytes(totals["bytes"] ?: 0L))
            append(" · 文件 ").append(totals["regular_files"] ?: 0L)
            append(" · 空文件 ").append(totals["empty_files"] ?: 0L)
            append(" · 空目录 ").append(totals["empty_dirs"] ?: 0L)
            append(" · 碎片 ").append(totals["fragment_files"] ?: 0L)
        }
        val lines = moduleProp.readLines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("description=") }
        if (index >= 0) lines[index] = description else lines += description
        RootFileStore.writeAtomic(moduleProp, lines.joinToString("\n", postfix = "\n"), worldReadable = true)
    }

    private fun commitCleanEvent(
        eventId: String,
        mode: String,
        bytes: Long,
        files: Long,
        emptyFiles: Long,
        emptyDirs: Long,
        fragments: Long,
        elapsed: Long,
        trigger: String
    ): Map<String, Long> {
        val recorder = File(moduleDir, "record-clean-event.sh")
        require(recorder.isFile) { "clean_event_recorder_missing" }
        val process = ProcessBuilder(
            "/system/bin/sh", recorder.path, eventId, mode, bytes.toString(), files.toString(),
            emptyFiles.toString(), emptyDirs.toString(), fragments.toString(), elapsed.toString(), trigger
        ).redirectErrorStream(true).start()
        val completed = process.waitFor(25L, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
            process.waitFor(2L, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            error("clean_event_recorder_timeout")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().take(1_000) }
        require(process.exitValue() == 0) { output.ifBlank { "clean_event_recorder_failed" } }
        val totals = RootFileStore.readEnv(File(stateDir, "totals.env"))
        return TOTAL_KEYS.associateWith { totals.optLong(it, 0L).coerceAtLeast(0L) }
    }

    private fun humanBytes(value: Long): String {
        val bytes = value.coerceAtLeast(0L).toDouble()
        return when {
            bytes >= 1_073_741_824.0 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576.0 -> String.format(Locale.US, "%.2f MB", bytes / 1_048_576.0)
            bytes >= 1024.0 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
            else -> "${bytes.toLong()} B"
        }
    }

    companion object {
        private val EVENT_ID = Regex("[A-Za-z0-9._:-]{1,160}")
        private val TOTAL_KEYS = listOf(
            "runs", "regular_files", "empty_files", "empty_dirs", "hidden_items", "fragment_files", "bytes", "elapsed"
        )
        private val NATIVE_MODES = setOf(
            "smart-clean",
            "snapshot-clean",
            "workbench-clean",
            "profile-clean",
            "instant-cache",
            "file-organizer-apply",
            "file-organizer-undo"
        )
    }
}
