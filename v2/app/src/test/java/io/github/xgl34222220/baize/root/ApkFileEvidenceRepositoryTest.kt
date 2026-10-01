package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.ApkFileIdentity
import io.github.xgl34222220.baize.ApkRootFileEvidence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApkFileEvidenceRepositoryTest {
    private val own = 10_321
    private val path = "/storage/emulated/0/Download/sample.apk"
    private fun stamp(path: String) = ApkFileIdentity(path, 4, 99, 100, 20, 21, 0, 0)
    private fun repository(epoch: String = "first", reads: MutableList<String> = mutableListOf()) =
        ApkFileEvidenceRepository({ own }, epoch, stat = { reads += it; JSONObject().put("ok", true).put("kind", "directory") },
            capture = { _, file -> stamp(file) })

    @Test fun explicitCurrentUserPathProducesBoundReadOnlyEvidence() {
        val result = JSONObject(repository().read(path, own))
        assertTrue(result.getBoolean("success"))
        assertEquals(path, result.getString("requestedPath"))
        assertEquals(own, result.getInt("requesterUid"))
        assertEquals("/data/media/0/Download/sample.apk", result.getJSONObject("identity").getString("path"))
        assertEquals("root", result.getJSONObject("identity").getString("backend"))
    }

    @Test fun otherApplicationAndOtherUserPathsAreRejectedBeforeAnyStat() {
        val reads = mutableListOf<String>(); val repo = repository(reads = reads)
        assertFalse(JSONObject(repo.read(path, own + 1)).getBoolean("success"))
        assertFalse(JSONObject(repo.read(path, 10 * 100_000 + own)).getBoolean("success"))
        assertFalse(JSONObject(repo.read(path.replace("/0/", "/10/"), own)).getBoolean("success"))
        assertTrue(reads.isEmpty())
    }

    @Test fun privatePathsTraversalAndNonArchiveRequestsAreRejectedWithoutReading() {
        val reads = mutableListOf<String>(); val repo = repository(reads = reads)
        for (invalid in listOf("/data/media/0/Download/sample.apk", "/data/app/base.apk",
            "/storage/emulated/0/Download/../sample.apk", "/storage/emulated/0/Download/file.txt")) {
            assertFalse(JSONObject(repo.read(invalid, own)).getBoolean("success"))
        }
        assertTrue(reads.isEmpty())
    }

    @Test fun rootInternalReadStillUsesTheAppsOwnUserScope() {
        val result = JSONObject(repository().read(path, 0))
        assertTrue(result.getBoolean("success"))
        assertEquals(0, result.getInt("requesterUid"))
    }

    @Test fun statPermissionFailureRemainsUnavailableWithErrnoEvidence() {
        val repo = ApkFileEvidenceRepository({ own }, stat = { JSONObject().put("ok", false).put("errno", 13) },
            capture = { _, _ -> fail("Unreadable roots must not be treated as verified"); null })
        val result = JSONObject(repo.read(path, own))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.isNull("identity"))
        assertEquals(13, result.getJSONArray("attempts").getJSONObject(0).getJSONObject("stat").getInt("errno"))
    }

    @Test fun visiblePublicRootCanReportEvidenceWhenBackingRootIsUnavailable() {
        val repo = ApkFileEvidenceRepository({ own }, stat = { JSONObject().put("kind", if (it.startsWith("/data/")) "unavailable" else "directory") },
            capture = { _, file -> stamp(file) })
        assertEquals(path, JSONObject(repo.read(path, own)).getJSONObject("identity").getString("path"))
    }

    @Test fun serviceRecreationChangesTheEvidenceEpoch() {
        val before = JSONObject(repository("one").read(path, own)).getJSONObject("identity")
        val after = JSONObject(repository("two").read(path, own)).getJSONObject("identity")
        assertNotEquals(before.getString("sourceEpoch"), after.getString("sourceEpoch"))
    }

    @Test fun capabilityComesFromTheAppServiceNotTheSchedulingModule() {
        val old = JSONObject().put("uid", 0).put("root", true).put("moduleVersionCode", 30008)
        assertFalse(ApkRootFileEvidence.supported(old.toString()))
        assertTrue(ApkRootFileEvidence.supported(old.put("apkFileEvidenceVersion", 1).toString()))
        assertFalse(ApkRootFileEvidence.supported(old.put("uid", own).toString()))
        assertFalse(ApkRootFileEvidence.supported("not json"))
    }
}
