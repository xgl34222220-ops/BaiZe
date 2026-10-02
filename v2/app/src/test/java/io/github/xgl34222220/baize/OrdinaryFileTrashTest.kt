package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OrdinaryFileTrashTest {
    private fun fixture(test: (File, OrdinaryFileTrash) -> Unit) {
        val root = Files.createTempDirectory("baize-trash-test").toFile()
        try { test(root, OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), now = { 1234L })) }
        finally { root.deleteRecursively() }
    }
    @Test fun moveSurvivesRepositoryRestartAndRestoreKeepsConflict() = fixture { root, trash ->
        val source = File(root, "photo.jpg").apply { writeText("original") }
        val entry = trash.move(source, source.length(), OrdinaryFileTrash.digest(source), 1000) { true }
        assertFalse(source.exists()); assertEquals(8L, trash.entries().single().bytes)
        source.writeText("new original")
        val restarted = OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")))
        val restored = restarted.restore(entry.id)
        assertEquals("original", restored.readText()); assertEquals("new original", source.readText())
        assertTrue(restarted.entries().isEmpty())
    }
    @Test fun budgetProtectionChangeAndSymlinkNeverRemoveOriginal() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val hash = OrdinaryFileTrash.digest(source)
        assertTrue(runCatching { trash.move(source, 4, hash, 3) { true } }.isFailure)
        assertTrue(runCatching { trash.move(source, 4, hash, 10) { false } }.isFailure)
        val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), source.toPath())
        assertTrue(runCatching { trash.move(link, 4, hash, 10) { true } }.isFailure)
        assertEquals("data", source.readText()); assertTrue(trash.entries().isEmpty())
    }
    @Test fun modifiedPayloadCannotBeRestoredOrPurged() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 10) { true }
        File(entry.stored).writeText("evil")
        assertTrue(runCatching { trash.restore(entry.id) }.isFailure)
        assertTrue(runCatching { trash.purge(entry.id) }.isFailure)
        assertEquals(1, trash.entries().size)
    }
    @Test fun changedPayloadCanBeExplicitlyRecoveredAndPartialConflictDoesNotBlockRetry() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        File(entry.stored).writeText("changed contents")
        source.writeText("existing")
        File(root, "file.baize-restored-${entry.id.take(8)}").writeText("partial")
        val restored = trash.restore(entry.id, allowChanged = true)
        assertEquals("changed contents", restored.readText())
        assertEquals("existing", source.readText())
        assertEquals("partial", File(root, "file.baize-restored-${entry.id.take(8)}").readText())
    }
    @Test fun interruptedPreMoveJournalCanBeClearedWithoutTouchingOriginal() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        File(entry.stored).renameTo(source)
        assertEquals(1, trash.entries().size)
        trash.forgetMissing(entry.id)
        assertTrue(trash.entries().isEmpty()); assertEquals("data", source.readText())
    }
    @Test fun directorySyncFailureBeforeMoveOrBeforeBackupRemovalKeepsRecoverableData() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val failing = OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), syncDirectory = { error("sync unavailable") })
        assertTrue(runCatching { failing.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true } }.isFailure)
        assertEquals("data", source.readText())
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        assertTrue(runCatching { failing.restore(entry.id) }.isFailure)
        assertTrue(File(entry.stored).exists()); assertEquals("data", source.readText())
        assertEquals("data", trash.restore(entry.id).readText())
    }
    @Test fun expirationDoesNotSilentlyDestroyAndPurgeIsExplicit() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 10) { true }
        val future = OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), now = { Long.MAX_VALUE })
        assertEquals(1, future.entries().size)
        assertEquals(4L, future.purge(entry.id)); assertTrue(future.entries().isEmpty())
    }
}
