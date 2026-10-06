package io.github.xgl34222220.baize

import android.Manifest
import android.app.Application
import android.os.Environment
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedStorageAccessTest {
    @Test @Config(sdk = [28, 29]) fun aSuccessfulRuntimeGrantClearsThePriorDenialFlag() {
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java)
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("storage-permission", 0)
        prefs.edit().putBoolean("denied", true).commit()
        var resumed = 0
        val request = StoragePermissionRequest(activity) { resumed++ }
        controller.setup()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(*SharedStorageAccess.legacyPermissions)
        shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE, true)
        request.launch()
        val submitted = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(SharedStorageAccess.legacyPermissions, submitted.requestedPermissions)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*SharedStorageAccess.legacyPermissions)
        activity.onRequestPermissionsResult(submitted.requestCode, submitted.requestedPermissions,
            IntArray(submitted.requestedPermissions.size) { android.content.pm.PackageManager.PERMISSION_GRANTED })
        assertEquals(1, resumed)
        assertFalse(prefs.getBoolean("denied", false))
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [28, 29]) fun alreadyGrantedPermissionsAlsoClearAStaleDenialWithoutRequestingMoreAccess() {
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java)
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("storage-permission", 0)
        prefs.edit().putBoolean("denied", true).commit()
        var resumed = 0
        val request = StoragePermissionRequest(activity) { resumed++ }
        controller.setup()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*SharedStorageAccess.legacyPermissions)
        request.launch()
        assertEquals(1, resumed)
        assertFalse(prefs.getBoolean("denied", false))
        assertNull(shadowOf(activity).lastRequestedPermission)
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [28, 29]) fun observingASettingsGrantDoesNotMisrouteTheNextRevocation() {
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java)
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("storage-permission", 0)
        prefs.edit().putBoolean("denied", true).commit()
        val request = StoragePermissionRequest(activity) {}
        controller.setup()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*SharedStorageAccess.legacyPermissions)
        // StorageTools and APK scan call this check on resume after the settings page.
        assertTrue(SharedStorageAccess.granted(activity))
        assertFalse(prefs.getBoolean("denied", false))
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(*SharedStorageAccess.legacyPermissions)
        shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE, false)
        shadowOf(activity.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE, false)
        request.launch()
        assertArrayEquals(SharedStorageAccess.legacyPermissions, shadowOf(activity).lastRequestedPermission.requestedPermissions)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(activity).nextStartedActivity.action)
        controller.pause().stop().destroy()
    }

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
    @Test @Config(sdk = [35], shadows = [StorageGrantEnvironmentShadow::class]) fun modernStorageUsesSpecialGrantRatherThanLegacyPermissions() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(*SharedStorageAccess.legacyPermissions)
        StorageGrantEnvironmentShadow.specialGrant = false
        assertFalse(SharedStorageAccess.granted(context))
        StorageGrantEnvironmentShadow.specialGrant = true
        assertTrue(SharedStorageAccess.granted(context))
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, SharedStorageAccess.settings(context).action)
    }
}

/** Robolectric has no real storage volume. Device tests exercise actual AppOps on API 36. */
@Implements(Environment::class)
class StorageGrantEnvironmentShadow : ShadowEnvironment() {
    companion object {
        @JvmField var specialGrant = false
        @JvmStatic @Implementation(minSdk = 30)
        fun isExternalStorageManager(): Boolean = specialGrant
    }
}
