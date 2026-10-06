package io.github.xgl34222220.baize

import android.content.ContextWrapper
import android.app.Application
import android.content.pm.PackageManager
import io.github.xgl34222220.baize.shizuku.shizukuCachePermission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShizukuCacheCapabilityTest {
    @Test fun legacyDeleteCachePermissionDoesNotAuthorizeTheShellOperation() {
        val context = object : ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == "android.permission.DELETE_CACHE_FILES") PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        assertFalse(shizukuCachePermission(context, 2000))
    }
    @Test fun capabilityChecksTheShellUidInsteadOfTheAppsBinderCaller() {
        val checked = mutableListOf<Pair<String, Int>>()
        val context = object : ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                checked += permission to uid
                return if (uid == 2000) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
        }
        assertTrue(shizukuCachePermission(context, 2000))
        assertEquals(listOf("android.permission.INTERNAL_DELETE_CACHE_FILES" to 2000), checked)
        assertFalse(shizukuCachePermission(context, 10123))
    }
    @Test fun rootCanProceedAndAnUnavailablePermissionCheckFailsClosedForShell() {
        val context = object : ContextWrapper(null) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = error("permission service unavailable")
        }
        assertTrue(shizukuCachePermission(context, 0))
        assertFalse(shizukuCachePermission(context, 2000))
    }
}
