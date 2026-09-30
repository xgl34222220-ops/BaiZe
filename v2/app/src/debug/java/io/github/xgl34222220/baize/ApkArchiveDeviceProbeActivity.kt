package io.github.xgl34222220.baize

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Ordinary App-UID check of this repository's own APK, present only in debug builds. */
class ApkArchiveDeviceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))) { finish(); return }
        val output = File(filesDir, "apk-archive-probe").apply { mkdirs() }
        lifecycleScope.launch {
            val result = try {
                check(Process.myUid() >= 10_000) { "Probe must use the ordinary application UID" }
                val existingAliases = cacheDir.listFiles().orEmpty().filter { it.name.startsWith("apk-preview-") }.map { it.name }.toSet()
                val ownApk = File(applicationInfo.sourceDir)
                val info = ApkArchiveMetadata.inspect(applicationContext, "", ownApk.path,
                    ownApk.length(), ownApk.lastModified() / 1000)
                check(info.parseStatus == ApkArchiveParseStatus.PARSED) { "${info.parseStatus}:${info.failureReason}" }
                check(info.appName.isNotBlank() && info.packageName == packageName)
                @Suppress("DEPRECATION")
                val ownVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                check(info.version == ownVersion)
                val bitmap = requireNotNull(info.iconBitmap) { "Archive icon missing" }
                check(bitmap.width in 1..192 && bitmap.height in 1..192)
                File(output, "archive-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // A second read exercises a different private alias for the same live archive.
                val again = ApkArchiveMetadata.inspect(applicationContext, "", ownApk.path,
                    ownApk.length(), ownApk.lastModified() / 1000)
                check(again.parseStatus == ApkArchiveParseStatus.PARSED && again.iconBitmap != null)
                check(again.packageName == info.packageName && again.appName == info.appName)
                check(bitmap.sameAs(again.iconBitmap)) { "Repeated archive artwork changed" }
                check(cacheDir.listFiles().orEmpty().filter { it.name.startsWith("apk-preview-") }.map { it.name }.toSet() == existingAliases) {
                    "Private archive aliases were not cleaned up"
                }
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("archiveLabel", info.appName).put("packageMatches", true).put("versionMatches", true)
                    .put("archiveIconDecoded", true).put("iconWidth", bitmap.width).put("iconHeight", bitmap.height)
                    .put("repeatReadPassed", true).put("repeatIconPixelsEqual", true).put("privateAliasesReleased", true)
                    .put("input", "this repository debug APK only")
            } catch (error: Exception) {
                JSONObject().put("passed", false).put("error", error.javaClass.name)
                    .put("message", error.message.orEmpty().take(300))
            }
            File(output, "result.json").writeText(result.toString(2))
            finish()
        }
    }
}
