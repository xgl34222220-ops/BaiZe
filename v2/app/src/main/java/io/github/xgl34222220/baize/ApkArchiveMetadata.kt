package io.github.xgl34222220.baize

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

internal enum class ApkInstallStatus(val label: String) {
    INSTALLED("已安装"), OLDER("低于已装版本"), NEWER("高于已装版本"), NOT_INSTALLED("未安装"), UNKNOWN("未识别")
}
internal data class ApkArchiveInfo(val appName: String = "", val packageName: String = "", val version: String = "",
    val installedVersion: String = "", val status: ApkInstallStatus = ApkInstallStatus.UNKNOWN)

internal object ApkArchiveMetadata {
    @Suppress("DEPRECATION")
    fun inspect(context: Context, path: String): ApkArchiveInfo {
        if (!path.endsWith(".apk", true)) return ApkArchiveInfo()
        return try {
            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(path, 0) ?: return ApkArchiveInfo()
            val application = archive.applicationInfo
            application?.sourceDir = path
            application?.publicSourceDir = path
            val installed = try { pm.getPackageInfo(archive.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
            ApkArchiveInfo(application?.let { pm.getApplicationLabel(it).toString() }.orEmpty(), archive.packageName,
                archive.versionName.orEmpty(), installed?.versionName.orEmpty(), compareVersions(versionCode(archive), installed?.let(::versionCode)))
        } catch (_: Exception) { ApkArchiveInfo() }
    }
    fun compareVersions(archive: Long, installed: Long?): ApkInstallStatus = when {
        installed == null -> ApkInstallStatus.NOT_INSTALLED
        archive < installed -> ApkInstallStatus.OLDER
        archive > installed -> ApkInstallStatus.NEWER
        else -> ApkInstallStatus.INSTALLED
    }
    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
