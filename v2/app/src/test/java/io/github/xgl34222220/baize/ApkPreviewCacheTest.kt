package io.github.xgl34222220.baize

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApkPreviewCacheTest {
    private fun item(id: Int) = ApkScanItem("$id.apk", 1, 100, 0, "/synthetic/$id.apk", "content://media/$id", modifiedSeconds = 10)
    private fun parsed() = ApkArchiveInfo("归档应用", "synthetic.app", "1.0", status = ApkInstallStatus.NOT_INSTALLED,
        parseStatus = ApkArchiveParseStatus.PARSED)

    @Test fun artworkCacheIsBoundedAndChangedIdentityDoesNotReuseArtwork() = runTest {
        var reads = 0
        val cache = ApkPreviewCache(capacity = 2) { reads++; parsed() }
        cache.load(item(1)); cache.load(item(2)); cache.load(item(1))
        assertEquals(2, reads)
        cache.load(item(3)); cache.load(item(2))
        assertEquals(4, reads)
        cache.load(item(2).copy(modifiedSeconds = 11))
        assertEquals(5, reads)
    }

    @Test fun twoReadsAtMostRunTogetherAndWaitingRowsDoNotStartUnboundedWork() = runTest {
        val release = CompletableDeferred<Unit>()
        var active = 0; var peak = 0; var reads = 0
        val cache = ApkPreviewCache {
            active++; peak = maxOf(peak, active); reads++
            try { release.await(); parsed() } finally { active-- }
        }
        val jobs = (1..20).map { async { cache.load(item(it)) } }
        runCurrent()
        assertEquals(2, reads); assertEquals(2, peak)
        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(20, reads); assertEquals(2, peak); assertEquals(0, active)
    }

    @Test fun replacementReviewDiscardsAnOldReadAndCancellationDoesNotCacheAResult() = runTest {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val cache = ApkPreviewCache { reads++; if (reads == 1) release.await(); parsed() }
        val stale = async { cache.load(item(1)) }
        runCurrent(); cache.clear(); release.complete(Unit); runCurrent()
        assertTrue(stale.isCancelled)
        cache.load(item(1)); assertEquals(2, reads)
        val blocked = CompletableDeferred<Unit>()
        val second = ApkPreviewCache { reads++; blocked.await(); parsed() }
        val cancelled = async { second.load(item(2)) }
        runCurrent(); cancelled.cancel(); cancelled.join(); blocked.complete(Unit)
        second.load(item(2)); assertEquals(4, reads)
    }

    @Test fun pendingVersionMatchesStayVisibleButCannotBeSelectedAsKnownOlderPackages() {
        val pending = item(1)
        val older = item(2).copy(archive = parsed().copy(status = ApkInstallStatus.OLDER))
        val state = ApkScanUiState(items = listOf(pending, older), filter = ApkInstallStatus.OLDER)
        assertEquals(2, state.visibleItems.size)
        assertEquals(listOf(older), state.selectableVisibleItems)
        assertEquals(setOf(older.uri), state.toggleAllSelection().selected)
        val known = state.copy(items = listOf(pending.copy(archive = parsed()), older))
        assertEquals(listOf(older), known.visibleItems)
        assertEquals(listOf(pending), ApkScanUiState(items = listOf(pending), query = "1.apk").selectableVisibleItems)
    }
}
