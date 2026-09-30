package io.github.xgl34222220.baize

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CleanerNavigationTest {
    @Test fun repeatedTapLaunchesOnlyOneWorkbenchUntilReturning() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(CleanerNavigation.scan(activity))
        assertFalse(CleanerNavigation.scan(activity))
        assertFalse(CleanerNavigation.scan(activity, "deep"))
        val first = shadowOf(activity).nextStartedActivity
        assertEquals(ScanWorkbenchActivity::class.java.name, first.component!!.className)
        assertEquals("safe", first.getStringExtra(ScanWorkbenchActivity.EXTRA_PROFILE))
        assertNotEquals(0, first.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().start().resume()
        assertTrue(CleanerNavigation.scan(activity, "deep"))
        assertEquals("deep", shadowOf(activity).nextStartedActivity.getStringExtra(ScanWorkbenchActivity.EXTRA_PROFILE))
        controller.pause().stop().destroy()
    }

    @Test fun legacyProfileEntryImmediatelyReplacesItselfWithWorkbench() {
        val intent = Intent().putExtra(ProfileActivity.EXTRA_PROFILE, "deep")
        val controller = Robolectric.buildActivity(ProfileActivity::class.java, intent).create()
        assertTrue(controller.get().isFinishing)
        val destination = shadowOf(controller.get()).nextStartedActivity
        assertEquals(ScanWorkbenchActivity::class.java.name, destination.component!!.className)
        assertEquals("deep", destination.getStringExtra(ScanWorkbenchActivity.EXTRA_PROFILE))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.destroy()
    }

    @Test fun legacyRequestCannotReopenAPageAfterRecreationOrDelayedConnection() {
        val intent = Intent().putExtra(MiuixDashboardActivity.EXTRA_RUN_SMART_CLEAN, true)
        assertTrue(CleanerNavigation.consumeLegacyRequest(intent))
        assertFalse(intent.hasExtra(MiuixDashboardActivity.EXTRA_RUN_SMART_CLEAN))
        assertFalse(CleanerNavigation.consumeLegacyRequest(intent))
    }

    @Test fun unknownProfilesCannotStartUnboundedNewModes() {
        assertEquals("safe", CleanerNavigation.normalizedProfile("all-delete"))
        for (profile in listOf("safe", "cache", "deep", "rules", "empty", "fragments", "corpses"))
            assertEquals(profile, CleanerNavigation.normalizedProfile(profile))
    }
}
