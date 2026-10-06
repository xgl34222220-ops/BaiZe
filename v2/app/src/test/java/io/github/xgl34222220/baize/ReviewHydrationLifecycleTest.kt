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


    @Test fun configurationRecreationKeepsTheSameTaskSession() {
        val context = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ScanReviewStore.save(context, "rotation-barrier") {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            JSONObject()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val controller = Robolectric.buildActivity(ScanWorkbenchActivity::class.java).setup()
            val retained = controller.get().session
            controller.recreate()
            assertSame("Rotation must not create a second scan/session", retained, controller.get().session)
            controller.pause().stop().destroy()
        } finally { release.countDown() }
    }

    @Test fun backDoesNotSilentlyDropARunningTask() {
        val controller = Robolectric.buildActivity(ScanWorkbenchActivity::class.java).create()
        val activity = controller.get()
        @Suppress("UNCHECKED_CAST")
        val state = ScanWorkbenchSession::class.java.getDeclaredField("screenState\$delegate")
            .apply { isAccessible = true }.get(activity.session) as androidx.compose.runtime.MutableState<WorkbenchUiState>
        state.value = state.value.copy(running = true)
        val back = ScanWorkbenchActivity::class.java.getDeclaredMethod("requestBack").apply { isAccessible = true }
        back.invoke(activity)
        assertFalse(activity.isFinishing)
        assertTrue(activity.shouldConfirmStop())
        state.value = state.value.copy(running = false)
        assertFalse(activity.shouldConfirmStop())
        ScanWorkbenchSession::class.java.getDeclaredField("operationEpoch").apply { isAccessible = true }
            .setLong(activity.session, activity.session.operationToken + 1)
        state.value = state.value.copy(running = true)
        assertFalse("A new task must not resurrect the previous Back dialog", activity.shouldConfirmStop())
        state.value = state.value.copy(running = false)
        back.invoke(activity)
        assertTrue(activity.isFinishing)
        controller.destroy()
    }

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
            val target = if (controller.get() is ScanWorkbenchActivity) (controller.get() as ScanWorkbenchActivity).session else controller.get()
            target.javaClass.getDeclaredMethod(scanMethod).apply { isAccessible = true }.invoke(target)
            assertFalse(target.javaClass.getDeclaredField(boundField).apply { isAccessible = true }.getBoolean(target))
            controller.stop().destroy()
        } finally {
            release.countDown()
        }
        assertEquals("previous-review", ScanReviewStore.read(context, key)!!.getString("marker"))
    }
}
