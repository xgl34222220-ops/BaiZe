package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReleaseAccountingTest {
    @get:Rule val folder = TemporaryFolder()
    private val repo get() = AuditRepository(folder.root)
    private fun record(operation: String, result: JSONObject) = repo.recordResult(operation, "test", result.toString())
    private fun event() = JSONObject(File(folder.root, "audit.jsonl").readLines().last())
    private fun timeline() = JSONObject(repo.timelinePageJson(0, 100))
    private fun deleted(bytes: Long) = JSONObject().put("success", true).put("deletedBytes", bytes)
        .put("deletedFiles", if (bytes > 0) 1 else 0)

    @Test fun successfulSystemRequestHasUnknownCapacityInsteadOfMeasuredZero() {
        record("instant-cache", JSONObject().put("success", true).put("succeeded", 1))
        assertEquals("success", event().getString("status"))
        assertEquals("unknown", event().getString("releaseState"))
        assertTrue(event().isNull("bytes")); assertTrue(event().isNull("releasedBytes"))
        assertEquals(0, timeline().getInt("measuredReleaseCount"))
        assertEquals(1, timeline().getInt("unmeasuredReleaseCount"))
    }

    @Test fun missingDeletionCountCannotFallBackToScanEstimate() {
        record("cache-clean", JSONObject().put("success", true).put("deletedFiles", 2).put("totalBytes", 8192))
        assertEquals("unknown", event().getString("releaseState"))
        assertTrue(event().isNull("releasedBytes"))
    }

    @Test fun explicitZeroRemainsMeasuredZero() {
        record("cache-clean", deleted(0).put("deletedFiles", 3))
        assertEquals("measured", event().getString("releaseState"))
        assertEquals(0L, event().getLong("releasedBytes"))
        assertEquals(1, timeline().getInt("measuredReleaseCount"))
        assertEquals(1, timeline().getInt("zeroReleaseCount"))
        assertEquals(0, timeline().getInt("unmeasuredReleaseCount"))
    }

    @Test fun invalidNullNegativeFractionalAndOverflowCountsRemainUnknown() {
        for (invalid in listOf(JSONObject.NULL, -1L, 1.5, "9223372036854775808", "NaN", true)) {
            record("cache-clean", JSONObject().put("deletedBytes", invalid).put("bytes", 5000))
            assertEquals("unknown", event().getString("releaseState"))
            assertTrue(event().isNull("releasedBytes"))
        }
    }

    @Test fun latestEnvironmentAcceptsAnExactIntegerString() {
        record("clean", JSONObject().put("success", true).put("latest", JSONObject().put("bytes", "2048")))
        assertEquals(2048L, event().getLong("releasedBytes"))
    }

    @Test fun trashAndQuarantineNeverIncreaseDeletionTotals() {
        record("ordinary-trash", JSONObject().put("success", true).put("trashedBytes", 4096))
        record("profile-quarantine", JSONObject().put("success", true).put("quarantinedBytes", 2048))
        assertEquals("retained", event().getString("releaseState"))
        assertTrue(event().isNull("releasedBytes"))
        val summary = timeline()
        assertEquals(0L, summary.getLong("releasedBytes"))
        assertEquals(0, summary.getInt("measuredReleaseCount"))
        assertEquals(2, summary.getInt("retainedCount"))
        assertEquals(6144L, summary.getLong("quarantinedBytes"))
    }

    @Test fun protectedSkippedAndFailedAreSeparateOutcomes() {
        record("cache-clean", deleted(0).put("skippedCandidates", 3).put("protectedCandidates", 2))
        assertEquals("protected", event().getString("status"))
        assertEquals(2L, event().getLong("protected")); assertEquals(1L, event().getLong("skipped"))
        record("cache-clean", deleted(0).put("skippedCandidates", 1))
        assertEquals("skipped", event().getString("status"))
        record("cache-clean", JSONObject().put("success", false).put("error", "root_unavailable"))
        assertEquals("failed", event().getString("status"))
        assertEquals("unknown", event().getString("releaseState"))
    }

    @Test fun knownDeletionSurvivesFailureAndCancellation() {
        record("cache-clean", deleted(1024).put("success", false).put("failures", 1))
        assertEquals("failed", event().getString("status"))
        record("profile-clean", deleted(2048).put("cancelled", true))
        assertEquals("cancelled", event().getString("status"))
        assertEquals(3072L, timeline().getLong("releasedBytes"))
    }

    @Test fun scansOrganizerAndRestoreDoNotCountAsDeletedContent() {
        for (operation in listOf("profile-scan", "file-organizer-apply", "quarantine-restore")) {
            record(operation, JSONObject().put("success", true).put("bytes", 8192))
            assertEquals("not_applicable", event().getString("releaseState"))
        }
        assertEquals(0, timeline().getInt("measuredReleaseCount"))
        assertEquals(0L, timeline().getLong("releasedBytes"))
    }

    @Test fun acceptedBackgroundRequestIsNotACompletedDeletion() {
        record("clean", deleted(4096).put("accepted", true))
        assertEquals(0L, timeline().getLong("releasedBytes"))
        assertEquals(0, timeline().getInt("measuredReleaseCount"))
    }

    @Test fun summaryAndItsProfileChildAreCountedOnce() {
        val child = record("profile-clean", deleted(2048))
        repo.recordNativeTask(JSONObject().put("mode", "workbench-clean").put("success", true)
            .put("bytes", 4096).put("files", 2).put("includedAuditEventIds", JSONArray().put(child)).toString(), "{\"success\":true}")
        val summary = timeline()
        assertEquals(4096L, summary.getLong("releasedBytes"))
        assertEquals(1, summary.getInt("measuredReleaseCount"))
        assertEquals(2, summary.getInt("total"))
    }

    @Test fun unknownSummaryDoesNotHideItsMeasuredChild() {
        val child = record("profile-clean", deleted(2048))
        repo.recordNativeTask(JSONObject().put("mode", "workbench-clean").put("success", false)
            .put("releaseState", "unknown").put("includedAuditEventIds", JSONArray().put(child)).toString(), "{\"success\":true}")
        assertEquals(2048L, timeline().getLong("releasedBytes"))
        assertEquals(1, timeline().getInt("unmeasuredReleaseCount"))
    }

    @Test fun partialMeasurementAddsKnownBytesWithoutCallingItsRemainderZero() {
        record("cache-clean", deleted(1024).put("releaseState", "partial"))
        assertEquals(1024L, timeline().getLong("releasedBytes"))
        assertEquals(1, timeline().getInt("unmeasuredReleaseCount"))
        assertEquals(0, timeline().getInt("zeroReleaseCount"))
    }

    @Test fun oldSystemCacheZeroIsReclassifiedWithoutRewritingTheHistoryFile() {
        val history = File(folder.root, "history.tsv")
        val text = "2026-10-03 00:00:00\tinstant-cache\t0\t0\t0\t0\t系统请求成功\tapp\n"
        history.writeText(text)
        val summary = timeline()
        assertEquals(1, summary.getInt("unmeasuredReleaseCount"))
        assertEquals("unknown", summary.getJSONArray("events").getJSONObject(0).getString("releaseState"))
        assertEquals(text, history.readText())
    }

    @Test fun oldAmbiguousZeroIsUnknownButOldPositiveDeletionRemainsMeasured() {
        File(folder.root, "history.tsv").writeText("2026-10-03 00:00:00\tcache-clean\t0\t0\t0\t0\t旧记录\tapp\n" +
            "2026-10-03 00:00:01\tcache-clean\t4096\t1\t0\t0\t旧删除\tapp\n")
        assertEquals(4096L, timeline().getLong("releasedBytes"))
        assertEquals(1, timeline().getInt("unmeasuredReleaseCount"))
    }

    @Test fun newNativeHistoryAndAuditMergeWithoutDoubleCounting() {
        val task = JSONObject().put("mode", "workbench-clean").put("success", true).put("bytes", 2048).put("files", 1)
        val result = HistoryRepository(folder.newFolder("module"), folder.root).recordNativeTaskJson(task.toString())
        assertTrue(result, JSONObject(result).getBoolean("success"))
        repo.recordNativeTask(task.toString(), result)
        assertEquals(1, timeline().getInt("total"))
        assertEquals(2048L, timeline().getLong("releasedBytes"))
    }

    @Test fun cancelledKnownBytesPersistInLifetimeStatistics() {
        val history = HistoryRepository(folder.newFolder("module"), folder.root)
        val task = JSONObject().put("mode", "workbench-clean").put("success", false).put("cancelled", true)
            .put("bytes", 1024).put("files", 1)
        assertTrue(JSONObject(history.recordNativeTaskJson(task.toString())).getBoolean("success"))
        val summary = JSONObject(history.taskHistoryJson(10))
        assertEquals(1024L, summary.getLong("lifetimeReleased"))
        assertEquals(0L, summary.getLong("lifetimeRuns"))
    }

    @Test fun unknownHistoryRetainsItsMeaningAfterReload() {
        val history = HistoryRepository(folder.newFolder("module"), folder.root)
        assertTrue(JSONObject(history.recordNativeTaskJson(JSONObject().put("mode", "workbench-clean")
            .put("releaseState", "unknown").put("success", false).toString())).getBoolean("success"))
        val entry = JSONObject(history.taskHistoryJson(10)).getJSONArray("entries").getJSONObject(0)
        assertEquals("unknown", entry.getString("releaseState"))
        assertEquals("unknown", timeline().getJSONArray("events").getJSONObject(0).getString("releaseState"))
    }

    @Test fun adviserAverageExcludesUnmeasuredRequestsAndIncludesTrueZero() {
        record("cache-clean", deleted(4096))
        record("cache-clean", deleted(0))
        repeat(3) { record("instant-cache", JSONObject().put("success", true).put("succeeded", 1)) }
        val advisor = timeline().getJSONObject("advisor")
        assertEquals(2, advisor.getInt("measuredReleaseCount"))
        assertEquals(2048L, advisor.getLong("averageReleaseBytes"))
    }

    @Test fun unmeasuredRequestsAreNotScoredAsZeroBenefit() {
        repeat(3) { record("instant-cache", JSONObject().put("success", true).put("succeeded", 1)) }
        val effectiveness = timeline().getJSONObject("effectiveness")
        assertFalse(effectiveness.getBoolean("available"))
        assertEquals(0, effectiveness.getInt("sampleCount"))
        assertEquals(3, effectiveness.getInt("unmeasuredTaskCount"))
        assertTrue(timeline().getJSONObject("advisor").isNull("averageReleaseBytes"))
    }

    @Test fun capacityAdditionCannotWrapNegative() {
        record("cache-clean", deleted(Long.MAX_VALUE))
        record("profile-clean", deleted(1024))
        assertEquals(Long.MAX_VALUE, timeline().getLong("releasedBytes"))
    }
}
