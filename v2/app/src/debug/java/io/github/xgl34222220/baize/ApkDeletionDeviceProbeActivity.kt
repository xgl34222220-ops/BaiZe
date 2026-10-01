package io.github.xgl34222220.baize

import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real MediaProvider mutations of newly-created fixtures, only on the disposable CI emulator. */
class ApkDeletionDeviceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val output = File(filesDir, "apk-deletion-probe").apply { mkdirs() }
            var fixtureRoot: File? = null
            val result = try {
                check(Process.myUid() >= 10_000 && ApkMediaStoreIndex.hasAllFilesAccess())
                @Suppress("DEPRECATION")
                val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "baize-apk-delete-probe-${UUID.randomUUID()}")
                check(!root.exists() && root.mkdirs())
                fixtureRoot = root
                val own = File(applicationInfo.sourceDir)
                val selected = own.copyTo(File(root, "selected.apk"))
                val kept = own.copyTo(File(root, "unselected.apk"))
                val changed = own.copyTo(File(root, "changed.apk"))
                val paths = arrayOf(selected.path, kept.path, changed.path)
                val latch = CountDownLatch(paths.size)
                MediaScannerConnection.scanFile(applicationContext, paths,
                    Array(paths.size) { "application/vnd.android.package-archive" }) { _, _ -> latch.countDown() }
                check(latch.await(25, TimeUnit.SECONDS)) { "Synthetic MediaStore scan timed out" }
                val indexed = ApkMediaStoreIndex.query(applicationContext)
                check(indexed.error == null) { indexed.error.orEmpty() }
                fun candidate(file: File) = indexed.candidates.single { it.path == file.path }.also {
                    check(it.identity != null) { "Physical scan identity unavailable" }
                }
                val target = candidate(selected)
                val modified = candidate(changed)
                val safe = ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), emptySet()))
                fun remove(item: IndexedApkCandidate, protection: ApkProtectionState) = ApkMediaStoreIndex.deleteIfUnchanged(
                    applicationContext, item.uri, item.path, item.bytes, item.modifiedSeconds, item.identity, { protection })
                check(remove(target, ApkProtectionState.Unknown("disconnected", safe.rules)) == ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE)
                check(selected.isFile && kept.isFile)
                check(remove(target, ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), setOf(root.path)))) == ApkIndexedDeleteResult.PROTECTED)
                check(selected.isFile && kept.isFile)
                // Leave MediaStore metadata untouched while replacing the actual file identity.
                val replacement = own.copyTo(File(root, "replacement.tmp"))
                check(replacement.renameTo(changed))
                check(remove(modified, safe) == ApkIndexedDeleteResult.CHANGED && changed.isFile)
                val deleted = remove(target, safe)
                check(deleted == ApkIndexedDeleteResult.DELETED) { "Conditional MediaStore delete returned $deleted" }
                check(!selected.exists() && kept.isFile && changed.isFile)
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("physicalIdentityCaptured", true).put("conditionalMediaStoreDelete", true)
                    .put("unknownProtectionPreserved", true).put("freshPathProtectionPreserved", true)
                    .put("replacedFilePreserved", true).put("unselectedPreserved", true)
                    .put("input", "three copies of this repository's debug APK")
                    .put("protectionSource", "explicit synthetic rule states; Root transport tested separately")
            } catch (error: Exception) {
                JSONObject().put("passed", false).put("error", error.javaClass.name).put("message", error.message.orEmpty())
            } finally {
                // This UUID namespace was created above by this probe; it contains no pre-existing files.
                fixtureRoot?.deleteRecursively()
            }
            File(output, "result.json").writeText(result.toString(2))
            launch(Dispatchers.Main) { finish() }
        }
    }
}
