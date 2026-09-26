package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean

class ToolboxCommandTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun drainsOutputBeyondPipeCapacityAndReturnsActualExit() {
        val command = ToolboxCommand(AtomicBoolean(), folder.newFolder())
        val result = command.run(listOf("/bin/sh", "-c", "head -c 131072 /dev/zero; echo done; exit 7"), 5)
        assertEquals(7, result.exit)
        assertTrue(result.output.endsWith("done"))
        assertFalse(result.timedOut)
    }
    @Test fun boundsHungCommandsAndDeletesTemporaryLogs() {
        val logs = folder.newFolder()
        val command = ToolboxCommand(AtomicBoolean(), logs)
        val started = System.nanoTime()
        val result = command.run(listOf("/bin/sh", "-c", "exec sleep 30"), 1)
        assertTrue(result.timedOut)
        assertFalse(result.success)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 7000)
        assertTrue(logs.listFiles()!!.isEmpty())
    }
    @Test fun cancellationBeforeStartCannotExecuteCommand() {
        val result = ToolboxCommand(AtomicBoolean(true), folder.newFolder()).run(listOf("/bin/sh", "-c", "exit 0"))
        assertTrue(result.cancelled)
        assertFalse(result.success)
    }
}
