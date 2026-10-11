package io.github.xgl34222220.baize.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadStabilityGateTest {
    @Test fun minAgeIsClampedToOneMinuteThroughOneDay() {
        assertEquals(10, DownloadStabilityGate.clampMinAgeMinutes(null))
        assertEquals(1, DownloadStabilityGate.clampMinAgeMinutes(0))
        assertEquals(1, DownloadStabilityGate.clampMinAgeMinutes(-5))
        assertEquals(30, DownloadStabilityGate.clampMinAgeMinutes(30))
        assertEquals(1440, DownloadStabilityGate.clampMinAgeMinutes(99_999))
    }

    @Test fun recentAndFutureModificationTimesAreTreatedAsStillWriting() {
        val now = 1_000_000L
        assertTrue(DownloadStabilityGate.isRecent(now - 30, now, 600))
        assertTrue(DownloadStabilityGate.isRecent(now + 120, now, 600))
        assertFalse(DownloadStabilityGate.isRecent(now - 600, now, 600))
        assertFalse(DownloadStabilityGate.isRecent(now - 3_600, now, 600))
    }

    @Test fun fuseViewsAndBackingPathsNormalizeToTheMediaRoot() {
        assertEquals("/data/media/0/Download/a.mp4",
            DownloadStabilityGate.normalizeOpenTarget("/storage/emulated/0/Download/a.mp4"))
        assertEquals("/data/media/10/Download/b.mp4",
            DownloadStabilityGate.normalizeOpenTarget("/mnt/user/10/emulated/10/Download/b.mp4"))
        assertEquals("/data/media/0/Download/c.mp4",
            DownloadStabilityGate.normalizeOpenTarget("/mnt/pass_through/0/emulated/0/Download/c.mp4"))
        assertEquals("/data/media/0/Download/d.mp4",
            DownloadStabilityGate.normalizeOpenTarget("/data/media/0/Download/d.mp4 (deleted)"))
        assertNull(DownloadStabilityGate.normalizeOpenTarget("socket:[1234]"))
        assertNull(DownloadStabilityGate.normalizeOpenTarget("/data/app/base.apk"))
    }

    @Test fun gateVerdictPrefersRecentThenOpenThenStable() {
        val now = 2_000_000L
        val gate = DownloadStabilityGate(600, setOf("/data/media/0/Download/busy.mp4")) { now }
        assertEquals(DownloadStabilityGate.Verdict.RECENT, gate.check("/data/media/0/Download/new.mp4", now - 10))
        assertEquals(DownloadStabilityGate.Verdict.OPEN, gate.check("/data/media/0/Download/busy.mp4", now - 3_600))
        assertEquals(DownloadStabilityGate.Verdict.STABLE, gate.check("/data/media/0/Download/done.mp4", now - 3_600))
        assertNull(gate.reason(DownloadStabilityGate.Verdict.STABLE))
        assertTrue(gate.reason(DownloadStabilityGate.Verdict.RECENT)!!.contains("10 分钟"))
    }

    @Test fun procScanCollectsSharedStorageTargetsOnceAcrossProcesses() {
        val proc = Files.createTempDirectory("baize-proc").toFile()
        try {
            val fd1 = File(proc, "4242/fd").apply { mkdirs() }
            val fd2 = File(proc, "5151/fd").apply { mkdirs() }
            File(proc, "self").mkdirs()
            Files.createSymbolicLink(File(fd1, "7").toPath(), File("/storage/emulated/0/Download/a.mp4").toPath())
            Files.createSymbolicLink(File(fd1, "8").toPath(), File("/data/app/base.apk").toPath())
            Files.createSymbolicLink(File(fd2, "3").toPath(), File("/data/media/0/Download/a.mp4").toPath())
            Files.createSymbolicLink(File(fd2, "4").toPath(), File("/mnt/user/0/emulated/0/Download/b.mkv").toPath())
            val open = DownloadStabilityGate.scanOpenFiles(proc)
            assertEquals(setOf("/data/media/0/Download/a.mp4", "/data/media/0/Download/b.mkv"), open)
            // 上限生效：只看 1 个 fd 时不会遍历全部进程。
            assertTrue(DownloadStabilityGate.scanOpenFiles(proc, maxEntries = 1).size <= 1)
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test fun missingProcYieldsEmptySetInsteadOfFailing() {
        assertTrue(DownloadStabilityGate.scanOpenFiles(File("/nonexistent-baize-proc")).isEmpty())
    }
}
