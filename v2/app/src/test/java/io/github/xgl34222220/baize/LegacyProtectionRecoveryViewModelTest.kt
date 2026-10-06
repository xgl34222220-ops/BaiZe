package io.github.xgl34222220.baize

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyProtectionRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val path = "/storage/emulated/0/Download/keep"
    private val current = "current.app"
    private fun pending() = LegacyProtectionRecovery.RecoverySnapshot(
        currentPackages = setOf(current), currentPaths = emptySet(),
        pendingPackages = emptySet(), pendingPaths = setOf(path),
        packageReviewRequired = false, pathReviewRequired = true, fingerprint = "first", currentPackagesPresent = true)

    private class Access(var snapshot: LegacyProtectionRecovery.RecoverySnapshot) : LegacyProtectionRecoveryAccess {
        var reads = 0
        var writes = 0
        var readError: Exception? = null
        var writeError: Exception? = null
        var selected: Pair<Set<String>, Set<String>>? = null
        override fun read(): LegacyProtectionRecovery.RecoverySnapshot {
            reads++
            readError?.let { throw it }
            return snapshot
        }
        override fun resolve(expected: LegacyProtectionRecovery.RecoverySnapshot,
            packages: Set<String>, paths: Set<String>): LegacyProtectionRecovery.RecoverySnapshot {
            writes++
            check(expected.fingerprint == snapshot.fingerprint) { "记录已变化" }
            writeError?.let { throw it }
            selected = packages to paths
            snapshot = snapshot.copy(currentPaths = paths, pendingPaths = emptySet(), pendingPackages = emptySet(),
                packageReviewRequired = false, pathReviewRequired = false, fingerprint = "resolved")
            return snapshot
        }
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { dispatcher.scheduler.advanceUntilIdle(); Dispatchers.resetMain() }
    private fun drain() { dispatcher.scheduler.advanceUntilIdle() }

    @Test fun readingDefaultsToAllCandidatesWithoutWritingAndPreservesCurrentRules() {
        val access = Access(pending())
        val model = LegacyProtectionRecoveryViewModel(access, dispatcher)
        model.initialize()
        assertTrue(model.state.loading)
        assertFalse(model.state.canReview)
        drain()
        assertEquals(0, access.writes)
        assertEquals(setOf(path), model.state.selectedPaths)
        assertEquals(setOf(current), model.state.snapshot!!.currentPackages)
        model.togglePackage(current)
        assertTrue(model.state.selectedPackages.isEmpty())
        assertTrue(model.state.canReview)
    }

    @Test fun initializationRefreshAndSaveCannotDoubleSubmitDuringAnInFlightWrite() {
        val access = Access(pending())
        val model = LegacyProtectionRecoveryViewModel(access, dispatcher)
        model.initialize(); model.initialize(); drain()
        model.save()
        assertTrue(model.state.saving)
        model.save(); model.refresh(); model.initialize(); model.togglePath(path)
        drain()
        assertEquals(1, access.reads)
        assertEquals(1, access.writes)
        assertEquals(emptySet<String>() to setOf(path), access.selected)
        assertTrue(model.state.confirmed)
        assertFalse(model.state.canReview)
        assertEquals(setOf(current), model.state.snapshot!!.currentPackages)
    }

    @Test fun readFailureNeverBecomesAnEmptyReviewOrAWritableState() {
        val access = Access(pending()).apply { readError = IllegalStateException("损坏的存储") }
        val model = LegacyProtectionRecoveryViewModel(access, dispatcher)
        model.initialize(); drain(); model.save(); drain()
        assertNull(model.state.snapshot)
        assertTrue(model.state.needsRefresh)
        assertTrue(model.state.error.contains("损坏的存储"))
        assertFalse(model.state.canReview)
        assertEquals(0, access.writes)
    }

    @Test fun staleFingerprintRequiresFreshReviewAndNeverRetriesTheWrite() {
        val access = Access(pending())
        val model = LegacyProtectionRecoveryViewModel(access, dispatcher)
        model.initialize(); drain()
        model.togglePath(path)
        access.snapshot = pending().copy(fingerprint = "newer", pendingPaths = setOf(path, "$path/new"))
        model.save(); drain(); model.save(); drain()
        assertEquals(1, access.writes)
        assertTrue(model.state.needsRefresh)
        assertFalse(model.state.canReview)
        assertTrue(model.state.selectedPaths.isEmpty())
        model.refresh(); drain()
        assertEquals(setOf(path, "$path/new"), model.state.selectedPaths)
        assertTrue(model.state.canReview)
        assertEquals(1, access.writes)
    }

    @Test fun unchangedRefreshKeepsExplicitSelectionsAndFailedSaveKeepsKnownRules() {
        val access = Access(pending())
        val model = LegacyProtectionRecoveryViewModel(access, dispatcher)
        model.initialize(); drain()
        model.togglePath(path); model.refresh(); drain()
        assertTrue(model.state.selectedPaths.isEmpty())
        access.writeError = IllegalStateException("写入失败")
        model.save(); drain()
        assertEquals(setOf(current), model.state.snapshot!!.currentPackages)
        assertFalse(model.state.confirmed)
        assertTrue(model.state.needsRefresh)
        model.save(); drain()
        assertEquals(1, access.writes)
    }

    @Test fun emptyHistoricalSetStillRequiresExplicitSaveAndInvalidSelectionIsBlocked() {
        val snapshot = pending().copy(pendingPaths = emptySet())
        val access = Access(snapshot)
        val model = LegacyProtectionRecoveryViewModel(access, dispatcher)
        model.initialize(); drain()
        assertTrue(model.state.canReview)
        assertEquals(0, access.writes)
        model.togglePath("/not/a/candidate")
        assertTrue(model.state.selectedPaths.isEmpty())
        assertFalse(model.state.copy(selectedPaths = setOf("/not/a/candidate")).canReview)
        model.save(); drain()
        assertEquals(1, access.writes)
        assertTrue(model.state.confirmed)
    }
}
