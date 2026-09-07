package io.github.xgl34222220.baize.root

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RootMediaScanQueueContextTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun pendingMediaDoesNotCrashWhenRootContextHasNoApplication() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context? = null
        }
        val state = folder.newFolder("state")
        val media = folder.newFile("photo.jpg")
        assertEquals(1, RootMediaScanQueue.enqueue(state, listOf(media.path)))
        // Previously submit() received null and threw before its scan failure handler.
        assertEquals(1, RootMediaScanQueue.flush(context, state))
    }
}
