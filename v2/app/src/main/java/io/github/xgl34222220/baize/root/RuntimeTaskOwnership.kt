package io.github.xgl34222220.baize.root

import java.io.File

/** State files describe a task; only a live process with the recorded identity owns it. */
internal object RuntimeTaskOwnership {
    private val markers = listOf(
        "task-worker.sh", "worker-runner.sh", "organizer-worker.sh", "cache-lane-worker.sh",
        "cleaner.sh", "cleaner.sh.compat", "native-cleaner.sh", "profile-cleaner.sh",
        "cache-snapshot-clean.sh", "cache-transaction.sh", "one-pass-scan.sh", "native-scan.sh",
        "apk-scanner.sh", "apk-cleaner.sh", "apk-snapshot-scan.sh", "apk-snapshot-clean.sh",
        "deep-scan-manifest.sh", "deep-manifest-clean.sh", "baize_engine", "baize_deep_snapshot"
    )

    fun isRunning(stateDir: File, procDir: File = File("/proc")): Boolean {
        val worker = RootFileStore.readEnv(File(stateDir, "worker.env"))
        if (matches(worker.optLong("pid"), worker.optLong("start_ticks"), procDir)) return true
        for (name in listOf("run.lock", "cache-lane.lock")) {
            val lock = File(stateDir, name)
            if (matches(number(File(lock, "pid")), number(File(lock, "start_ticks")), procDir)) return true
        }
        val launch = runCatching { File(stateDir, "task-launch.lock").readLines() }.getOrDefault(emptyList())
        return matches(launch.getOrNull(0)?.toLongOrNull() ?: 0L, launch.getOrNull(1)?.toLongOrNull() ?: 0L, procDir)
    }

    private fun number(file: File): Long = runCatching { file.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L)

    private fun matches(pid: Long, ticks: Long, procDir: File): Boolean {
        if (pid <= 1L) return false
        val process = File(procDir, pid.toString())
        val tail = runCatching {
            val raw = File(process, "stat").readText()
            raw.substring(raw.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
        }.getOrDefault(emptyList())
        if (tail.firstOrNull() in listOf(null, "Z", "X")) return false
        val actualTicks = tail.getOrNull(19)?.toLongOrNull() ?: return false
        if (ticks > 0L && actualTicks != ticks) return false
        val command = runCatching {
            File(process, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrDefault("")
        return markers.any(command::contains)
    }
}
