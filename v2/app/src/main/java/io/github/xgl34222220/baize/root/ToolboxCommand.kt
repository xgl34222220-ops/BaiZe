package io.github.xgl34222220.baize.root

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Argument-vector commands only; output goes to a bounded-on-read file, never a blocking pipe. */
internal class ToolboxCommand(
    private val cancelled: AtomicBoolean,
    private val logDirectory: File = File(RootPaths.STATE_DIR, "toolbox/commands")
) {
    data class Result(val exit: Int, val output: String, val timedOut: Boolean, val cancelled: Boolean) {
        val success get() = exit == 0 && !timedOut && !cancelled
    }
    fun run(arguments: List<String>, seconds: Long = 20, honourCancel: Boolean = true, outputLimit: Int = 4000): Result {
        require(arguments.isNotEmpty() && arguments.none { it.contains('\u0000') })
        if (honourCancel && cancelled.get()) return Result(-1, "已停止", false, true)
        logDirectory.mkdirs()
        val log = File.createTempFile("command-", ".log", logDirectory)
        var process: Process? = null
        return try {
            process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            var stopped = false
            var timeout = false
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                stopped = honourCancel && cancelled.get()
                timeout = System.nanoTime() >= deadline || log.length() > 2 * 1024 * 1024
                if (stopped || timeout) {
                    process.destroy()
                    if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    break
                }
            }
            Result(if (process.isAlive) -1 else process.exitValue(), RootFileStore.tailText(log, outputLimit.coerceIn(1000, 64000)).trim(), timeout, stopped)
        } catch (error: Exception) {
            Result(-1, error.message ?: error.javaClass.simpleName, false, cancelled.get())
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            log.delete()
        }
    }
}
