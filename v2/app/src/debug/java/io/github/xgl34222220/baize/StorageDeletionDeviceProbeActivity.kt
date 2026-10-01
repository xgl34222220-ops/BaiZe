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
                fun create(name: String): File {
                    val file = File(root, name).apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
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
                val kept = create("unselected.zip")
                val protected = create("protected.zip")
                val changed = create("changed.zip")
                val stale = create("stale.zip")
                stage = "capture-real-storage-identities"
                val before = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(root.path + "/") }
                fun record(file: File) = before.single { it.path == file.path }.also { check(it.identity != null) }
                selected.forEach(::record)
                check(selected.map { storageCategory(record(it)) }.toSet() == setOf("archive", "document", "image", "video", "audio", "apk", "other"))
                val safe = ApkProtectionState.KnownRoot(ApkProtectionRules(emptySet(), emptySet()))
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
                stage = "delete-every-indexed-category"
                val categories = JSONArray()
                for (file in selected) {
                    val item = record(file)
                    val outcome = StorageMediaRepository.delete(applicationContext, item, { safe })
                    check(outcome.deleted && !file.exists()) { "${file.extension}: ${outcome.reason}" }
                    contentResolver.query(android.net.Uri.parse(item.uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)!!.use {
                        check(!it.moveToFirst()) { "Deleted file still indexed: ${file.extension}" }
                    }
                    categories.put(storageCategory(item))
                }
                check(kept.isFile && protected.isFile && changed.isFile)
                stage = "stale-row-does-not-become-selectable-capacity"
                check(stale.delete())
                val after = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path.startsWith(root.path + "/") }
                val ghost = after.single { it.path == stale.path }
                check(ghost.identity == null && ghost.verifiedBytes == 0L)
                val diagnostic = ApkFileReadDiagnostics.collect(applicationContext, ghost.uri, ghost.path, null, ghost.identity, indexedFile = true)
                File(output, "stale-zip-diagnostic.json").writeText(diagnostic)
                val parsed = JSONObject(diagnostic)
                check(parsed.getJSONObject("appPathStat").optInt("errno") == 2)
                check(parsed.getJSONObject("index").getBoolean("exists"))
                check(!parsed.getJSONObject("mediaStoreFd").getBoolean("ok"))
                stage = "duplicate-keeps-one-real-copy"
                val duplicateA = create("duplicate-a.zip")
                val duplicateB = create("duplicate-b.zip")
                val pair = StorageMediaRepository.scanIndex(applicationContext).records.filter { it.path in setOf(duplicateA.path, duplicateB.path) }
                val group = StorageMediaRepository.findDuplicates(applicationContext, pair).groups.single()
                val chosen = group.records.first()
                check(StorageMediaRepository.duplicateStillSafe(applicationContext, chosen, group, setOf(chosen.uri), StorageScanControl()))
                check(StorageMediaRepository.delete(applicationContext, chosen, { safe }).deleted)
                check(listOf(duplicateA, duplicateB).count { it.isFile } == 1)
                JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                    .put("categoriesActuallyDeletedAndIndexRemoved", categories)
                    .put("changedSameSizeAndMtimePreserved", changed.isFile)
                    .put("unknownProtectionAndProtectedFilePreserved", protected.isFile)
                    .put("unselectedPreserved", kept.isFile).put("staleIndexNotSelectableOrCounted", true)
                    .put("duplicateContentRecheckedAndOneCopyPreserved", true)
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
