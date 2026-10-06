package io.github.xgl34222220.baize

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.root.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** No deletion: actual shared-storage timestamps plus explicitly forced identity collisions. */
class StorageCollisionDeviceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.lowercase().contains("sdk"))) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val output = File(filesDir, "storage-collision-probe").apply { mkdirs() }
            var phase = "access"
            val result = runCatching {
                check(Process.myUid() >= 10000 && StorageMediaRepository.hasAccess(this@StorageCollisionDeviceProbeActivity))
                phase = "shared-fixtures"
                val timestampSetters = ArrayList<String>()
                @Suppress("DEPRECATION")
                val owned = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "baize-collision-owned-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val files = listOf("first.bin", "second.bin").mapIndexed { index, name -> File(owned, name).apply {
                    writeBytes(ByteArray(128 * 1024))
                    timestampSetters += controlledTimestamp(this, output, index + 1)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, path); put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.SIZE, length()); put(MediaStore.MediaColumns.DATE_MODIFIED, lastModified() / 1000)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    }
                    checkNotNull(contentResolver.insert(MediaStore.Files.getContentUri(if (Build.VERSION.SDK_INT >= 29) "external_primary" else "external"), values))
                } }
                val before = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(owned.path + "/") }
                check(before.size == 2) { "Expected two owned index rows, observed ${before.size}" }
                phase = "original-duplicate-proof"
                val cache = StorageDigestCache()
                val originalGroup = StorageMediaRepository.findDuplicates(applicationContext, before, cache = cache).groups.single()
                val guard = ApkDeletionGuard.forContext(applicationContext)
                val originalRecord = before.single { it.path == files[0].path }
                val aliasPath = files[0].path.replace("/storage/emulated/0/", "/sdcard/")
                val aliasIdentity = guard.capture(aliasPath)
                val aliasObserved = aliasPath != files[0].path && aliasIdentity != null &&
                    aliasIdentity.sameStorageObject(checkNotNull(originalRecord.identity))
                val aliasChecks = JSONObject().put("realSharedAliasObserved", aliasObserved)
                    .put("aliasPath", aliasPath)
                if (aliasObserved) {
                    val aliasRecord = originalRecord.copy(id = 900001, uri = "content://media/external/file/900001",
                        path = aliasPath, identity = aliasIdentity)
                    check(StorageMediaRepository.findDuplicates(applicationContext, listOf(originalRecord, aliasRecord)).groups.isEmpty())
                    check(!StorageMediaRepository.duplicateStillSafe(applicationContext, originalRecord,
                        originalGroup.copy(records = listOf(originalRecord, aliasRecord)),
                        setOf(originalRecord.uri), StorageScanControl()))
                    aliasChecks.put("aliasScanRejectsDuplicate", true).put("aliasCannotAuthorizeSurvivor", true)
                } else {
                    aliasChecks.put("aliasScanRejectsDuplicate", JSONObject.NULL)
                        .put("aliasCannotAuthorizeSurvivor", JSONObject.NULL)
                        .put("unverifiedReason", "A supported shared alias identity was not observed")
                }
                val originalIdentity = checkNotNull(guard.capture(files[1].path))
                val originalProof = IndexedContentReview.capture(originalIdentity, guard)
                val changed = ByteArray(128 * 1024).apply { this[lastIndex] = 1 }
                phase = "same-size-content-mutation"
                files[1].writeBytes(changed)
                timestampSetters += controlledTimestamp(files[1], output, 3)
                val after = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(owned.path + "/") }
                check(after.size == 2) { "Expected two owned index rows after mutation, observed ${after.size}" }
                val modifiedBefore = before.single { it.path == files[1].path }.modifiedSeconds
                val modifiedAfter = after.single { it.path == files[1].path }.modifiedSeconds
                check(files.all { it.lastModified() == 1500000000000L })
                check(modifiedBefore == modifiedAfter) { "MediaStore timestamp did not collide" }
                check(StorageMediaRepository.findDuplicates(applicationContext, after, cache = cache).groups.isEmpty())
                val newIdentity = checkNotNull(guard.capture(files[1].path))
                check(!IndexedContentReview.matches(originalProof, originalIdentity, guard) { false })
                // Explicitly forced tuple collision: keep the old content proof but supply today's identity.
                // This tests the production SHA check on actual shared files, not an observed OS full-tuple collision.
                check(!IndexedContentReview.matches(originalProof.copy(identity = newIdentity), newIdentity, guard) { false })
                val finalProof = IndexedContentReview.capture(newIdentity, guard)
                check(finalProof.sha256 != originalProof.sha256 && files[1].readBytes().contentEquals(changed))
                // The old group's digest cannot authorize today's selected file even if
                // the caller supplies today's observed metadata. No mutation API is called.
                val currentSelected = after.single { it.path == files[1].path }
                check(!StorageMediaRepository.duplicateStillSafe(applicationContext, currentSelected,
                    originalGroup.copy(records = after), setOf(currentSelected.uri), StorageScanControl()))
                File(output, "shared-observations.json").writeText(JSONObject()
                    .put("appUid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("mtimeSetters", org.json.JSONArray(timestampSetters))
                    .put("originalIdentity", originalIdentity.json()).put("newIdentity", newIdentity.json()).toString(2))
                phase = "private-read-only-boundaries"
                val privateChecks = privateReadOnlyBoundaries(output)
                phase = "legacy-audit-fixture"
                val auditState = File(output, "audit-owned-${UUID.randomUUID()}").apply { mkdirs() }
                val row = "2026-10-03 12:00:00\tdeep\t4096\t1\t0\t0\t完成\n"
                File(auditState, "history.tsv").writeText(row + row)
                val audit = JSONObject(AuditRepository(auditState).timelinePageJson(0, 30))
                check(audit.getInt("total") == 2 && audit.getLong("releasedBytes") == 4096L && audit.getInt("unmeasuredReleaseCount") == 1)
                File(output, "audit-observed.json").writeText(audit.toString(2))
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("sharedMtimeMillisCollisionObserved", true).put("mediaStoreSecondsCollisionObserved", true)
                    .put("mtimeSetters", org.json.JSONArray(timestampSetters))
                    .put("freshDuplicateScanRejectsChangedContent", true).put("staleReviewProofRejected", true)
                    .put("forcedIdentityCollisionRejectedByContentHash", true)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("sameSizeContentMutationObserved", before.map { it.bytes } == after.map { it.bytes })
                    .put("staleDuplicateGroupRefused", true)
                    .put("nanosecondStatFieldsAvailable", originalIdentity.modifiedNanos >= 0 && originalIdentity.changedNanos >= 0)
                    .put("capturedIdentityTupleCollisionObserved", originalIdentity == newIdentity)
                    // API 26's -1 fields are unobserved nanoseconds, not real equal nanoseconds.
                    .put("fullFilesystemIdentityCollisionObserved", originalIdentity.modifiedNanos >= 0 &&
                        originalIdentity.changedNanos >= 0 && originalIdentity == newIdentity)
                    .put("originalIdentity", originalIdentity.json()).put("newIdentity", newIdentity.json())
                    .put("sameSecondLegacyAuditOccurrences", 2).put("syntheticConfirmedBytes", 4096)
                    .put("ambiguousLegacyCapacityNotDoubleCounted", true)
                    .put("noDeletionPerformed", true).put("bothSharedFixturesPreserved", files.all { it.isFile })
                    .put("privateReadOnlyBoundaries", privateChecks)
                    .put("sharedAliasChecks", aliasChecks)
                    .put("fixtureDirectory", owned.path)
            }.getOrElse { JSONObject().put("passed", false).put("error", it.toString())
                .put("phase", phase).put("api", Build.VERSION.SDK_INT).put("uid", Process.myUid())
                .put("stackTrace", it.stackTraceToString()) }
            File(output, "result.json").writeText(result.toString(2)); finish()
        }
    }

    /** Some legacy shared filesystems refuse App utime. Only the guarded CI host
     * may set mtime on these generated fixtures; the App receives no new privilege. */
    private fun controlledTimestamp(file: File, output: File, request: Int): String {
        val mtime = 1500000000000L
        if (file.setLastModified(mtime)) return "app"
        val ack = File(output, "timestamp-ack-$request.txt").apply { writeText("pending") }
        val requestFile = File(output, "timestamp-request-$request.json")
        val pending = File(output, "timestamp-request-$request.part")
        pending.writeText(JSONObject().put("path", file.path).put("mtimeMillis", mtime)
            .put("appUid", Process.myUid()).toString(2))
        check(pending.renameTo(requestFile)) { "Could not publish fixture clock request" }
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (ack.readText().trim() != mtime.toString()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Generated fixture mtime could not be controlled: ${file.path}" }
            Thread.sleep(50)
        }
        while (file.lastModified() != mtime && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        check(file.lastModified() == mtime) { "Controller ack did not match the observed fixture mtime" }
        return "guarded-ci-root-fixture-controller"
    }

    private fun privateReadOnlyBoundaries(output: File): JSONObject {
        val fixtures = File(output, "readonly-owned-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val root = File(fixtures, "scan").apply { check(mkdir()) }
        val outside = File(fixtures, "outside").apply { check(mkdir()) }
        File(root, "first.bin").writeBytes(ByteArray(7))
        File(root, "nested").mkdir()
        File(root, "nested/second.bin").writeBytes(ByteArray(9))
        val privateFile = File(outside, "retained.bin").apply { writeBytes(ByteArray(33) { 7 }) }
        Os.symlink(outside.path, File(root, "outside-link").path)
        Os.symlink(root.path, File(root, "loop-link").path)
        val usage = DirectoryUsageScanner.scan(mapOf(root to root.path))
        check(usage.bytes == 16L && usage.linksSkipped == 2 && !usage.limited)
        val limited = DirectoryUsageScanner.scan(mapOf(root to root.path), maxEntries = 2)
        check(limited.limited && limited.bytes < 16L)
        val missing = DirectoryUsageScanner.scan(mapOf(File(fixtures, "missing") to "missing"))
        check(missing.inaccessible == 1 && missing.roots.isEmpty())
        check(runCatching { DirectoryUsageScanner.scan(mapOf(root to root.path), check = { throw CancellationException() }) }
            .exceptionOrNull() is CancellationException)
        // Context's /data/user/0 alias may canonicalize to /data/data. Both roots
        // refer only to this App-owned fixture, never arbitrary private data.
        val privateGuard = ApkDeletionGuard(setOf(outside.path, outside.canonicalPath), null)
        val identity = checkNotNull(privateGuard.capture(privateFile.path))
        val expected = privateFile.readBytes()
        val proof = IndexedContentReview.capture(identity, privateGuard)
        var errno: Int? = null
        try {
            Os.chmod(privateFile.path, 0)
            val blockedIdentity = checkNotNull(privateGuard.capture(privateFile.path))
            val failure = runCatching { IndexedContentReview.capture(blockedIdentity, privateGuard) }.exceptionOrNull()
            errno = (failure as? ErrnoException)?.errno
            check(errno == android.system.OsConstants.EACCES) { "No real EACCES observed: $failure" }
            check(!IndexedContentReview.matches(proof, identity, privateGuard) { false })
            val item = IndexedApkCandidate(999, "content://media/external/file/999", privateFile.path,
                privateFile.name, blockedIdentity.bytes, blockedIdentity.modifiedSeconds, blockedIdentity)
            val batch = IndexedContentReview.prepare(listOf(item), privateGuard, { false })
            check(batch.proofs.isEmpty() && batch.rejected.keys == setOf(item.uri))
        } finally { Os.chmod(privateFile.path, 384) }
        check(privateFile.readBytes().contentEquals(expected))
        val link = File(outside, "retained-hardlink.bin")
        val linkFailure = runCatching { Os.link(privateFile.path, link.path) }.exceptionOrNull()
        val hardLinkChecks = JSONObject().put("realHardlinkObserved", linkFailure == null)
        if (linkFailure == null) {
            val sourceIdentity = checkNotNull(privateGuard.capture(privateFile.path))
            val linkedIdentity = checkNotNull(privateGuard.capture(link.path))
            check(sourceIdentity.canonicalPath != linkedIdentity.canonicalPath &&
                sourceIdentity.device == linkedIdentity.device && sourceIdentity.inode == linkedIdentity.inode)
            val records = listOf(privateFile to sourceIdentity, link to linkedIdentity).mapIndexed { index, (file, observed) ->
                StorageFileRecord(800001L + index, "content://media/external/file/${800001L + index}",
                    file.path, file.name, observed.bytes, observed.modifiedSeconds, "application/octet-stream", identity = observed)
            }
            check(StorageDuplicateMatcher.match(records, { FileInputStream(it.path) },
                unchanged = { privateGuard.capture(it.path) == it.identity }).isEmpty())
            check(link.readBytes().contentEquals(expected))
            hardLinkChecks.put("hardlinkScanRejectsDuplicate", true).put("bothLinkContentsPreserved", true)
                .put("device", sourceIdentity.device).put("inode", sourceIdentity.inode)
        } else {
            hardLinkChecks.put("hardlinkScanRejectsDuplicate", JSONObject.NULL)
                .put("bothLinkContentsPreserved", JSONObject.NULL).put("unverifiedReason", linkFailure.toString())
        }
        return JSONObject().put("appUid", Process.myUid()).put("realReadErrno", errno)
            .put("realReadFailureRejectsContentProof", true)
            .put("outsideLinksAndLoopsNotTraversed", true).put("entryLimitReportedIncomplete", true)
            .put("missingRootReportedUnavailable", true).put("cancellationPropagated", true)
            .put("allFixtureContentsPreserved", true).put("linksPreserved", Files.isSymbolicLink(File(root, "outside-link").toPath()))
            .put("hardLinkChecks", hardLinkChecks)
    }
}
