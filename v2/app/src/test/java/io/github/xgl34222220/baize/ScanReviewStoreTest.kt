package io.github.xgl34222220.baize

import android.app.Application
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ScanReviewStoreTest {
    @Test fun readAfterQueuedSaveReturnsLatestReview() {
        val context = RuntimeEnvironment.getApplication()
        ScanReviewStore.save(context, "test") { JSONObject().put("phase", "扫描完成") }
        ScanReviewStore.save(context, "test") { JSONObject().put("phase", "清理完成").put("packageName", "com.example.app") }
        val loaded = requireNotNull(ScanReviewStore.read(context, "test"))
        assertEquals("清理完成", loaded.getString("phase"))
        assertEquals("com.example.app", loaded.getString("packageName"))
    }

    @Test fun incompletePagePreviewRetainsSnapshotIdentityWithoutCleanupPermission() {
        val context = RuntimeEnvironment.getApplication()
        ScanReviewStore.save(context, "incremental") {
            JSONObject().put("cacheSnapshotId", "cache-123").put("profileSnapshotId", "profile-456")
                .put("loadingResults", true).put("scanReady", false)
                .put("selected", JSONArray().put("profile:item"))
                .put("items", JSONArray().put(JSONObject().put("id", "profile:item")))
        }
        val restored = requireNotNull(ScanReviewStore.read(context, "incremental"))
        assertEquals("cache-123", restored.getString("cacheSnapshotId"))
        assertEquals("profile-456", restored.getString("profileSnapshotId"))
        assertTrue(restored.getBoolean("loadingResults"))
        assertFalse(restored.getBoolean("scanReady"))
        assertEquals("profile:item", restored.getJSONArray("selected").getString(0))
        assertEquals(1, restored.getJSONArray("items").length())
    }

    @Test fun restartBeforeFirstPageOverwritesPreviouslyReadyReview() {
        val context = RuntimeEnvironment.getApplication()
        ScanReviewStore.save(context, "restart") {
            JSONObject().put("scanReady", true).put("profileSnapshotId", "obsolete")
                .put("items", JSONArray().put(JSONObject().put("id", "old")))
        }
        ScanReviewStore.save(context, "restart") {
            JSONObject().put("scanReady", false).put("profileSnapshotId", "")
                .put("items", JSONArray())
        }
        val restored = requireNotNull(ScanReviewStore.read(context, "restart"))
        assertFalse(restored.getBoolean("scanReady"))
        assertEquals("", restored.getString("profileSnapshotId"))
        assertEquals(0, restored.getJSONArray("items").length())
    }
}
