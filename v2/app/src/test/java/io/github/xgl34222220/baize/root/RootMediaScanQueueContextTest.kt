package io.github.xgl34222220.baize.root

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RootMediaScanQueueContextTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun mediaQueueDoesNotTouchRootApplicationOrContentResolver() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = error("Root has no Application")
            override fun getContentResolver(): android.content.ContentResolver = error("Unregistered Root process")
        }
        val state = folder.newFolder("state")
        val path = folder.newFile("我的 photo.jpg").path
        val called = CountDownLatch(1)
        RootMediaScanQueue.enqueue(state, listOf(path))
        assertEquals(1, RootMediaScanQueue.flush(context, state) { actual, _ ->
            assertEquals(path, actual)
            called.countDown()
            true
        })
        assertTrue(called.await(3, TimeUnit.SECONDS))
        awaitCondition { !File(state, RootMediaScanQueue.INFLIGHT_NAME).exists() }
    }

    @Test fun providerFailureRetainsInflightAndCanRetryAfterBackoff() {
        val context = RuntimeEnvironment.getApplication()
        val state = folder.newFolder("retry")
        val path = folder.newFile("a.jpg").path
        val failed = CountDownLatch(1)
        RootMediaScanQueue.enqueue(state, listOf(path))
        RootMediaScanQueue.flush(context, state) { _, _ -> failed.countDown(); false }
        assertTrue(failed.await(3, TimeUnit.SECONDS))
        assertTrue(File(state, RootMediaScanQueue.INFLIGHT_NAME).exists())
        val retried = CountDownLatch(1)
        awaitCondition {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
            RootMediaScanQueue.flush(context, state) { actual, _ ->
                assertEquals(path, actual); retried.countDown(); true
            } == 1
        }
        assertTrue(retried.await(3, TimeUnit.SECONDS))
        awaitCondition { !File(state, RootMediaScanQueue.INFLIGHT_NAME).exists() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(condition())
    }
}
