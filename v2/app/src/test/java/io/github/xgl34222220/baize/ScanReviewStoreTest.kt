package io.github.xgl34222220.baize

import android.app.Application
import org.json.JSONObject
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
}
