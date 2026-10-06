package io.github.xgl34222220.baize

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StoragePresenceReviewTest {
    private val uid = 10042
    private fun record(id: Long, extension: String) = StorageFileRecord(id, "content://media/external/file/$id",
        "/storage/emulated/0/Download/synthetic-$id.$extension", "synthetic-$id.$extension", 1024, 1000, "application/octet-stream")
    private fun missing(item: IndexedApkCandidate): JSONObject {
        fun stat() = JSONObject().put("ok", false).put("errno", 2)
        fun directory() = JSONObject().put("ok", true).put("kind", "directory")
        val file = JSONObject().put("uid", 0).put("root", true).put("evidenceVersion", 1).put("requesterUid", uid)
            .put("requestedPath", item.path).put("success", false).put("identity", JSONObject.NULL).put("reason", "root_identity_unavailable")
            .put("attempts", JSONArray().apply {
                listOf(item.path, item.path.replace("/storage/emulated/0/", "/data/media/0/")).forEach {
                    put(JSONObject().put("path", it).put("storageRoot", directory()).put("stat", stat()))
                }
            })
        return JSONObject().put("path", item.path).put("uri", item.uri).put("appUid", uid).put("allFilesAccess", true)
            .put("appPathStat", stat()).put("appParentStat", directory()).put("currentAppIdentity", JSONObject.NULL)
            .put("index", JSONObject().put("exists", true).put("path", item.path).put("bytes", item.bytes).put("modified", item.modifiedSeconds))
            .put("mediaStoreFd", JSONObject().put("ok", false).put("errno", JSONObject.NULL)
                .put("error", "FileNotFoundException").put("message", "open failed: ENOENT (No such file or directory)"))
            .put("root", JSONObject().put("connected", true).put("uid", 0).put("fileEvidenceSupported", true).put("file", file))
    }
    @Test fun allStorageCategoriesExcludeCorroboratedGhostsWithoutAnyDeleteOperation() {
        val records = listOf("zip", "pdf", "jpg", "mp4", "flac", "apk", "bin").mapIndexed { i, ext -> record(i + 1L, ext) }
        val reviewed = StorageMediaRepository.reviewPresence(StorageIndexResult(records, 4, false), StorageScanControl(), ::missing, uid)
        assertTrue(reviewed.records.isEmpty())
        assertEquals(7, reviewed.confirmedMissing)
        assertEquals(0L, storageBuckets(reviewed.records).sumOf { it.bytes })
    }
    @Test fun unreadableOrDisconnectedEvidenceRemainsUnknownAndNeverBecomesCapacity() {
        val records = listOf(record(1, "zip"), record(2, "pdf"))
        val reviewed = StorageMediaRepository.reviewPresence(StorageIndexResult(records, 4, false), StorageScanControl(), {
            missing(it).apply {
                if (it.id == 1L) getJSONObject("appPathStat").put("errno", 13)
                else getJSONObject("root").put("connected", false)
            }
        }, uid)
        assertEquals(records, reviewed.records)
        assertEquals(0, reviewed.confirmedMissing)
        assertEquals(0L, storageBuckets(reviewed.records).sumOf { it.bytes })
    }
    @Test fun liveFileIsNotSentToMissingReviewAndMixedResultsKeepItsIdentity() {
        val live = record(1, "zip").let { it.copy(identity = ApkFileIdentity(it.path, 1, 2, it.bytes, it.modifiedSeconds, 1001, 0, 0)) }
        val ghost = record(2, "zip")
        var inspected = 0
        val reviewed = StorageMediaRepository.reviewPresence(StorageIndexResult(listOf(live, ghost), 4, false), StorageScanControl(), {
            inspected++; assertEquals(ghost.uri, it.uri); missing(it)
        }, uid)
        assertEquals(1, inspected)
        assertEquals(listOf(live), reviewed.records)
        assertEquals(1024L, storageBuckets(reviewed.records).sumOf { it.bytes })
    }
}
