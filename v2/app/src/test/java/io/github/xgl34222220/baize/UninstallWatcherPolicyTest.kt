package io.github.xgl34222220.baize

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class UninstallWatcherPolicyTest {
    private val self = "io.github.xgl34222220.baize"

    @Test fun onlyAFullRemovalOfAnotherValidPackageNotifies() {
        assertTrue(UninstallWatcherPolicy.shouldNotify(Intent.ACTION_PACKAGE_FULLY_REMOVED, "com.example.app", false, true, self))
        // Updates (replacing) and the plain REMOVED broadcast never prompt a leftover scan.
        assertFalse(UninstallWatcherPolicy.shouldNotify(Intent.ACTION_PACKAGE_FULLY_REMOVED, "com.example.app", true, true, self))
        assertFalse(UninstallWatcherPolicy.shouldNotify(Intent.ACTION_PACKAGE_REMOVED, "com.example.app", false, true, self))
        assertFalse(UninstallWatcherPolicy.shouldNotify(null, "com.example.app", false, true, self))
    }

    @Test fun disabledSettingSelfAndMalformedNamesAreIgnored() {
        assertFalse(UninstallWatcherPolicy.shouldNotify(Intent.ACTION_PACKAGE_FULLY_REMOVED, "com.example.app", false, false, self))
        assertFalse(UninstallWatcherPolicy.shouldNotify(Intent.ACTION_PACKAGE_FULLY_REMOVED, self, false, true, self))
        listOf(null, "", "nodot", "../evil", "com.example/../x", "com.exa mple", "1com.example").forEach {
            assertFalse("$it", UninstallWatcherPolicy.shouldNotify(Intent.ACTION_PACKAGE_FULLY_REMOVED, it, false, true, self))
        }
    }

    @Test fun notificationIdIsStablePerPackage() {
        assertEquals(UninstallWatcherPolicy.notificationId("com.a.b"), UninstallWatcherPolicy.notificationId("com.a.b"))
        assertTrue(UninstallWatcherPolicy.notificationId("com.a.b") in 0x5100..0x60FF)
    }
}
