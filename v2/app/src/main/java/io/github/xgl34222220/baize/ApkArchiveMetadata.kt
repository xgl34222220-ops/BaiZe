package io.github.xgl34222220.baize

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.DisplayMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

internal enum class ApkInstallStatus(val label: String) {
    INSTALLED("已安装"), OLDER("低于已装版本"), NEWER("高于已装版本"), NOT_INSTALLED("未安装"), UNKNOWN("未识别")
}
internal data class ApkArchiveInfo(val appName: String = "", val packageName: String = "", val version: String = "",
    val installedVersion: String = "", val status: ApkInstallStatus = ApkInstallStatus.UNKNOWN,
    val iconBitmap: Bitmap? = null, val parseStatus: ApkArchiveParseStatus = ApkArchiveParseStatus.PENDING,
    val failureReason: ApkArchiveFailure? = null)

internal object ApkArchiveMetadata {
    // Linux/bionic has supported this atomic open flag since before Android 8; only the
    // public OsConstants field was added in API 27. Keep it atomic on our API 26 minimum.
    // https://android.googlesource.com/platform/bionic/+/6861c6f/libc/include/fcntl.h
    private const val ANDROID_26_O_CLOEXEC = 0x80000
    internal fun closeOnExecFlag(): Int = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else ANDROID_26_O_CLOEXEC
    /**
     * A cooperative three-second budget, not a hard timeout: Android's synchronous parser cannot
     * be interrupted safely. Cancellation is checked between calls, then descriptors/resources
     * close before this coroutine can complete. No APK code, installer or Root service is used.
     */
    suspend fun inspect(context: Context, uri: String, path: String, expectedBytes: Long,
        expectedModifiedSeconds: Long): ApkArchiveInfo = withContext(Dispatchers.IO) {
        ApkArchivePolicy.inputFailure(path, expectedBytes)?.let { return@withContext unavailable(it) }
        val coroutine = currentCoroutineContext()
        val started = SystemClock.elapsedRealtime()
        val checkpoint = {
            coroutine.ensureActive()
            if (SystemClock.elapsedRealtime() - started >= ApkArchivePolicy.TIME_BUDGET_MS) {
                throw ApkArchiveReadException(ApkArchiveFailure.TIME_BUDGET)
            }
        }
        try {
            checkpoint()
            openSource(context, uri, path, expectedBytes, expectedModifiedSeconds).use { source ->
                checkpoint()
                val before = Os.fstat(source.fileDescriptor)
                verifySnapshot(before, expectedBytes, expectedModifiedSeconds)
                // A unique private alias keeps the file pinned and prevents reused /proc fd
                // numbers (or two versions of one package) from colliding in ResourcesManager.
                val directory = Files.createTempDirectory(context.cacheDir.toPath(), "apk-preview-").toFile()
                val alias = File(directory, "archive.apk")
                try {
                    Os.symlink("/proc/self/fd/${source.fd}", alias.path)
                    ApkArchivePolicy.checkZip(alias, checkpoint)
                    checkpoint()
                    val result = readArchive(AndroidApkArchivePlatform(context.packageManager), alias.path, checkpoint)
                    checkpoint()
                    val after = Os.fstat(source.fileDescriptor)
                    if (!sameFile(before, after)) throw ApkArchiveReadException(ApkArchiveFailure.FILE_CHANGED)
                    // Detect replacement of a readable path while the old descriptor remains open.
                    val pathNow = try { Os.stat(path) } catch (_: ErrnoException) { null }
                    if (pathNow != null && !sameFile(before, pathNow)) throw ApkArchiveReadException(ApkArchiveFailure.FILE_CHANGED)
                    result
                } finally {
                    alias.delete() // delete the link itself; never follow it or traverse APK paths
                    directory.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApkArchiveReadException) {
            unavailable(failure.reason)
        } catch (_: SecurityException) {
            unavailable(ApkArchiveFailure.INACCESSIBLE)
        } catch (_: OutOfMemoryError) {
            unavailable(ApkArchiveFailure.RESOURCE_LIMIT)
        } catch (_: Exception) {
            unavailable(ApkArchiveFailure.INVALID_ARCHIVE)
        }
    }

    private fun openSource(context: Context, uriString: String, path: String, bytes: Long,
        modified: Long): ParcelFileDescriptor {
        try {
            // O_NONBLOCK prevents a replaced FIFO from hanging before its regular-file check.
            val fd = Os.open(path, OsConstants.O_RDONLY or closeOnExecFlag() or
                OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            return try {
                verifySnapshot(Os.fstat(fd), bytes, modified)
                ParcelFileDescriptor.dup(fd)
            } finally { Os.close(fd) }
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EACCES && failure.errno != OsConstants.EPERM &&
                failure.errno != OsConstants.ENOENT) throw ApkArchiveReadException(ApkArchiveFailure.INACCESSIBLE)
        } catch (_: SecurityException) {
            // Scoped storage may permit only the MediaStore descriptor below.
        }
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content" || uri.authority != "media") throw ApkArchiveReadException(ApkArchiveFailure.INACCESSIBLE)
        verifyIndexedSource(context, uri, path, bytes, modified)
        return context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw ApkArchiveReadException(ApkArchiveFailure.INACCESSIBLE)
    }

    @Suppress("DEPRECATION")
    private fun verifyIndexedSource(context: Context, uri: Uri, path: String, bytes: Long, modified: Long) {
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val matches = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val pathColumn = cursor.getColumnIndex(projection[0])
            val sizeColumn = cursor.getColumnIndex(projection[1])
            val modifiedColumn = cursor.getColumnIndex(projection[2])
            if (pathColumn < 0 || sizeColumn < 0 || modifiedColumn < 0) return@use false
            cursor.getString(pathColumn) == path && ApkArchivePolicy.snapshotMatches(
                cursor.getLong(sizeColumn), cursor.getLong(modifiedColumn), bytes, modified)
        } ?: false
        if (!matches) throw ApkArchiveReadException(ApkArchiveFailure.FILE_CHANGED)
    }

    private fun verifySnapshot(stat: StructStat, bytes: Long, modified: Long) {
        if (!OsConstants.S_ISREG(stat.st_mode)) throw ApkArchiveReadException(ApkArchiveFailure.INACCESSIBLE)
        if (stat.st_size > ApkArchivePolicy.MAX_APK_BYTES) throw ApkArchiveReadException(ApkArchiveFailure.TOO_LARGE)
        if (!ApkArchivePolicy.snapshotMatches(stat.st_size, stat.st_mtime, bytes, modified)) {
            throw ApkArchiveReadException(ApkArchiveFailure.FILE_CHANGED)
        }
    }

    private fun sameFile(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

    internal fun readArchive(platform: ApkArchivePlatform, path: String, checkpoint: () -> Unit): ApkArchiveInfo {
        checkpoint()
        val archive = platform.archiveInfo(path) ?: throw ApkArchiveReadException(ApkArchiveFailure.INVALID_ARCHIVE)
        val original = archive.applicationInfo ?: throw ApkArchiveReadException(ApkArchiveFailure.INVALID_ARCHIVE)
        if (archive.packageName.isNullOrBlank() || archive.packageName == "system") {
            throw ApkArchiveReadException(ApkArchiveFailure.INVALID_ARCHIVE)
        }
        // Start clean: never inherit installed overlays, shared-library paths or split paths.
        val application = ApplicationInfo().apply {
            packageName = archive.packageName
            uid = original.uid
            labelRes = original.labelRes
            nonLocalizedLabel = original.nonLocalizedLabel
            icon = original.icon
            sourceDir = path
            publicSourceDir = path
        }
        checkpoint()
        var reason: ApkArchiveFailure? = null
        var name = cleanLabel(application.nonLocalizedLabel?.toString().orEmpty())
        var icon: Bitmap? = null
        try {
            val resources = platform.archiveResources(application)
            try {
                usePreviewDensity(resources)
                if (name.isEmpty() && application.labelRes != 0) {
                    name = try { cleanLabel(resources.getText(application.labelRes).toString()) }
                    catch (_: Resources.NotFoundException) { "" }
                }
                checkpoint()
                ZipFile(path).use { zip -> icon = ApkArchiveIcon.load(resources, application.icon, zip, checkpoint) }
            } finally {
                // This asset manager belongs to a unique temporary archive path, never the host
                // app or an installed package. Returned bitmaps no longer depend on it.
                resources.assets.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ApkArchiveReadException) {
            reason = failure.reason
        } catch (_: OutOfMemoryError) {
            reason = ApkArchiveFailure.RESOURCE_LIMIT
        } catch (_: Exception) {
            reason = ApkArchiveFailure.ICON_UNAVAILABLE
        }
        checkpoint()
        val installed = try { platform.installedInfo(archive.packageName) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: PackageManager.NameNotFoundException) { null }
        catch (_: Exception) {
            return ApkArchiveInfo(name, archive.packageName, versionLabel(archive),
                iconBitmap = icon, parseStatus = ApkArchiveParseStatus.PARTIAL,
                failureReason = reason ?: ApkArchiveFailure.INACCESSIBLE)
        }
        if (reason == null && name.isEmpty()) reason = ApkArchiveFailure.LABEL_UNAVAILABLE
        return ApkArchiveInfo(name, archive.packageName, versionLabel(archive),
            installed?.let(::versionLabel).orEmpty(), compareVersions(versionCode(archive), installed?.let(::versionCode)),
            icon, if (reason == null) ApkArchiveParseStatus.PARSED else ApkArchiveParseStatus.PARTIAL, reason)
    }

    @Suppress("DEPRECATION")
    private fun usePreviewDensity(resources: Resources) {
        val metrics = DisplayMetrics().apply {
            setTo(resources.displayMetrics)
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
            density = 1f
            scaledDensity = 1f
        }
        resources.updateConfiguration(Configuration(resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }, metrics)
    }

    internal fun cleanLabel(value: String): String = value.take(256)
        .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }.trim()

    private fun unavailable(reason: ApkArchiveFailure) = ApkArchiveInfo(
        parseStatus = if (reason == ApkArchiveFailure.UNSUPPORTED_FORMAT) ApkArchiveParseStatus.UNSUPPORTED else ApkArchiveParseStatus.FAILED,
        failureReason = reason)

    private fun versionLabel(info: PackageInfo): String = cleanLabel(info.versionName.orEmpty())
        .ifBlank { versionCode(info).toString() }

    fun compareVersions(archive: Long, installed: Long?): ApkInstallStatus = when {
        installed == null -> ApkInstallStatus.NOT_INSTALLED
        archive < installed -> ApkInstallStatus.OLDER
        archive > installed -> ApkInstallStatus.NEWER
        else -> ApkInstallStatus.INSTALLED
    }
    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

internal interface ApkArchivePlatform {
    fun archiveInfo(path: String): PackageInfo?
    fun archiveResources(application: ApplicationInfo): Resources
    fun installedInfo(packageName: String): PackageInfo?
}

@Suppress("DEPRECATION")
private class AndroidApkArchivePlatform(private val pm: PackageManager) : ApkArchivePlatform {
    override fun archiveInfo(path: String): PackageInfo? = pm.getPackageArchiveInfo(path, 0)
    override fun archiveResources(application: ApplicationInfo): Resources = pm.getResourcesForApplication(application)
    override fun installedInfo(packageName: String): PackageInfo? = pm.getPackageInfo(packageName, 0)
}
