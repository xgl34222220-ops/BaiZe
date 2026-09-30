package io.github.xgl34222220.baize

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic package metadata/resources only. Real archive parsing has a separate device smoke. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApkArchiveMetadataTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    @Config(sdk = [26])
    fun android26KeepsAtomicCloseOnExecWithoutAccessingTheNewerSdkField() {
        assertEquals(0x80000, ApkArchiveMetadata.closeOnExecFlag())
    }

    @Test fun samePackageArchivesUseTheirOwnNameVersionAndIcon() {
        val first = fixture("first.apk")
        val second = fixture("second.apk")
        val platform = FixturePlatform(mapOf(first.path to ("第一版" to Color.RED), second.path to ("第二版" to Color.BLUE)))
        val a = ApkArchiveMetadata.readArchive(platform, first.path) {}
        val b = ApkArchiveMetadata.readArchive(platform, second.path) {}
        assertEquals("第一版", a.appName)
        assertEquals("第二版", b.appName)
        assertEquals("1.2", a.version)
        assertEquals("8.0", a.installedVersion)
        assertEquals(ApkInstallStatus.OLDER, a.status)
        assertEquals(ApkArchiveParseStatus.PARSED, a.parseStatus)
        assertEquals(Color.RED, requireNotNull(a.iconBitmap).getPixel(96, 96))
        assertEquals(Color.BLUE, requireNotNull(b.iconBitmap).getPixel(96, 96))
        assertEquals(listOf(first.path, second.path), platform.resourcePaths)
        assertEquals(192, a.iconBitmap.width)
        assertEquals(192, a.iconBitmap.height)
    }

    @Test fun absentArchiveIconStaysEmptyAndDoesNotUseInstalledIcon() {
        val apk = fixture("missing-icon.apk")
        val platform = FixturePlatform(mapOf(apk.path to ("归档名称" to Color.RED)), iconId = 0)
        val result = ApkArchiveMetadata.readArchive(platform, apk.path) {}
        assertEquals("归档名称", result.appName)
        assertNull(result.iconBitmap)
        assertEquals(ApkArchiveParseStatus.PARTIAL, result.parseStatus)
        assertEquals(ApkArchiveFailure.ICON_UNAVAILABLE, result.failureReason)
    }

    @Test fun anArchiveWithoutVersionNameStillShowsItsRealVersionCode() {
        val apk = fixture("no-version-name.apk")
        val base = FixturePlatform(mapOf(apk.path to ("归档应用" to Color.BLUE)))
        val platform = object : ApkArchivePlatform by base {
            override fun archiveInfo(path: String) = base.archiveInfo(path).apply {
                versionName = null
                @Suppress("DEPRECATION")
                versionCode = 42
            }
        }
        val result = ApkArchiveMetadata.readArchive(platform, apk.path) {}
        assertEquals("42", result.version)
        assertEquals(ApkInstallStatus.NEWER, result.status)
    }

    @Test fun noArchiveLabelDoesNotInventAnApplicationNameFromFileOrPackage() {
        val apk = fixture("convincing-app-name.apk")
        val platform = FixturePlatform(mapOf(apk.path to ("" to Color.RED)))
        val result = ApkArchiveMetadata.readArchive(platform, apk.path) {}
        assertEquals("", result.appName)
        assertEquals(ApkArchiveParseStatus.PARTIAL, result.parseStatus)
        assertEquals(ApkArchiveFailure.LABEL_UNAVAILABLE, result.failureReason)
    }

    @Test fun cancellationFromAnyPlatformStagePropagates() {
        val apk = fixture("cancelled.apk")
        val cancelled = CancellationException("synthetic lifecycle cancellation")
        for (stage in 0..2) {
            val platform = object : ApkArchivePlatform {
                override fun archiveInfo(path: String): PackageInfo {
                    if (stage == 0) throw cancelled
                    return packageInfo()
                }
                override fun archiveResources(application: ApplicationInfo): Resources {
                    if (stage == 1) throw cancelled
                    return FixtureResources("fixture", Color.RED)
                }
                override fun installedInfo(packageName: String): PackageInfo? = throw cancelled
            }
            assertSame(cancelled, assertThrows(CancellationException::class.java) {
                ApkArchiveMetadata.readArchive(platform, apk.path) {}
            })
        }
    }

    @Test fun largeDrawableRendersIntoAFixedBoundedBitmap() {
        val drawable = object : Drawable() {
            override fun draw(canvas: Canvas) { canvas.drawColor(Color.MAGENTA) }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
            override fun getIntrinsicWidth() = 100_000
            override fun getIntrinsicHeight() = 80_000
        }
        val bitmap = ApkArchiveIcon.render(drawable)
        assertEquals(192, bitmap.width)
        assertEquals(192, bitmap.height)
        assertTrue(bitmap.allocationByteCount <= 192 * 192 * 4)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    private class FixturePlatform(private val values: Map<String, Pair<String, Int>>, private val iconId: Int = ICON) : ApkArchivePlatform {
        val resourcePaths = mutableListOf<String>()
        override fun archiveInfo(path: String): PackageInfo = packageInfo().apply {
            applicationInfo!!.icon = iconId
            // Deliberately poison inherited paths: the reader must create a clean archive info.
            applicationInfo!!.splitSourceDirs = arrayOf("/not-an-archive/installed-split.apk")
            applicationInfo!!.sharedLibraryFiles = arrayOf("/not-an-archive/shared.apk")
        }
        override fun archiveResources(application: ApplicationInfo): Resources {
            assertEquals(application.sourceDir, application.publicSourceDir)
            assertNull(application.splitSourceDirs)
            assertNull(application.sharedLibraryFiles)
            resourcePaths += application.sourceDir
            val (label, color) = values.getValue(application.sourceDir)
            return FixtureResources(label, color)
        }
        override fun installedInfo(packageName: String): PackageInfo = packageInfo().apply {
            versionName = "8.0"
            @Suppress("DEPRECATION")
            versionCode = 8
            applicationInfo!!.nonLocalizedLabel = "已安装应用名称，禁止冒充归档"
            applicationInfo!!.icon = android.R.drawable.ic_dialog_alert
        }
    }

    @Suppress("DEPRECATION")
    private class FixtureResources(private val label: String, private val color: Int) : Resources(
        ReflectionHelpers.callConstructor(AssetManager::class.java), DisplayMetrics(), Configuration()) {
        override fun getText(id: Int): CharSequence { assertEquals(LABEL, id); return label }
        override fun getValueForDensity(id: Int, density: Int, outValue: TypedValue, resolveRefs: Boolean) {
            assertEquals(ICON, id)
            outValue.type = TypedValue.TYPE_INT_COLOR_ARGB8
            outValue.data = color
            outValue.string = null
        }
        override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable {
            assertEquals(ICON, id)
            return ColorDrawable(color)
        }
    }

    private fun fixture(name: String): File = folder.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
    }

    companion object {
        private const val LABEL = 0x7f010001
        private const val ICON = 0x7f020001
        @Suppress("DEPRECATION")
        private fun packageInfo() = PackageInfo().apply {
            packageName = "synthetic.preview.example"
            versionName = "1.2"
            versionCode = 1
            applicationInfo = ApplicationInfo().apply { packageName = "synthetic.preview.example"; labelRes = LABEL; icon = ICON }
        }
    }
}
