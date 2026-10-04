package io.github.xgl34222220.baize

import android.app.Application
import io.github.xgl34222220.baize.root.AuditRepository
import io.github.xgl34222220.baize.root.HistoryRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StorageTimestampCollisionTest {
    @Test fun fullIdentityTupleCollisionDoesNotReusePreviousScanContentHash() {
        val contents = mutableMapOf(1L to ByteArray(128 * 1024), 2L to ByteArray(128 * 1024))
        val records = (1L..2L).map { id ->
            val path = "/storage/emulated/0/Download/collision-$id.bin"
            StorageFileRecord(id, "uri$id", path, "collision-$id.bin", 128 * 1024, 1500000000, "application/octet-stream",
                identity = ApkFileIdentity(path, 1, id, 128 * 1024, 1500000000, 1791028800, 123000000, 123000000))
        }
        val cache = StorageDigestCache(); var reads = 0
        val open: (StorageFileRecord) -> java.io.InputStream = { reads++; contents.getValue(it.id).inputStream() }
        assertEquals(1, StorageDuplicateMatcher.match(records, open, cache = cache).size)
        val initial = reads
        contents.getValue(2)[128 * 1024 - 1] = 1 // Same size, prefix and all supplied identity fields.
        assertTrue(StorageDuplicateMatcher.match(records, open, cache = cache).isEmpty())
        assertTrue("A new scan must read actual contents", reads > initial)
    }
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("audit-time-collision").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private val oldRow = "2026-10-03 12:00:00\tdeep\t4096\t1\t0\t0\t完成\n"
    @Test fun ambiguousLegacyRowsStayVisibleWithoutInventingAnotherRelease() = fixture { state ->
        val file = File(state, "history.tsv").apply { writeText(oldRow + oldRow) }; val original = file.readBytes()
        val result = JSONObject(AuditRepository(state).timelinePageJson(0, 30))
        assertEquals(2, result.getInt("total")); assertEquals(4096L, result.getLong("releasedBytes"))
        assertEquals(1, result.getInt("unmeasuredReleaseCount"))
        assertArrayEquals(original, file.readBytes())
    }
    @Test fun oldAuditMirrorStillDeduplicatesOneCorrespondingLegacyOccurrence() = fixture { state ->
        File(state, "history.tsv").writeText(oldRow)
        val first = JSONObject(AuditRepository(state).timelinePageJson(0, 30)).getJSONArray("events").getJSONObject(0)
        File(state, "audit.jsonl").writeText(first.toString() + "\n")
        File(state, "history.tsv").appendText(oldRow)
        val result = JSONObject(AuditRepository(state).timelinePageJson(0, 30))
        assertEquals(2, result.getInt("total")); assertEquals(4096L, result.getLong("releasedBytes"))
        assertEquals(1, result.getInt("unmeasuredReleaseCount"))
    }
    @Test fun distinctStoredIdsWithSameTimestampAndAmountAreBothCountedExactlyOnce() = fixture { state ->
        val rows = listOf("audit-owned-one", "audit-owned-two").joinToString("\n", postfix = "\n") { id ->
            listOf("2026-10-03 12:00:00", "deep", "4096", "1", "0", "0", "完成", "test", "", "", "measured", id).joinToString("\t") }
        File(state, "history.tsv").writeText(rows)
        val result = JSONObject(AuditRepository(state).timelinePageJson(0, 30))
        assertEquals(2, result.getInt("total")); assertEquals(8192L, result.getLong("releasedBytes"))
        assertEquals(0, result.getInt("unmeasuredReleaseCount"))
    }
    @Test fun declaredMeasurementWithInvalidTsvBytesStaysUnknownInHistoryAndAudit() = fixture { state ->
        val values = listOf("-1", "1.5", "true", "9223372036854775808", "")
        val text = values.mapIndexed { index, value -> listOf("2026-10-03 12:00:00", "deep", value, "1", "0", "0", "未确认", "test", "", "", "measured", "fixture-$index").joinToString("\t") }.joinToString("\n", postfix = "\n")
        val file = File(state, "history.tsv").apply { writeText(text) }
        val history = JSONObject(HistoryRepository(File(state, "module"), state).taskHistoryJson(30)).getJSONArray("entries")
        for (i in 0 until history.length()) assertEquals("unknown", history.getJSONObject(i).getString("releaseState"))
        val audit = JSONObject(AuditRepository(state).timelinePageJson(0, 30))
        assertEquals(0, audit.getInt("zeroReleaseCount")); assertEquals(values.size, audit.getInt("unmeasuredReleaseCount"))
        assertEquals(text, file.readText())
    }
}
