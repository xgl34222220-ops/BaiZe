package io.github.xgl34222220.baize

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PhotoCompressionJournalTest {
    private val row = PhotoCompressionRecord("原图.jpg", "a".repeat(64), "b".repeat(64), "", 8192, 1024, 60, 2048, PhotoMetadataMode.STRIP)
    private fun fixture(block: (File, PhotoCompressionJournal) -> Unit) {
        val dir = Files.createTempDirectory("photo-journal").toFile()
        try { block(dir, PhotoCompressionJournal(File(dir, "journal.json"))) } finally { dir.deleteRecursively() }
    }
    @Test fun beginSurvivesColdRecreationEvenBeforeDestinationExists() = fixture { dir, journal ->
        journal.begin(2)
        val restored = PhotoCompressionJournal(File(dir, "journal.json"))
        assertTrue(restored.requiresReview()); assertTrue(restored.message().contains("未自动重试")); assertTrue(restored.pending().isEmpty())
    }
    @Test fun createdTargetAndActualProviderNameSurviveRecreation() = fixture { dir, journal ->
        journal.begin(1); val id = journal.prepare("requested.jpg", row)
        journal.target(id, "content://fixture/document/owned", "actual-name.jpg")
        val pending = PhotoCompressionJournal(File(dir, "journal.json")).pending().single()
        assertEquals(id, pending.id); assertEquals("actual-name.jpg", pending.filename); assertEquals("content://fixture/document/owned", pending.record.uri)
    }
    @Test fun failureRecordIsKeptAfterWorkerFinishes() = fixture { _, journal ->
        journal.begin(1); val id = journal.prepare("owned.jpg", row); journal.note(id, "无法读取，已保留"); journal.finish()
        assertTrue(journal.requiresReview()); assertEquals("无法读取，已保留", journal.pending().single().note)
        assertTrue(runCatching { journal.begin(1) }.isFailure)
    }
    @Test fun confirmingOneCopyKeepsOtherPendingCopies() = fixture { _, journal ->
        journal.begin(2); val first = journal.prepare("first.jpg", row); val second = journal.prepare("second.jpg", row)
        journal.confirmed(first); journal.finish()
        assertEquals(second, journal.pending().single().id); assertTrue(journal.requiresReview())
    }
    @Test fun cacheCleanupOnlyRemovesJournaledGeneratedBatchIntermediates() = fixture { dir, journal ->
        val cache = File(dir, "cache").apply { mkdir() }
        val generated = File(cache, "photo-batch-output-${UUID.randomUUID()}.jpg").apply { writeText("temporary") }
        val untracked = File(cache, "photo-batch-source-${UUID.randomUUID()}.jpg").apply { writeText("untracked") }
        val original = File(dir, "original.jpg").apply { writeText("original") }
        val outside = File(dir, "photo-batch-source-${UUID.randomUUID()}.jpg").apply { writeText("outside") }
        journal.begin(1); journal.trackCache(generated, original, outside); journal.discardOwnedCache(cache)
        assertFalse(generated.exists()); assertTrue(untracked.exists()); assertTrue(original.exists()); assertTrue(outside.exists())
    }
    @Test fun closingReminderDoesNotDeleteAnyTargetOrOriginal() = fixture { dir, journal ->
        val target = File(dir, "target.jpg").apply { writeText("partial") }; val original = File(dir, "original.jpg").apply { writeText("original") }
        journal.begin(1); journal.prepare(target.name, row.copy(uri = target.toURI().toString())); journal.clearReminder()
        assertEquals("partial", target.readText()); assertEquals("original", original.readText()); assertFalse(journal.requiresReview())
    }
    @Test fun malformedJournalFailsClosedAndCannotBeOverwrittenByNewExport() = fixture { dir, journal ->
        File(dir, "journal.json").writeText("{broken")
        assertTrue(runCatching { journal.requiresReview() }.isFailure); assertTrue(runCatching { journal.begin(1) }.isFailure)
        assertEquals("{broken", File(dir, "journal.json").readText())
    }
}
