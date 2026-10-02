package io.github.xgl34222220.baize.root

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SchedulerRunLedgerTest {
    @Test fun ledgerDistinguishesBlockedStoppedAndServiceFailuresWithoutClaimingDeletion() {
        val root = Files.createTempDirectory("baize-ledger-test").toFile()
        try {
            val ledger = File(root, "auto-run-ledger").apply { mkdirs() }
            File(ledger, "1.env").writeText("epoch=100\nstate=waiting\ngroup=apk\nreason=等待息屏\nnext_check_epoch=200\n")
            val results = File(root, "task-results").apply { mkdirs() }
            listOf(0, 3, 7, 9).forEach { code -> File(results, "$code.env").writeText("ended=${101+code}\nmode=apk-auto\ntrigger=scheduler:interval\nexit_code=$code\n") }
            File(results, "manual.env").writeText("ended=999\ntrigger=app\nexit_code=0\n")
            val json = SchedulerRunLedger.read(root)
            assertEquals(5, json.length())
            assertTrue(json.toString().contains("任务锁占用"))
            assertTrue(json.toString().contains("服务不可用"))
            assertTrue(json.toString().contains("任务已停止"))
            assertTrue(json.toString().contains("等待息屏"))
            assertFalse(json.toString().contains("已删除"))
        } finally { root.deleteRecursively() }
    }
}
