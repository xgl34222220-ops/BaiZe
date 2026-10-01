package io.github.xgl34222220.baize

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

/** Synthetic index rows only. Any attempt to delete is a test failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [DashboardRootBindingShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ApkIndexAndRecreationRegressionTest {
    private lateinit var provider: SyntheticApkIndexProvider

    @Before fun registerProvider() {
        provider = SyntheticApkIndexProvider()
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun unavailableProviderIsNotReportedAsSuccessfulEmptyStorage() {
        provider.returnNull = true
        val result = ApkMediaStoreIndex.query(RuntimeEnvironment.getApplication())
        assertEquals("The fixture must actually reach the provider", 1, provider.queries)
        assertTrue(result.candidates.isEmpty())
        assertFalse("A null provider cursor must not become a successful zero-result scan", result.error.isNullOrBlank())
    }

    @Test fun tenThousandRowLimitIsDisclosedToThePersonReviewingResults() {
        provider.rowCount = 10_001
        val controller = Robolectric.buildActivity(ApkScanActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.call("startScan")
            await { !activity.state().running && activity.state().items.size == 10_000 }
            val coverage = activity.state().coverage.single()
            assertNotEquals("A capped result is partial coverage, not a completed full scan", "scanned", coverage.status)
            assertTrue("The result must explain the limit", coverage.reason.contains("上限"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun completedApkReviewAndSelectionSurviveActivityRecreationWithoutRescanning() {
        provider.rowCount = 2
        val controller = Robolectric.buildActivity(ApkScanActivity::class.java).setup()
        try {
            val before = controller.get()
            before.call("startScan")
            await { !before.state().running && before.state().items.size == 2 }
            val uri = before.state().items.first().uri
            before.javaClass.getDeclaredMethod("toggleItem", String::class.java)
                .apply { isAccessible = true }.invoke(before, uri)
            assertEquals(setOf(uri), before.state().selected)
            val queries = provider.queries
            controller.recreate()
            val after = controller.get()
            await { !after.state().running }
            assertEquals("Rotation must retain the review, not silently return to an empty screen", 2, after.state().items.size)
            assertEquals(setOf(uri), after.state().selected)
            assertTrue(after.state().cleanReady)
            assertEquals("Restoring a review must not discover a different set of files", queries, provider.queries)
        } finally { controller.pause().stop().destroy() }
    }

    private fun ApkScanActivity.state(): ApkScanUiState = javaClass.getDeclaredMethod("getScreenState")
        .apply { isAccessible = true }.invoke(this) as ApkScanUiState
    private fun ApkScanActivity.call(name: String) = javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("The synthetic scan did not finish in time", condition())
    }
}

class SyntheticApkIndexProvider : ContentProvider() {
    var returnNull = false
    var rowCount = 0
    var queries = 0
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        queries++
        if (returnNull) return null
        val columns = requireNotNull(projection)
        return MatrixCursor(columns).apply {
            for (id in 1..rowCount) addRow(columns.map { column -> when (column) {
                MediaStore.Files.FileColumns._ID -> id.toLong()
                MediaStore.MediaColumns.DISPLAY_NAME -> "synthetic-$id.apk"
                MediaStore.MediaColumns.SIZE -> 128L
                MediaStore.MediaColumns.DATA -> "/storage/emulated/0/Download/synthetic-$id.apk"
                MediaStore.MediaColumns.DATE_MODIFIED -> 1_700_000_000L
                MediaStore.MediaColumns.MIME_TYPE -> "application/vnd.android.package-archive"
                else -> null
            } }.toTypedArray())
        }
    }
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("This fixture is read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Deletion is not authorized in an index/recreation test")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("This fixture is read-only")
}
