package io.github.xgl34222220.baize

import android.Manifest
import android.app.Application
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

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
        ShadowEnvironment.setIsExternalStorageManager(false)
        assertFalse(SharedStorageAccess.granted(context))
        ShadowEnvironment.setIsExternalStorageManager(true)
        assertTrue(SharedStorageAccess.granted(context))
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, SharedStorageAccess.settings(context).action)
    }
}
