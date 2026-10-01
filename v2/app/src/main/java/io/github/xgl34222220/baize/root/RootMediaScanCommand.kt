package io.github.xgl34222220.baize.root

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** The platform content tool acquires an external provider, without an IApplicationThread. */
internal object RootMediaScanCommand {
    private const val COMMAND_TIMEOUT_SECONDS = 15L

    internal fun arguments(path: String): List<String> {
        require(path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == "." || it == ".." })
        // MediaProvider associates /storage paths with external volumes. A raw /data/media
        // scan can return STREAM=null successfully while leaving the public index untouched.
        // Preserve every character of the file suffix; never canonicalize back into the raw view.
        val raw = Regex("^/data/media/([0-9]+)(/.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(path)
        val removable = Regex("^/mnt/media_rw/([A-Za-z0-9-]+)(/.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(path)
        val publicPath = when {
            raw != null -> "/storage/emulated/${raw.groupValues[1]}${raw.groupValues[2]}"
            removable != null -> "/storage/${removable.groupValues[1]}${removable.groupValues[2]}"
            else -> path
        }
        val user = Regex("^/storage/emulated/([0-9]+)(?:/|$)")
            .find(publicPath)?.groupValues?.get(1) ?: "0"
        return listOf("/system/bin/content", "call", "--user", user,
            "--uri", "content://media", "--method", "scan_file", "--arg", publicPath)
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
