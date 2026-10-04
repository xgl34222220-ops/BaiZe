package io.github.xgl34222220.baize

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.root.AuditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** No deletion: actual shared-storage timestamps plus explicitly forced identity collisions. */
class StorageCollisionDeviceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.lowercase().contains("sdk"))) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val output = File(filesDir, "storage-collision-probe").apply { mkdirs() }
            val result = runCatching {
                check(Process.myUid() >= 10000 && StorageMediaRepository.hasAccess(this@StorageCollisionDeviceProbeActivity))
                @Suppress("DEPRECATION")
                val owned = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "baize-collision-owned-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val files = listOf("first.bin", "second.bin").map { name -> File(owned, name).apply {
                    writeBytes(ByteArray(128 * 1024)); check(setLastModified(1500000000000L))
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, path); put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.SIZE, length()); put(MediaStore.MediaColumns.DATE_MODIFIED, lastModified() / 1000)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    }
                    checkNotNull(contentResolver.insert(MediaStore.Files.getContentUri("external_primary"), values))
                } }
                val before = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(owned.path + "/") }
                check(before.size == 2)
                val cache = StorageDigestCache()
                check(StorageMediaRepository.findDuplicates(applicationContext, before, cache = cache).groups.size == 1)
                val guard = ApkDeletionGuard.forContext(applicationContext)
                val originalIdentity = checkNotNull(guard.capture(files[1].path))
                val originalProof = IndexedContentReview.capture(originalIdentity, guard)
                val changed = ByteArray(128 * 1024).apply { this[lastIndex] = 1 }
                files[1].writeBytes(changed); check(files[1].setLastModified(1500000000000L))
                val after = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(owned.path + "/") }
                check(after.size == 2)
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
                val auditState = File(output, "audit-owned-${UUID.randomUUID()}").apply { mkdirs() }
                val row = "2026-10-03 12:00:00\tdeep\t4096\t1\t0\t0\t完成\n"
                File(auditState, "history.tsv").writeText(row + row)
                val audit = JSONObject(AuditRepository(auditState).timelinePageJson(0, 30))
                check(audit.getInt("total") == 2 && audit.getLong("releasedBytes") == 4096L && audit.getInt("unmeasuredReleaseCount") == 1)
                File(output, "audit-observed.json").writeText(audit.toString(2))
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("sharedMtimeMillisCollisionObserved", true).put("mediaStoreSecondsCollisionObserved", true)
                    .put("freshDuplicateScanRejectsChangedContent", true).put("staleReviewProofRejected", true)
                    .put("forcedIdentityCollisionRejectedByContentHash", true)
                    .put("fullFilesystemIdentityCollisionObserved", originalIdentity == newIdentity)
                    .put("originalIdentity", originalIdentity.json()).put("newIdentity", newIdentity.json())
                    .put("sameSecondLegacyAuditOccurrences", 2).put("syntheticConfirmedBytes", 4096)
                    .put("ambiguousLegacyCapacityNotDoubleCounted", true)
                    .put("noDeletionPerformed", true).put("bothSharedFixturesPreserved", files.all { it.isFile })
                    .put("fixtureDirectory", owned.path)
            }.getOrElse { JSONObject().put("passed", false).put("error", it.toString()) }
            File(output, "result.json").writeText(result.toString(2)); finish()
        }
    }
}
