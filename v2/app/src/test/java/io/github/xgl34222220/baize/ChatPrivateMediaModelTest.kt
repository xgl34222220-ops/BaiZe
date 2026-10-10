package io.github.xgl34222220.baize

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 聊天媒体页 · 应用私有数据：默认不勾选、分组勾选、时间档位与回收站上限预估。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatPrivateMediaModelTest {
    private fun folder(path: String, app: String, files: Long, bytes: Long, recoverable: Boolean = true) = ChatPrivateFolder(
        path, app, "pkg", "图片", "image2", "012345", 1, 2, files, bytes, 0, recoverable,
        mapOf(7 to (files to bytes), 30 to (files / 2 to bytes / 2), 90 to (0L to 0L), 180 to (0L to 0L)))

    @Test fun parseKeepsEverythingUncheckedAndReadsAgeBuckets() {
        val raw = JSONObject().put("success", true).put("now", 1_700_000_000L).put("quarantineEntriesLeft", 3000)
            .put("quarantineBytesLeft", 16L shl 30)
            .put("folders", JSONArray()
                .put(JSONObject().put("path", "/data/data/com.tencent.mm/MicroMsg/x/image2").put("app", "微信").put("package", "com.tencent.mm")
                    .put("kind", "图片").put("folder", "image2").put("account", "012345").put("device", 1).put("inode", 2)
                    .put("files", 10).put("bytes", 1000).put("recoverable", true)
                    .put("ages", JSONObject().put("90", JSONObject().put("files", 4).put("bytes", 400))))
                .put(JSONObject().put("path", "/sdcard/not-private").put("files", 1)))
        val state = ChatPrivateMedia.parse(raw.toString())
        assertEquals(1, state.folders.size)
        assertTrue(state.selected.isEmpty())
        assertEquals(4L, state.folders.single().filesOlder(90))
        assertEquals(0L, state.folders.single().filesOlder(180))
        assertEquals(10L, state.folders.single().filesOlder(0))
        assertEquals(1_700_000_000L, state.scannedAt)
        assertTrue(ChatPrivateMedia.parse(JSONObject().put("success", false).put("message", "x").toString()).error)
    }

    @Test fun groupToggleSelectsOnlyThatAppAndAgeChangesSelectionTotals() {
        val state = ChatPrivateState(folders = listOf(folder("/data/a", "微信", 10, 1000), folder("/data/b", "微信", 4, 400),
            folder("/data/c", "QQ", 6, 600)), olderThanDays = 7)
        val wx = state.toggleApp("微信")
        assertEquals(setOf("/data/a", "/data/b"), wx.selected)
        assertEquals(14L, wx.selectedFiles)
        assertEquals(7L, wx.copy(olderThanDays = 30).selectedFiles)
        assertTrue(wx.toggleApp("微信").selected.isEmpty())
        assertEquals(setOf("/data/c"), state.toggle("/data/c").selected)
        assertTrue(wx.copy(olderThanDays = 90).selectedFolders.isEmpty())
    }

    @Test fun planSplitsRecoverableAndPermanentByCap() {
        val folders = listOf(folder("/data/a", "微信", 10, 1000), folder("/data/b", "QQ", 5, 500, recoverable = false))
        val withinCap = ChatPrivateMedia.plan(folders, 7, 3000, 16L shl 30)
        assertEquals(10L, withinCap.recoverableFiles)
        assertEquals(5L, withinCap.permanentFiles)
        val overCount = ChatPrivateMedia.plan(folders.take(1), 7, 3, 16L shl 30)
        assertEquals(3L, overCount.recoverableFiles)
        assertEquals(7L, overCount.permanentFiles)
        assertEquals(1000L, overCount.totalBytes)
        val overBytes = ChatPrivateMedia.plan(folders.take(1), 7, 3000, 250L)
        assertEquals(2L, overBytes.recoverableFiles)
        assertEquals(8L, overBytes.permanentFiles)
    }

    @Test fun cleanTotalsSumProgressAndKeepLatestKeptCount() {
        val totals = ChatPrivateMedia.CleanTotals()
        assertTrue(ChatPrivateMedia.accumulate(totals, JSONObject().put("success", true).put("quarantined", 3).put("quarantinedBytes", 30)
            .put("kept", 5).put("keptBytes", 50).put("truncated", true).toString()))
        assertFalse(ChatPrivateMedia.accumulate(totals, JSONObject().put("success", true).put("deleted", 2).put("deletedBytes", 20)
            .put("kept", 4).put("keptBytes", 40).put("reasons", JSONArray().put(JSONObject().put("reason", "回收站已满").put("count", 4))).toString()))
        assertEquals(3L, totals.quarantined); assertEquals(2L, totals.deleted); assertEquals(4L, totals.kept)
        val text = ChatPrivateMedia.summary(totals) { "${it}B" }
        assertTrue(text, text.contains("文字聊天记录未改动"))
        assertTrue(text, text.contains("回收站已满"))
        val request = ChatPrivateMedia.cleanRequest(ChatPrivateState(folders = listOf(folder("/data/a", "微信", 10, 1000)),
            selected = setOf("/data/a"), olderThanDays = 30, scannedAt = 5), allowPermanent = false)
        assertEquals(30, request.getInt("olderThanDays"))
        assertFalse(request.getBoolean("allowPermanent"))
        assertEquals("/data/a", request.getJSONArray("folders").getJSONObject(0).getString("path"))
    }
}
