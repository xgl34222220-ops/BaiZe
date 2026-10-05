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
    @Test fun reviewedSnapshotRejectsRewrittenJournalBeforeRestoreOrPurge() = fixture { root, trash ->
        val source = File(root, "reviewed.apk").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        val replacement = entry.copy(original = File(root, "different.apk").path, created = entry.created + 1)
        File(root, "metadata/${entry.id}.json").writeText(replacement.json().toString())
        assertTrue(runCatching { trash.purge(entry.id, expected = entry) }.isFailure)
        assertTrue(runCatching { trash.restore(entry.id, expected = entry) }.isFailure)
        assertEquals("data", File(entry.stored).readText())
        assertFalse(File(replacement.original).exists())
        assertEquals(1, trash.entries().size)
    }
    @Test fun reviewedPurgeDoesNotDeleteNewArrivalAndKeepsChangedContent() = fixture { root, trash ->
        fun add(name: String): TrashEntry {
            val source = File(root, name).apply { writeText("data") }
            return trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        }
        val reviewed = add("reviewed.apk")
        val later = add("later.apk")
        assertEquals(4L, trash.purge(reviewed.id, expected = reviewed))
        assertEquals(listOf(later.id), trash.entries().map { it.id })
        File(later.stored).writeText("changed")
        assertTrue(runCatching { trash.purge(later.id, expected = later) }.isFailure)
        assertEquals("changed", File(later.stored).readText())
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
    @Test fun crossMountOrAtomicRenameFailureNeverFallsBackToDeletingOriginal() = fixture { root, _ ->
        val source = File(root, "file").apply { writeText("data") }
        val noMount = OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), sameFileSystem = { _, _ -> false })
        assertTrue(runCatching { noMount.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true } }.isFailure)
        val exdev = OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), atomicMove = { a, b ->
            throw java.nio.file.FileSystemException(a.path, b.path, "Cross-device link")
        })
        assertTrue(runCatching { exdev.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true } }.isFailure)
        assertEquals("data", source.readText()); assertTrue(exdev.entries().isEmpty())
    }
    @Test fun sourceParentRenameOrRemovalCannotRemoveVolumeRootPayload() = fixture { root, trash ->
        val parent = File(root, "original-parent").apply { mkdirs() }
        val source = File(parent, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        val movedParent = File(root, "renamed-parent")
        assertTrue(parent.renameTo(movedParent)); assertTrue(movedParent.delete())
        assertTrue(File(entry.stored).isFile)
        assertTrue(runCatching { trash.restore(entry.id) }.isFailure)
        assertEquals(1, trash.entries().size)
        assertTrue(parent.mkdir())
        assertEquals("data", trash.restore(entry.id).readText())
    }
    @Test fun externallyGrownPayloadStillConsumesBudgetAndReservedPathsCannotReenterTrash() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        File(entry.stored).writeText("x".repeat(20))
        assertEquals(20L, trash.occupiedBytes())
        source.writeText("data")
        assertTrue(runCatching { trash.move(source, 4, OrdinaryFileTrash.digest(source), 21) { true } }.isFailure)
        assertTrue(OrdinaryFileTrash.isPayloadPath("/storage/emulated/0/.baize-file-trash/app/file"))
        assertFalse(OrdinaryFileTrash.isPayloadPath("/storage/emulated/0/.baize-file-trash-old/file"))
        assertTrue(OrdinaryFileTrash.isPayloadPath("/storage/SD/.BAIZE-FILE-TRASH/app/file"))
        assertTrue(OrdinaryFileTrash.isPayloadPath("/storage/SD/ANDROID/DATA/IO.GITHUB.XGL34222220.BAIZE/FILES/RECOVERABLE-TRASH/file"))
        assertFalse(StorageMediaRepository.safeSharedFile("/storage/SD/.BAIZE-FILE-TRASH/app/file"))
    }
    @Test fun changedOrUnavailablePayloadPathsNeverMakePrivateJournalsDisappear() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        val payloadRoot = File(entry.stored).parentFile!!
        val relocated = File(root, "relocated")
        assertTrue(payloadRoot.renameTo(relocated))
        assertEquals(TrashPayloadState.UNAVAILABLE, trash.entries().single().payloadState)
        assertEquals(4L, trash.occupiedBytes())
        assertTrue(runCatching { trash.forgetMissing(entry.id) }.isFailure)
        Files.createSymbolicLink(payloadRoot.toPath(), relocated.toPath())
        assertEquals(TrashPayloadState.PATH_CHANGED, trash.entries().single().payloadState)
        assertEquals(4L, trash.occupiedBytes())
        assertTrue(runCatching { trash.restore(entry.id, allowChanged = true) }.isFailure)
        assertTrue(runCatching { trash.purge(entry.id) }.isFailure)
        Files.delete(payloadRoot.toPath()); assertTrue(relocated.renameTo(payloadRoot))
        assertEquals("data", trash.restore(entry.id).readText())
    }
    @Test fun payloadSymlinkCannotHideJournalOrCauseReadDeleteOfAnotherFile() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 100) { true }
        val outside = File(root, "outside").apply { writeText("do not touch this file") }
        assertTrue(File(entry.stored).delete())
        Files.createSymbolicLink(File(entry.stored).toPath(), outside.toPath())
        assertEquals(TrashPayloadState.PATH_CHANGED, trash.entries().single().payloadState)
        assertEquals(4L, trash.occupiedBytes())
        assertTrue(runCatching { trash.restore(entry.id, allowChanged = true) }.isFailure)
        assertTrue(runCatching { trash.purge(entry.id) }.isFailure)
        assertEquals("do not touch this file", outside.readText())
    }
    @Test fun expirationDoesNotSilentlyDestroyAndPurgeIsExplicit() = fixture { root, trash ->
        val source = File(root, "file").apply { writeText("data") }
        val entry = trash.move(source, 4, OrdinaryFileTrash.digest(source), 10) { true }
        val future = OrdinaryFileTrash(File(root, "metadata"), listOf(File(root, "payload")), now = { Long.MAX_VALUE })
        assertEquals(1, future.entries().size)
        assertEquals(4L, future.purge(entry.id)); assertTrue(future.entries().isEmpty())
    }
}

