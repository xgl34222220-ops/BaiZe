package io.github.xgl34222220.baize

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** 删除重复页面后，旧组件名必须继续落到统一页面，而不是崩溃或打开空白页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyEntryRedirectsTest {
    @Test fun legacyCacheEntryRedirectsToWorkbenchCacheProfile() {
        val controller = Robolectric.buildActivity(CacheActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        val destination = shadowOf(controller.get()).nextStartedActivity
        assertEquals(ScanWorkbenchActivity::class.java.name, destination.component!!.className)
        assertEquals("cache", destination.getStringExtra(ScanWorkbenchActivity.EXTRA_PROFILE))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.destroy()
    }

    @Test fun everyLegacyProfileKeepsItsWorkbenchCategory() {
        for (profile in listOf("empty", "rules", "fragments", "deep", "corpses")) {
            val intent = android.content.Intent().putExtra(ProfileActivity.EXTRA_PROFILE, profile)
            val controller = Robolectric.buildActivity(ProfileActivity::class.java, intent).create()
            assertTrue(controller.get().isFinishing)
            val destination = shadowOf(controller.get()).nextStartedActivity
            assertEquals(ScanWorkbenchActivity::class.java.name, destination.component!!.className)
            assertEquals(profile, destination.getStringExtra(ScanWorkbenchActivity.EXTRA_PROFILE))
            controller.destroy()
        }
    }

    @Test fun unknownLegacyProfileClosesWithoutOpeningAnotherPage() {
        for (profile in listOf(null, "", "all-delete")) {
            val intent = android.content.Intent().apply { profile?.let { putExtra(ProfileActivity.EXTRA_PROFILE, it) } }
            val controller = Robolectric.buildActivity(ProfileActivity::class.java, intent).create()
            assertTrue(controller.get().isFinishing)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            controller.destroy()
        }
        assertNull(LegacyEntryRedirects.profileTarget("cache"))
    }

    @Test fun removedSmartScanPagesAreAliasesOfTheResumableFlow() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("v2/app/src/main/AndroidManifest.xml"))
            .first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val android = "http://schemas.android.com/apk/res/android"
        val aliases = document.getElementsByTagName("activity-alias").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .associate { it.getAttributeNS(android, "name") to it.getAttributeNS(android, "targetActivity") }
        }
        val activities = document.getElementsByTagName("activity").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }
        }
        for (legacy in LegacyEntryRedirects.SMART_SCAN_ALIASES) {
            assertEquals(legacy, ".ResumableSmartScanActivity", aliases[legacy])
            assertFalse("$legacy must not be a second page", legacy in activities)
        }
        assertTrue(".ResumableSmartScanActivity" in activities)
        // 重定向入口仍需注册，否则旧 Intent 会抛 ActivityNotFoundException。
        assertTrue(".CacheActivity" in activities)
        assertTrue(".ProfileActivity" in activities)
    }
}
