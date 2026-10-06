package io.github.xgl34222220.baize

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApkMissingIndexPolicyTest {
    private val path = "/storage/emulated/0/Download/synthetic-missing.apk"
    private val uri = "content://media/external/file/42"
    private val appUid = 10042
    private fun statMissing() = JSONObject().put("ok", false).put("errno", 2)
    private fun directory() = JSONObject().put("ok", true).put("kind", "directory")
    internal fun evidence(): JSONObject {
        val attempts = JSONArray()
        for (target in listOf("/data/media/0/Download/synthetic-missing.apk", path)) {
            attempts.put(JSONObject().put("path", target).put("storageRoot", directory()).put("stat", statMissing()))
        }
        val file = JSONObject().put("uid", 0).put("root", true).put("evidenceVersion", 1)
            .put("requesterUid", appUid).put("requestedPath", path).put("success", false)
            .put("identity", JSONObject.NULL).put("reason", "root_identity_unavailable").put("attempts", attempts)
        return JSONObject().put("path", path).put("uri", uri).put("appUid", appUid).put("allFilesAccess", true)
            .put("appPathStat", statMissing()).put("appParentStat", directory()).put("currentAppIdentity", JSONObject.NULL)
            .put("index", JSONObject().put("exists", true).put("path", path).put("bytes", 1024).put("modified", 1000))
            .put("mediaStoreFd", statMissing().put("error", "FileNotFoundException"))
            .put("root", JSONObject().put("connected", true).put("uid", 0).put("fileEvidenceSupported", true).put("file", file))
    }
    private fun confirmed(report: JSONObject) = ApkMissingIndexPolicy.confirmedMissing(report, path, uri, 1024, 1000, appUid)
    private fun rootFile(report: JSONObject) = report.getJSONObject("root").getJSONObject("file")

    @Test fun corroboratedMissingFileWithStaleIndexIsExcludedFromReview() { assertTrue(confirmed(evidence())) }
    @Test fun appPermissionFailureIsUnknownEvenIfRootSaysMissing() {
        assertFalse(confirmed(evidence().apply { getJSONObject("appPathStat").put("errno", 13) }))
    }
    @Test fun fdPermissionFailureIsUnknownEvenIfBothPathViewsSayMissing() {
        assertFalse(confirmed(evidence().apply { getJSONObject("mediaStoreFd").put("errno", 13) }))
    }
    @Test fun eitherRootViewDeniedIsUnknown() {
        for (i in 0..1) assertFalse(confirmed(evidence().apply { rootFile(this).getJSONArray("attempts").getJSONObject(i).getJSONObject("stat").put("errno", 13) }))
    }
    @Test fun unavailableStorageRootIsNotADeletedFile() {
        assertFalse(confirmed(evidence().apply { rootFile(this).getJSONArray("attempts").getJSONObject(0).put("storageRoot", statMissing()) }))
    }
    @Test fun readableDescriptorOrReappearedPathIsRetained() {
        assertFalse(confirmed(evidence().apply { getJSONObject("mediaStoreFd").put("ok", true) }))
        assertFalse(confirmed(evidence().put("currentAppIdentity", JSONObject().put("inode", 123))))
        assertFalse(confirmed(evidence().apply { getJSONObject("appPathStat").put("ok", true) }))
    }
    @Test fun disconnectedOldOrNonRootServiceCannotConfirmAbsence() {
        assertFalse(confirmed(evidence().apply { getJSONObject("root").put("connected", false) }))
        assertFalse(confirmed(evidence().apply { getJSONObject("root").put("fileEvidenceSupported", false) }))
        assertFalse(confirmed(evidence().apply { getJSONObject("root").put("uid", 1000) }))
    }
    @Test fun wrongCallingUidOrRequestedPathIsRejected() {
        assertFalse(confirmed(evidence().apply { rootFile(this).put("requesterUid", 10043) }))
        assertFalse(confirmed(evidence().apply { rootFile(this).put("requestedPath", "/storage/emulated/10/Download/synthetic-missing.apk") }))
    }
    @Test fun rootFoundFileOrUnrecognizedEvidenceIsRetained() {
        assertFalse(confirmed(evidence().apply { rootFile(this).put("success", true).put("identity", JSONObject()) }))
        assertFalse(confirmed(evidence().apply { rootFile(this).put("evidenceVersion", 2) }))
        assertFalse(confirmed(evidence().apply { rootFile(this).put("reason", "caller_mismatch") }))
    }
    @Test fun missingOrDuplicateRootViewIsNotCorroboration() {
        assertFalse(confirmed(evidence().apply { rootFile(this).getJSONArray("attempts").remove(1) }))
        assertFalse(confirmed(evidence().apply { val a=rootFile(this).getJSONArray("attempts");a.put(1, a.getJSONObject(0)) }))
    }
    @Test fun indexRowReusedForAnotherFileOrModifiedInPlaceIsRetained() {
        for (key in listOf("path", "bytes", "modified")) assertFalse(confirmed(evidence().apply {
            getJSONObject("index").put(key, if (key == "path") "/storage/emulated/0/Download/other.apk" else 9999)
        }))
    }
    @Test fun failedIndexReadAndNullDescriptorWithoutMissingErrnoRemainUnknown() {
        assertFalse(confirmed(evidence().put("index", JSONObject().put("available", false))))
        assertFalse(confirmed(evidence().put("mediaStoreFd", JSONObject().put("ok", false).put("error", "null_descriptor"))))
    }
    @Test fun permissionRevocationOrForeignUserCannotBeHiddenAsMissing() {
        assertFalse(confirmed(evidence().put("allFilesAccess", false)))
        assertFalse(ApkMissingIndexPolicy.confirmedMissing(evidence(), path, uri, 1024, 1000, 1010042))
    }
    @Test fun binderMayDropErrnoCauseButPreserveTheExplicitMissingFileError() {
        val report = evidence().apply {
            getJSONObject("mediaStoreFd").put("errno", JSONObject.NULL).put("message", "open failed: ENOENT (No such file or directory)")
        }
        assertTrue(confirmed(report))
        report.getJSONObject("mediaStoreFd").put("message", "open failed: EACCES (Permission denied)")
        assertFalse(confirmed(report))
        report.getJSONObject("mediaStoreFd").put("message", "Permission denied reading /synthetic/ENOENT.apk")
        assertFalse(confirmed(report))
    }
    @Test fun removedIndexRowAndMissingBackingFileNeedNoUriMutation() {
        assertTrue(confirmed(evidence().put("index", JSONObject().put("exists", false))
            .put("mediaStoreFd", JSONObject().put("ok", false).put("error", "FileNotFoundException"))))
    }
}
