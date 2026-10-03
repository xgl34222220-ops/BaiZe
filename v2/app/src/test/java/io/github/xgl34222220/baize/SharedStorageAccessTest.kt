package io.github.xgl34222220.baize

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.os.Process
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedStorageAccessTest {
    @Test @Config(sdk = [28, 29]) fun legacyRequiresBothPermissionsAndRoutesToAppSettings() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(*SharedStorageAccess.legacyPermissions)
        assertFalse(SharedStorageAccess.granted(context)); assertFalse(ApkMediaStoreIndex.hasAllFilesAccess(context))
        assertEquals("all_files_access_required", ApkMediaStoreIndex.query(context).error)
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertFalse(SharedStorageAccess.granted(context))
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertTrue(SharedStorageAccess.granted(context)); assertTrue(StorageMediaRepository.hasAccess(context))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, SharedStorageAccess.settings(context).action)
    }
    @Test @Config(sdk = [35]) fun modernStorageUsesSpecialGrantRatherThanLegacyPermissions() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(*SharedStorageAccess.legacyPermissions)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode("android:manage_external_storage", Process.myUid(), context.packageName, AppOpsManager.MODE_ERRORED)
        assertFalse(SharedStorageAccess.granted(context))
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode("android:manage_external_storage", Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(SharedStorageAccess.granted(context))
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, SharedStorageAccess.settings(context).action)
    }
}
