package io.github.xgl34222220.baize

import android.os.CancellationSignal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ApkIndexPresenceReviewTest {
    private val missing = IndexedApkCandidate(42, "content://media/external/file/42", "/storage/emulated/0/Download/synthetic-missing.apk", "synthetic-missing.apk", 1024, 1000)
    private fun evidence() = ApkMissingIndexPolicyTest().evidence()
    @Test fun excludedRecordsCannotContributeCountBytesOrSelectionCandidates() {
        val unknown = missing.copy(id = 43, uri = "content://media/external/file/43", path = "/storage/emulated/0/Download/unknown.apk", bytes = 2048)
        val present = missing.copy(id = 44, uri = "content://media/external/file/44", identity = ApkFileIdentity(missing.path, 1, 2, 1024, 1000, 1000, 0, 0))
        val reviewed = ApkIndexPresenceReview.review(ApkMediaStoreResult(listOf(missing, unknown, present), 3), CancellationSignal(),
            inspect = { check(it.identity == null); if (it.id == 42L) evidence() else JSONObject() }, appUid = 10042, now = { 0 })
        assertEquals(listOf(unknown, present), reviewed.candidates)
        assertEquals(3072L, reviewed.candidates.sumOf { it.bytes })
        assertEquals(1, reviewed.confirmedMissingRecords)
        assertFalse(reviewed.missingCheckIncomplete)
    }
    @Test fun cancelledEvidenceReadCannotPublishAPartialReview() {
        val signal = CancellationSignal()
        val reviewed = ApkIndexPresenceReview.review(ApkMediaStoreResult(listOf(missing), 0), signal,
            inspect = { signal.cancel(); evidence() }, appUid = 10042, now = { 0 })
        assertTrue(reviewed.cancelled)
        assertTrue(reviewed.candidates.isEmpty())
        assertEquals(0, reviewed.confirmedMissingRecords)
    }
    @Test fun deadlineAndCheckLimitKeepUnconfirmedRows() {
        var ticks = 0
        val timed = ApkIndexPresenceReview.review(ApkMediaStoreResult(listOf(missing), 0), CancellationSignal(),
            inspect = { error("No read after budget expires") }, appUid = 10042, now = { if (ticks++ == 0) 0 else 5000 })
        assertEquals(listOf(missing), timed.candidates)
        assertTrue(timed.missingCheckIncomplete)
        var checks = 0
        val many = List(1001) { missing.copy(id = it.toLong() + 100) }
        val limited = ApkIndexPresenceReview.review(ApkMediaStoreResult(many, 0), CancellationSignal(),
            inspect = { checks++; JSONObject() }, appUid = 10042, now = { 0 })
        assertEquals(1000, checks)
        assertEquals(many, limited.candidates)
        assertTrue(limited.missingCheckIncomplete)
    }
    @Test fun failedReadIsUnknownAndCoroutineCancellationIsNotSwallowed() {
        val result = ApkMediaStoreResult(listOf(missing), 0)
        assertEquals(result.candidates, ApkIndexPresenceReview.review(result, CancellationSignal(),
            inspect = { throw java.io.IOException("synthetic disconnect") }, appUid = 10042, now = { 0 }).candidates)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ApkIndexPresenceReview.review(result, CancellationSignal(), inspect = { throw java.util.concurrent.CancellationException() }, appUid = 10042, now = { 0 })
        }
    }
}
