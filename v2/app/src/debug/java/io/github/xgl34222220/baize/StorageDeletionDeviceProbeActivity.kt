package io.github.xgl34222220.baize

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Own UUID fixtures on the disposable emulator; exercises the real shared storage repository. */
class StorageDeletionDeviceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))) { finish(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val output = File(filesDir, "storage-deletion-probe").apply { mkdirs() }
            var owned: File? = null
            val createdRows = mutableListOf<android.net.Uri>()
            var stage = "permissions"
            val result = try {
                check(Process.myUid() >= 10_000 && StorageMediaRepository.hasAccess())
                @Suppress("DEPRECATION")
                val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "baize-storage-delete-probe-${UUID.randomUUID()}")
                check(!root.exists() && root.mkdirs()); owned = root
                fun create(name: String, fixedTimestamp: Boolean = false): File {
                    val file = File(root, name).apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
                    if (fixedTimestamp) check(file.setLastModified(1_500_000_000_000L))
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, file.path)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.SIZE, file.length())
                        put(MediaStore.MediaColumns.DATE_MODIFIED, file.lastModified() / 1000)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    }
                    val collection = MediaStore.Files.getContentUri(if (Build.VERSION.SDK_INT >= 29) "external_primary" else "external")
                    createdRows += checkNotNull(contentResolver.insert(collection, values))
                    return file
                }
                stage = "create-category-fixtures"
                val selected = listOf("zip", "pdf", "png", "mp4", "flac", "apk", "bin").map { create("selected.$it") }
                val trashFixture = create("trash-roundtrip.zip")
                val kept = create("unselected.zip")
                val protected = create("protected.zip")
                val changed = create("changed.zip")
                val stale = create("stale.zip")
                val tickFixture = create("same-tick-observation.zip", fixedTimestamp = true)
                stage = "capture-real-storage-identities"
                val before = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(root.path + "/") }
                fun record(file: File) = before.single { it.path == file.path }.also { check(it.identity != null) }
                selected.forEach(::record)
                val contentProofs = before.associate { item -> item.uri to IndexedContentReview.capture(
                    checkNotNull(item.identity), ApkDeletionGuard.forContext(applicationContext)) }
                check(selected.map { storageCategory(record(it)) }.toSet() == setOf("archive", "document", "image", "video", "audio", "apk", "other"))
                val safe = ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), emptySet()))
                stage = "ordinary-trash-durable-roundtrip"
                val moved = OrdinaryFileTrash.moveReviewed(applicationContext, record(trashFixture), contentProofs[record(trashFixture).uri], { safe }, { false })
                check(moved.trashed && !moved.deleted) { "Trash move failed: ${moved.reason}" }
                val trash = OrdinaryFileTrash.forContext(applicationContext)
                val entry = trash.entries().single { it.original == trashFixture.canonicalPath }
                check(!trashFixture.exists())
                trashFixture.writeText("new original must survive restore")
                val restored = trash.restore(entry.id)
                check(restored.path != trashFixture.path && restored.length() == 4096L)
                check(trashFixture.readText() == "new original must survive restore")
                check(trash.entries().none { it.id == entry.id })
                stage = "protect-and-change"
                check(StorageMediaRepository.delete(applicationContext, record(protected), {
                    ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), setOf(root.path)))
                }).result == ApkIndexedDeleteResult.PROTECTED)
                check(StorageMediaRepository.delete(applicationContext, record(protected), {
                    ApkProtectionState.Unknown("synthetic disconnected service")
                }).result == ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE)
                val old = record(changed)
                val replacement = File(root, "replacement.tmp").apply { writeBytes(ByteArray(4096) { 9 }); setLastModified(old.modifiedSeconds * 1000) }
                check(replacement.renameTo(changed))
                check(StorageMediaRepository.delete(applicationContext, old, { safe }).result == ApkIndexedDeleteResult.CHANGED)
                // Observe the shared-storage timestamp model independently from /data/user.
                // Never invoke deletion in this diagnostic; any collision remains explicit evidence.
                val guard = ApkDeletionGuard.forContext(applicationContext)
                fun ownedDescriptors() = File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
                    runCatching { android.system.Os.readlink(it.path) }.getOrNull()?.takeIf { target -> target.contains(root.name) }
                }
                // Count only descriptors for this UUID fixture, excluding unrelated UI/Binder work.
                check(ownedDescriptors().isEmpty()) { "Initial content review retained fixture descriptors: ${ownedDescriptors()}" }
                repeat(20) {
                    val identity = checkNotNull(guard.capture(tickFixture.path))
                    IndexedContentReview.capture(identity, guard)
                    var checks = 0
                    check(runCatching { IndexedContentReview.capture(identity, guard, { ++checks >= 3 }) }
                        .exceptionOrNull() is java.util.concurrent.CancellationException)
                    check(ownedDescriptors().isEmpty()) { "Completed/cancelled review retained a descriptor" }
                }
                var sharedStorageTimestampCollision = false
                var sharedStorageCollisionContentPreserved = false
                val descriptorsBefore = File("/proc/self/fd").list()?.size ?: error("Cannot observe own descriptors")
                for (iteration in 0 until 200) {
                    val original = checkNotNull(guard.capture(tickFixture.path))
                    val originalContent = IndexedContentReview.capture(original, guard)
                    tickFixture.writeBytes(ByteArray(4096) { if (iteration % 2 == 0) 19 else 37 })
                    check(tickFixture.setLastModified(1_500_000_000_000L))
                    if (guard.capture(tickFixture.path) == original) {
                        sharedStorageTimestampCollision = true
                        val outcome = StorageMediaRepository.delete(applicationContext, record(tickFixture).copy(identity = original),
                            { safe }, contentProof = originalContent)
                        check(outcome.result == ApkIndexedDeleteResult.CHANGED && tickFixture.isFile) { "Changed content was not preserved: $outcome" }
                        sharedStorageCollisionContentPreserved = true
                        break
                    }
                }
                File(output, "shared-storage-time-evidence.json").writeText(JSONObject()
                    .put("sharedStorageTimestampCollisionObserved", sharedStorageTimestampCollision)
                    .put("sharedStorageCollisionContentPreserved", sharedStorageCollisionContentPreserved)
                    .put("completedAndCancelledContentReviewDescriptorsClosed", true)
                    .put("openDescriptorsBefore", descriptorsBefore)
                    .put("openDescriptorsAfter", File("/proc/self/fd").list()?.size)
                    .put("ownedOpenDescriptorsAfter", JSONArray(ownedDescriptors()))
                    .put("api", Build.VERSION.SDK_INT).put("uid", Process.myUid()).toString(2))
                stage = "delete-every-indexed-category"
                check(ownedDescriptors().isEmpty()) { "Content review leaked fixture descriptors: ${ownedDescriptors()}" }
                val categories = JSONArray()
                for (file in selected) {
                    val item = record(file)
                    val diagnosticBefore = JSONObject(ApkFileReadDiagnostics.collect(applicationContext, item.uri, item.path, null, item.identity, indexedFile = true))
                    val outcome = StorageMediaRepository.delete(applicationContext, item, { safe }, contentProof = contentProofs[item.uri])
                    val evidence = recordIndexedDeletionProbe(applicationContext, output, "delete-${file.extension}-evidence.json",
                        item.uri, item.path, item.identity, outcome.result, diagnosticBefore)
                    check(outcome.deleted && !file.exists()) { "${file.extension}: $evidence" }
                    contentResolver.query(android.net.Uri.parse(item.uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use {
                        check(!it.moveToFirst()) { "Deleted file still indexed: ${file.extension}" }
                    }
                    categories.put(storageCategory(item))
                }
                check(kept.isFile && protected.isFile && changed.isFile)
                stage = "stale-row-does-not-become-selectable-capacity"
                check(stale.delete())
                val after = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(root.path + "/") }
                val ghost = after.singleOrNull { it.path == stale.path }
                check(ghost == null || (ghost.identity == null && ghost.verifiedBytes == 0L))
                val originalStale = record(stale)
                val diagnostic = ApkFileReadDiagnostics.collect(applicationContext, originalStale.uri, originalStale.path, null, originalStale.identity, indexedFile = true)
                File(output, "stale-zip-diagnostic.json").writeText(diagnostic)
                val parsed = JSONObject(diagnostic)
                check(parsed.getJSONObject("appPathStat").optInt("errno") == 2)
                check(!parsed.getJSONObject("mediaStoreFd").getBoolean("ok"))
                stage = "duplicate-keeps-one-real-copy"
                val duplicateA = create("duplicate-a.zip")
                val duplicateB = create("duplicate-b.zip")
                val pair = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path in setOf(duplicateA.path, duplicateB.path) }
                val group = StorageMediaRepository.findDuplicates(applicationContext, pair).groups.single()
                val chosen = group.records.first()
                check(StorageMediaRepository.duplicateStillSafe(applicationContext, chosen, group, setOf(chosen.uri), StorageScanControl()))
                val chosenProof = IndexedContentReview.capture(checkNotNull(chosen.identity), guard)
                check(StorageMediaRepository.delete(applicationContext, chosen, { safe }, contentProof = chosenProof).deleted)
                check(listOf(duplicateA, duplicateB).count { it.isFile } == 1)
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("categoriesActuallyDeletedAndIndexRemoved", categories)
                    .put("changedSameSizeAndMtimePreserved", changed.isFile)
                    .put("unknownProtectionAndProtectedFilePreserved", protected.isFile)
                    .put("unselectedPreserved", kept.isFile).put("staleIndexNotSelectableOrCounted", true)
                    .put("platformAlreadyRefreshedDeletedIndex", ghost == null)
                    .put("sharedStorageTimestampCollisionObserved", sharedStorageTimestampCollision)
                    .put("sharedStorageCollisionContentPreserved", sharedStorageCollisionContentPreserved)
                    .put("duplicateContentRecheckedAndOneCopyPreserved", true)
                    .put("ordinaryTrashDurableMoveAndConflictRestoreVerified", true)
                    .put("input", "run-owned synthetic indexed files; no user files")
            } catch (error: Exception) {
                JSONObject().put("passed", false).put("stage", stage).put("error", error.javaClass.name)
                    .put("message", error.message.orEmpty()).put("stack", error.stackTraceToString())
            } finally {
                // Only rows inserted by this exact probe and its newly-created UUID directory.
                createdRows.forEach { runCatching { contentResolver.delete(it, null, null) } }
                owned?.deleteRecursively()
            }
            File(output, "result.json").writeText(result.toString(2))
            launch(Dispatchers.Main) { finish() }
        }
    }
}
