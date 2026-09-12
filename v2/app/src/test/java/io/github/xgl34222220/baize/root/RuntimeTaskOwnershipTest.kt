package io.github.xgl34222220.baize.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeTaskOwnershipTest {
    @get:Rule val folder = TemporaryFolder()
    private fun process(proc: File, command: String, ticks: Long = 500L, state: String = "S") {
        val dir = File(proc, "123").apply { mkdirs() }
        File(dir, "cmdline").writeText("/system/bin/sh\u0000/data/adb/modules/baize_v2/$command\u0000")
        val fields = MutableList(20) { "0" }.apply { this[0] = state; this[19] = ticks.toString() }
        File(dir, "stat").writeText("123 (worker (test)) ${fields.joinToString(" ")}")
    }
    @Test fun `APK and deep manifest owners survive repair`() {
        val state = folder.newFolder("state")
        val proc = folder.newFolder("proc")
        File(state, "run.lock").mkdir()
        File(state, "run.lock/pid").writeText("123\n")
        File(state, "run.lock/start_ticks").writeText("500\n")
        for (name in listOf("apk-scanner.sh", "apk-cleaner.sh", "deep-scan-manifest.sh", "deep-manifest-clean.sh")) {
            process(proc, name)
            assertTrue(RuntimeTaskOwnership.isRunning(state, proc))
        }
    }
    @Test fun `recycled PID zombie and stale progress never claim ownership`() {
        val state = folder.newFolder("state")
        val proc = folder.newFolder("proc")
        File(state, "running.env").writeText("mode=clean\n")
        File(state, "worker.env").writeText("pid=123\nstart_ticks=500\n")
        assertFalse(RuntimeTaskOwnership.isRunning(state, proc))
        process(proc, "worker-runner.sh", ticks = 600L)
        assertFalse(RuntimeTaskOwnership.isRunning(state, proc))
        process(proc, "worker-runner.sh", state = "Z")
        assertFalse(RuntimeTaskOwnership.isRunning(state, proc))
        process(proc, "unrelated.sh")
        assertFalse(RuntimeTaskOwnership.isRunning(state, proc))
    }
    @Test fun `worker between scan and clean plus startup handshake remain owned`() {
        val state = folder.newFolder("state")
        val proc = folder.newFolder("proc")
        process(proc, "worker-runner.sh")
        File(state, "worker.env").writeText("pid=123\nstart_ticks=500\n")
        assertTrue(RuntimeTaskOwnership.isRunning(state, proc))
        File(state, "worker.env").delete()
        process(proc, "task-worker.sh")
        File(state, "task-launch.lock").writeText("123\n500\n")
        assertTrue(RuntimeTaskOwnership.isRunning(state, proc))
    }
}
