package io.github.xgl34222220.baize

import android.app.Application
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReviewHydrationLifecycleTest {
    @Test fun leavingWorkbenchBeforeReadFinishesDoesNotEraseThePreviousScan() =
        exitWhileDiskIsBlocked(ScanWorkbenchActivity::class.java, "safe", "runScan", "profileBound")

    @Test fun leavingOrganizerBeforeReadFinishesDoesNotEraseThePreviousPreview() =
        exitWhileDiskIsBlocked(FileOrganizerActivity::class.java, "organizer", "oneTapOrganize", "bound")

    private fun <T : ComponentActivity> exitWhileDiskIsBlocked(type: Class<T>, key: String, scanMethod: String, boundField: String) {
        val context = RuntimeEnvironment.getApplication()
        ScanReviewStore.save(context, key) { JSONObject().put("marker", "previous-review") }
        assertEquals("previous-review", ScanReviewStore.read(context, key)!!.getString("marker"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ScanReviewStore.save(context, "hydration-blocker") {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test disk barrier was not released" }
            JSONObject()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val controller = Robolectric.buildActivity(type).create()
            // Even a callback already queued before composition disables the button is gated.
            type.getDeclaredMethod(scanMethod).apply { isAccessible = true }.invoke(controller.get())
            assertFalse(type.getDeclaredField(boundField).apply { isAccessible = true }.getBoolean(controller.get()))
            controller.stop().destroy()
        } finally {
            release.countDown()
        }
        assertEquals("previous-review", ScanReviewStore.read(context, key)!!.getString("marker"))
    }
}
