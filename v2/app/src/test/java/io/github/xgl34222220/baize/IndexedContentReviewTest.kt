package io.github.xgl34222220.baize

import android.app.Application
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class IndexedContentReviewTest {
    private val path = "/storage/emulated/0/Download/synthetic.zip"
    private val identity = ApkFileIdentity(path, 1, 2, 4, 5, 6, 0, 0)
    private val guard = ApkDeletionGuard(setOf("/storage/emulated/0"), "/storage/emulated/0")
    private val item = IndexedApkCandidate(1, "content://media/external/file/1", path, "synthetic.zip", 4, 5, identity)

    @Test fun missingScanIdentityIsRetainedWithoutInventingANewOne() {
        val batch = IndexedContentReview.prepare(listOf(item.copy(identity = null)), guard, { false })
        assertTrue(batch.proofs.isEmpty())
        assertTrue(batch.rejected.getValue(item.uri).contains("扫描时未取得"))
    }
    @Test fun oversizedSelectedBatchIsRejectedBeforeReadingContent() {
        val bytes = IndexedContentReview.MAX_BATCH_BYTES + 1
        val batch = IndexedContentReview.prepare(listOf(item.copy(bytes = bytes, identity = identity.copy(bytes = bytes))), guard, { false })
        assertTrue(batch.proofs.isEmpty())
        assertTrue(batch.rejected.getValue(item.uri).contains("32 GiB"))
    }
    @Test fun cancellingReviewDoesNotPublishAPartialAuthorization() {
        assertThrows(CancellationException::class.java) {
            IndexedContentReview.prepare(listOf(item), guard, { true })
        }
    }
    @Test fun expiredFutureMalformedOrOtherFileProofCannotAuthorizeDeletion() {
        val now = SystemClock.elapsedRealtime()
        val proof = IndexedContentProof(identity, "0".repeat(64), now)
        for (invalid in listOf(null, proof.copy(createdAt = now - IndexedContentReview.REVIEW_TTL_MS - 1),
            proof.copy(createdAt = now + 1), proof.copy(sha256 = "incomplete"), proof.copy(identity = identity.copy(inode = 9)))) {
            assertFalse(IndexedContentReview.matches(invalid, identity, guard, { false }))
        }
    }
}
