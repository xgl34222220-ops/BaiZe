package io.github.xgl34222220.baize

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/** All supplied stat tuples here are synthetic; device observations are reported separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ContentIdentityReliabilityTest {
    private fun record(id: Long, nanos: Long = 123000000): StorageFileRecord {
        val path = "/storage/emulated/0/Download/content-$id.bin"
        return StorageFileRecord(id, "content://media/external/file/$id", path, "content-$id.bin", 131072, 1500000000,
            "application/octet-stream", identity = ApkFileIdentity(path, 43, id, 131072, 1500000000, 1791080000, nanos, nanos))
    }

    private fun collide(nanos: Long) {
        val files = listOf(record(1, nanos), record(2, nanos))
        val contents = mutableMapOf(1L to ByteArray(131072), 2L to ByteArray(131072))
        val cache = StorageDigestCache()
        var reads = 0
        val open: (StorageFileRecord) -> InputStream = { reads++; contents.getValue(it.id).inputStream() }
        assertEquals(1, StorageDuplicateMatcher.match(files, open, cache = cache).size)
        val before = reads
        contents.getValue(2)[131071] = 7
        assertTrue(StorageDuplicateMatcher.match(files, open, cache = cache).isEmpty())
        assertTrue(reads > before)
        assertEquals(0, cache.hits)
        contents.getValue(2)[131071] = 0
        assertEquals(1, StorageDuplicateMatcher.match(files, open, cache = cache).size)
    }
    @Test fun nonzeroNanosecondsCannotRefreshContentAuthority() = collide(123000000)
    @Test fun exactSecondClockCannotRefreshContentAuthority() = collide(0)
    @Test fun unavailableNanosecondsCannotRefreshContentAuthority() = collide(-1)

    @Test fun readFailureAfterAnEarlierSuccessfulScanDoesNotReuseOldProof() {
        val files = listOf(record(1), record(2)); val cache = StorageDigestCache()
        assertEquals(1, StorageDuplicateMatcher.match(files, { ByteArrayInputStream(ByteArray(131072)) }, cache = cache).size)
        var failures = 0
        assertTrue(StorageDuplicateMatcher.match(files, { throw IOException("synthetic unavailable content") },
            unreadable = { failures++ }, cache = cache).isEmpty())
        assertEquals(2, failures)
    }

    @Test fun missingStatRetainsTheFileWithoutPublishingAnAuthorization() {
        val item = record(1).asIndexedCandidate().copy(identity = null)
        val guard = ApkDeletionGuard(setOf("/storage/emulated/0"), "/storage/emulated/0")
        val batch = IndexedContentReview.prepare(listOf(item), guard, { false })
        assertTrue(batch.proofs.isEmpty())
        assertEquals(setOf(item.uri), batch.rejected.keys)
        val state = StorageToolsUiState(records = listOf(record(1).copy(identity = null)))
        assertTrue(state.toggleAllSelection().selected.isEmpty())
        assertEquals(0L, state.selectedBytes)
    }

    private fun noProgress(prefix: Boolean) {
        var reads = 0; var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("A stalled stream must not produce a content proof")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (++reads > 3) throw AssertionError("No-progress reads spun instead of rejecting an unavailable file")
                return 0
            }
            override fun close() { closed = true }
        }
        assertNull(StorageDuplicateMatcher.digest(record(1), prefix, { stream }))
        assertTrue(closed)
        assertEquals(3, reads)
    }
    @Test fun stalledPrefixReadIsBoundedAndNeverComparedAsEmptyContent() = noProgress(true)
    @Test fun stalledFullReadIsBoundedAndNeverComparedAsEmptyContent() = noProgress(false)

    @Test fun cancellationWinsOverNoProgressAndClosesTheStream() {
        var stopped = false; var closed = false
        val stream = object : InputStream() {
            override fun read() = -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { stopped = true; return 0 }
            override fun close() { closed = true }
        }
        assertThrows(CancellationException::class.java) {
            StorageDuplicateMatcher.digest(record(1), false, { stream }, { stopped })
        }
        assertTrue(closed)
    }
}
