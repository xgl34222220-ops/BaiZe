package io.github.xgl34222220.baize.root

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** The platform content tool acquires an external provider, without an IApplicationThread. */
internal object RootMediaScanCommand {
    private const val COMMAND_TIMEOUT_SECONDS = 15L

    internal fun arguments(path: String): List<String> {
        val user = Regex("^/(?:storage/emulated|data/media)/(\\d+)(?:/|$)")
            .find(path)?.groupValues?.get(1) ?: "0"
        return listOf("/system/bin/content", "call", "--user", user,
            "--uri", "content://media", "--method", "scan_file", "--arg", path)
    }

    // content catches provider exceptions and can still exit 0. Require its actual reply too.
    internal fun succeeded(code: Int, output: String): Boolean = code == 0 &&
        output.lineSequence().any { it.startsWith("Result: Bundle[") && it.contains("android.intent.extra.STREAM=") } &&
        !output.contains("Error while accessing provider:")

    fun scan(path: String, stateDir: File): Boolean {
        val output = File.createTempFile("media-command-", ".log", stateDir)
        var process: Process? = null
        return try {
            process = ProcessBuilder(arguments(path)).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w("BaiZeMedia", "Media refresh command timed out; queue retained")
                false
            } else {
                val response = output.inputStream().bufferedReader().use { it.readText().take(16_000) }
                succeeded(process.exitValue(), response).also {
                    if (!it) Log.w("BaiZeMedia", "Media refresh command failed: ${response.take(2_000)}")
                }
            }
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            output.delete()
        }
    }
}
