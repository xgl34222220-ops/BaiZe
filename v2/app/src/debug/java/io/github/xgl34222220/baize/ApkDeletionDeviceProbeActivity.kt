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
            var stage = "permissions"
            val legacyPreferences = getSharedPreferences("baize_v2", MODE_PRIVATE)
            val originalLegacyPaths = legacyPreferences.getStringSet("path_whitelist", null)?.toSet()
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
                val legacyProtected = own.copyTo(File(root, "legacy-protected.apk"))
                val paths = arrayOf(selected.path, kept.path, changed.path, legacyProtected.path)
                val latch = CountDownLatch(paths.size)
                MediaScannerConnection.scanFile(applicationContext, paths,
                    Array(paths.size) { "application/vnd.android.package-archive" }) { _, _ -> latch.countDown() }
                check(latch.await(25, TimeUnit.SECONDS)) { "Synthetic MediaStore scan timed out" }
                val indexed = ApkMediaStoreIndex.query(applicationContext)
                check(indexed.error == null) { indexed.error.orEmpty() }
                fun candidate(file: File) = indexed.candidates.single { it.path == file.path }.also {
                    check(it.identity != null) { "Physical scan identity unavailable" }
                }
                stage = "indexed-identities"
                val target = candidate(selected)
                val modified = candidate(changed)
                val safe = ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), emptySet()))
                val reviewStarted = android.os.SystemClock.elapsedRealtime()
                val contentProofs = indexed.candidates.filter { it.path.startsWith(root.path + "/") }.associate { item ->
                    item.uri to IndexedContentReview.capture(checkNotNull(item.identity), ApkDeletionGuard.forContext(applicationContext)) }
                val reviewElapsed = android.os.SystemClock.elapsedRealtime() - reviewStarted
                var lastMutation = JSONObject()
                fun remove(item: IndexedApkCandidate, protection: ApkProtectionState) = ApkMediaStoreIndex.deleteIfUnchanged(
                    applicationContext, item.uri, item.path, item.bytes, item.modifiedSeconds, item.identity, { protection },
                    onFailure = { lastMutation.put("error", it.javaClass.name).put("message", it.message.orEmpty()) },
                    onMutationResult = { rows, missing -> lastMutation.put("providerRows", rows).put("physicalAbsenceConfirmed", missing) },
                    contentProof = contentProofs[item.uri])
                stage = "protection-rejection"
                check(remove(target, ApkProtectionState.Unknown("disconnected", safe.rules)) == ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE)
                check(selected.isFile && kept.isFile)
                check(remove(target, ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), setOf(root.path)))) == ApkIndexedDeleteResult.PROTECTED)
                check(selected.isFile && kept.isFile)
                // Leave MediaStore metadata untouched while replacing the actual file identity.
                val replacement = own.copyTo(File(root, "replacement.tmp"))
                check(replacement.renameTo(changed))
                check(remove(modified, safe) == ApkIndexedDeleteResult.CHANGED && changed.isFile)
                stage = "selected-delete"
                lastMutation = JSONObject()
                val beforeSelected = JSONObject(ApkFileReadDiagnostics.collect(applicationContext, target.uri, target.path, null, target.identity))
                val deleted = remove(target, safe)
                val selectedEvidence = recordIndexedDeletionProbe(applicationContext, output, "selected-delete-evidence.json",
                    target.uri, target.path, target.identity, deleted, beforeSelected, lastMutation)
                check(deleted == ApkIndexedDeleteResult.DELETED) { selectedEvidence.toString() }
                check(!selected.exists() && kept.isFile && changed.isFile)
                stage = "single-file-diagnostic"
                val diagnostic = JSONObject(ApkFileReadDiagnostics.collect(applicationContext, target.uri, target.path, null, target.identity))
                File(output, "missing-file-diagnostic.json").writeText(diagnostic.toString(2))
                check(diagnostic.getJSONObject("appPathStat").getInt("errno") == 2) { "Missing file stat: $diagnostic" }
                check(diagnostic.isNull("currentAppIdentity") && !diagnostic.isNull("scanIdentity")) { "Current/scan identity: $diagnostic" }
                check(!diagnostic.getJSONObject("index").getBoolean("exists")) { "Deleted index row: $diagnostic" }
                check(!diagnostic.getJSONObject("mediaStoreFd").getBoolean("ok")) { "Deleted index descriptor: $diagnostic" }
                stage = "legacy-protection-removal"
                // Exercise real App preferences and MediaProvider after explicitly removing one legacy rule.
                check(legacyPreferences.edit().putStringSet("path_whitelist", originalLegacyPaths.orEmpty() + legacyProtected.path).commit())
                val emptyRules = ApkProtectionRules(emptySet(), emptySet())
                val manager = WhitelistManagerRepository(applicationContext, object : WhitelistProtectionAccess {
                    override fun read() = emptyRules
                    override fun updatePackages(added: Set<String>, removed: Set<String>) = error("No Root rule mutation expected")
                    override fun addPath(path: String) = error("No Root rule mutation expected")
                    override fun removePath(path: String) = error("No Root rule mutation expected")
                }, Environment.getExternalStorageDirectory().canonicalPath)
                val legacyTarget = candidate(legacyProtected)
                val before = manager.read()
                check(before.pathEntries.any { it.path == legacyProtected.path && it.localRecords.isNotEmpty() })
                check(remove(legacyTarget, ApkProtectionState.KnownRoot(before.effective)) == ApkIndexedDeleteResult.PROTECTED)
                check(legacyProtected.isFile)
                val after = manager.removePath(legacyProtected.path)
                lastMutation = JSONObject()
                val beforeLegacy = JSONObject(ApkFileReadDiagnostics.collect(applicationContext, legacyTarget.uri, legacyTarget.path, null, legacyTarget.identity))
                val legacyResult = remove(legacyTarget, ApkProtectionState.KnownRoot(after.effective))
                val afterLegacy = JSONObject(ApkFileReadDiagnostics.collect(applicationContext, legacyTarget.uri, legacyTarget.path, null, legacyTarget.identity))
                val legacyEvidence = JSONObject().put("outcome", legacyResult.name).put("mutation", lastMutation)
                    .put("before", beforeLegacy).put("after", afterLegacy)
                File(output, "legacy-delete-evidence.json").writeText(legacyEvidence.toString(2))
                check(legacyResult == ApkIndexedDeleteResult.DELETED) { legacyEvidence.toString() }
                check(!legacyProtected.exists() && kept.isFile && changed.isFile)
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("physicalIdentityCaptured", true).put("conditionalMediaStoreDelete", true)
                    .put("selectedContentReviewAndRecheck", true).put("contentReviewBytes", contentProofs.values.sumOf { it.identity.bytes })
                    .put("contentReviewMs", reviewElapsed)
                    .put("unknownProtectionPreserved", true).put("freshPathProtectionPreserved", true)
                    .put("replacedFilePreserved", true).put("unselectedPreserved", true)
                    .put("legacyProtectionVisibleRemovableAndCleanable", true)
                    .put("singleFileDiagnosticDistinguishesMissingFileAndIndex", true)
                    .put("input", "four copies of this repository's debug APK")
                    .put("protectionSource", "explicit synthetic rule states; Root transport tested separately")
            } catch (error: Exception) {
                JSONObject().put("passed", false).put("stage", stage).put("error", error.javaClass.name)
                    .put("message", error.message.orEmpty()).put("stack", error.stackTraceToString())
            } finally {
                legacyPreferences.edit().putStringSet("path_whitelist", originalLegacyPaths).commit()
                // This UUID namespace was created above by this probe; it contains no pre-existing files.
                fixtureRoot?.deleteRecursively()
            }
            File(output, "result.json").writeText(result.toString(2))
            launch(Dispatchers.Main) { finish() }
        }
    }
}
