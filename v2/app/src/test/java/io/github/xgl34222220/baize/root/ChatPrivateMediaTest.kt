package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 微信 / QQ / TIM 应用私有目录的聊天媒体清理：
 * 白名单只认媒体与缓存目录，数据库 / 索引 / 配置永不处理；按时间档位统计；
 * 先移入隔离区（有上限），超出上限只有明确确认才永久删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatPrivateMediaTest {
    @get:Rule val folder = TemporaryFolder()

    private val account = "0123456789abcdef0123456789abcdef"
    private val dataRoot by lazy { folder.newFolder("user0").canonicalFile }
    private val state by lazy { folder.newFolder("state") }
    private val now = System.currentTimeMillis() / 1000L

    private fun repository(maxEntries: Int = QuarantineRepository.MAX_RECOVERABLE_ENTRIES) =
        QuarantineRepository(state, File(state, "missing.conf"), emptyList(), maxEntries)

    private fun file(relative: String, ageDays: Int, text: String = "media-bytes"): File =
        File(dataRoot, relative).apply {
            parentFile!!.mkdirs(); writeText(text)
            setLastModified((now - ageDays * 86_400L - 60L) * 1000L)
        }

    private fun wx(relative: String) = "com.tencent.mm/MicroMsg/$account/$relative"

    private fun folderRequest(relative: String): ChatPrivateCleaner.FolderRequest {
        val dir = File(dataRoot, relative)
        val id = requireNotNull(ChatFileIdentity.read(dir))
        return ChatPrivateCleaner.FolderRequest(dir.canonicalPath, id.device, id.inode)
    }

    @Test fun allowlistRejectsDatabasesIndexesAndConfig() {
        listOf("EnMicroMsg.db", "EnMicroMsg.db-wal", "EnMicroMsg.db-shm", "message.db", "a.db-wal", "a.db-shm",
            "WxFileIndex.db", "index", "FTS5IndexMicroMsg", "config", "app_config.xml", "EnMicroMsg2.db.ini", "mmkv.default")
            .forEach { assertTrue(it, ChatPrivatePaths.isProtectedName(it)) }
        listOf("th_1a2b3c", "abc.jpg", "video_1.mp4", "msg_1.amr", "report.pdf").forEach { assertFalse(it, ChatPrivatePaths.isProtectedName(it)) }
        assertTrue(ChatPrivatePaths.isProtectedRelative("MicroMsg/$account/shared_prefs/a.jpg"))
        assertTrue(ChatPrivatePaths.isProtectedRelative("databases/x.jpg"))
        assertTrue(ChatPrivatePaths.isProtectedRelative("MicroMsg/$account/image2/../EnMicroMsg.db"))
        assertTrue(ChatPrivatePaths.isProtectedRelative("MicroMsg/$account/image2/ab/config/x.jpg"))
        assertFalse(ChatPrivatePaths.isProtectedRelative("MicroMsg/$account/image2/ab/cd/th_1"))

        File(dataRoot, wx("image2")).mkdirs()
        val base = dataRoot.path
        assertEquals(ChatPrivatePaths.Kind.IMAGE, ChatPrivatePaths.folderFor(dataRoot, "$base/${wx("image2")}")?.kind)
        assertNull(ChatPrivatePaths.folderFor(dataRoot, "$base/com.tencent.mm/MicroMsg/$account"))
        assertNull(ChatPrivatePaths.folderFor(dataRoot, "$base/com.tencent.mm/databases"))
        assertNull(ChatPrivatePaths.folderFor(dataRoot, "$base/com.tencent.mm/shared_prefs"))
        assertNull(ChatPrivatePaths.folderFor(dataRoot, "$base/com.tencent.mm/MicroMsg/not-an-account/image2"))
        assertNull(ChatPrivatePaths.folderFor(dataRoot, "$base/com.example.other/cache"))
        assertNull(ChatPrivatePaths.folderFor(dataRoot, "$base/${wx("image2")}/../.."))
        assertEquals(ChatPrivatePaths.Kind.IMAGE, ChatPrivatePaths.folderFor(dataRoot, "$base/com.tencent.mobileqq/files/nt_qq_a1b2c3d4/nt_data/Pic")?.kind)
    }

    @Test fun ageBucketsCountOnlyFilesOlderThanEachThreshold() {
        assertTrue(ChatAgeBuckets.matches(now - 8 * 86_400L, now, 7))
        assertFalse(ChatAgeBuckets.matches(now - 6 * 86_400L, now, 7))
        assertFalse(ChatAgeBuckets.matches(0L, now, 7))
        assertTrue(ChatAgeBuckets.matches(now, now, 0))

        file(wx("image2/ab/cd/th_old"), 200)
        file(wx("image2/ab/cd/th_mid"), 40)
        file(wx("image2/ab/cd/th_new"), 1)
        file(wx("image2/WxFileIndex.db"), 400)
        file(wx("image2/shared_prefs/x.jpg"), 400)
        file("com.tencent.mm/MicroMsg/$account/EnMicroMsg.db", 400)
        file(wx("video/v1.mp4"), 100, "video-bytes-long")

        val result = ChatPrivateScanner(dataRoot).scan()
        val image = result.folders.single { it.folder.kind == ChatPrivatePaths.Kind.IMAGE }
        assertEquals(3L, image.total.files)
        assertEquals(1L, image.protectedFiles)
        assertEquals(2L, image.ages.getValue(7).files)
        assertEquals(2L, image.ages.getValue(30).files)
        assertEquals(1L, image.ages.getValue(90).files)
        assertEquals(1L, image.ages.getValue(180).files)
        assertEquals("012345", image.account)
        val video = result.folders.single { it.folder.kind == ChatPrivatePaths.Kind.VIDEO }
        assertEquals(1L, video.ages.getValue(90).files)
        assertEquals(0L, video.ages.getValue(180).files)

        val json = JSONObject(ChatPrivateScanner.json(result, QuarantineRepository.Capacity(10, 1_000L), mapOf("com.tencent.mm" to true)))
        val folders = json.getJSONArray("folders")
        assertEquals(2, folders.length())
        assertTrue(folders.getJSONObject(0).getBoolean("recoverable"))
        assertEquals(10, json.getInt("quarantineEntriesLeft"))
    }

    @Test fun cleanMovesOldMediaToQuarantineKeepsRecentAndDatabasesAndRestores() {
        val old = file(wx("image2/ab/th_old"), 200)
        val mid = file(wx("image2/ab/th_mid"), 40)
        val recent = file(wx("image2/ab/th_new"), 1)
        val db = file(wx("image2/WxFileIndex.db"), 400)
        val prefs = file(wx("image2/shared_prefs/keep.jpg"), 400)
        val repository = repository()
        val result = JSONObject(ChatPrivateCleaner(dataRoot, repository).clean(
            ChatPrivateCleaner.Request(listOf(folderRequest(wx("image2"))), now, 30, allowPermanent = false)))
        assertEquals(result.toString(), 2L, result.getLong("quarantined"))
        assertEquals(0L, result.getLong("deleted"))
        assertFalse(old.exists()); assertFalse(mid.exists())
        assertTrue(recent.exists()); assertTrue(db.exists()); assertTrue(prefs.exists())
        val page = JSONObject(repository.page(0, 10)).getJSONArray("items")
        assertEquals(2, page.length())
        val restoredId = (0 until page.length()).map { page.getJSONObject(it) }.single { it.getString("originalPath") == old.path }.getString("id")
        assertTrue(JSONObject(repository.restore(restoredId)).optBoolean("success"))
        assertEquals("media-bytes", old.readText())
    }

    @Test fun overCapIsKeptUnlessPermanentDeletionIsConfirmed() {
        val files = (1..3).map { file(wx("video/v$it.mp4"), 100) }
        val kept = JSONObject(ChatPrivateCleaner(dataRoot, repository(maxEntries = 1)).clean(
            ChatPrivateCleaner.Request(listOf(folderRequest(wx("video"))), now, 90, allowPermanent = false)))
        assertEquals(kept.toString(), 1L, kept.getLong("quarantined"))
        assertEquals(0L, kept.getLong("deleted"))
        assertEquals(2L, kept.getLong("kept"))
        assertEquals(2, files.count { it.exists() })

        val repository = repository(maxEntries = 1)
        val confirmed = JSONObject(ChatPrivateCleaner(dataRoot, repository).clean(
            ChatPrivateCleaner.Request(listOf(folderRequest(wx("video"))), now, 90, allowPermanent = true)))
        assertEquals(confirmed.toString(), 0L, confirmed.getLong("quarantined"))
        assertEquals(2L, confirmed.getLong("deleted"))
        assertEquals(0, files.count { it.exists() })
    }

    @Test fun capacityFitsLeadingItemsWithinCountAndBytes() {
        assertEquals(2, QuarantineRepository.Capacity(5, 100L).fitting(listOf(40L, 50L, 20L)))
        assertEquals(1, QuarantineRepository.Capacity(1, 1_000L).fitting(listOf(1L, 1L)))
        assertEquals(0, QuarantineRepository.Capacity(0, 1_000L).fitting(listOf(1L)))
        assertEquals(1, repository(maxEntries = 1).capacity().entriesLeft)
    }

    @Test fun changedOrNonAllowlistedFoldersAreRejectedUntouched() {
        val media = file(wx("voice2/a/msg.amr"), 300)
        val request = folderRequest(wx("voice2"))
        val stale = request.copy(inode = request.inode + 1)
        val db = file("com.tencent.mm/databases/EnMicroMsg.db", 300)
        val dbDir = File(dataRoot, "com.tencent.mm/databases")
        val dbId = requireNotNull(ChatFileIdentity.read(dbDir))
        val result = JSONObject(ChatPrivateCleaner(dataRoot, repository()).clean(ChatPrivateCleaner.Request(
            listOf(stale, ChatPrivateCleaner.FolderRequest(dbDir.canonicalPath, dbId.device, dbId.inode)), now, 7, allowPermanent = true)))
        assertEquals(0L, result.getLong("quarantined") + result.getLong("deleted"))
        assertEquals(2, result.getJSONArray("folderErrors").length())
        assertTrue(media.exists()); assertTrue(db.exists())
    }

    @Test fun recheckRefusesProtectedFilesAndFreshFiles() {
        val cleaner = ChatPrivateCleaner(dataRoot, repository())
        val db = file(wx("attachment/EnMicroMsg.db"), 300)
        val fresh = file(wx("attachment/new.pdf"), 0)
        val old = file(wx("attachment/old.pdf"), 300)
        val outside = file("com.tencent.mm/files/other.pdf", 300)
        val cutoff = now - 30 * 86_400L
        assertNotNull(cleaner.recheck(db, cutoff))
        assertNotNull(cleaner.recheck(fresh, cutoff))
        assertNotNull(cleaner.recheck(outside, cutoff))
        assertNull(cleaner.recheck(old, cutoff))
    }

    @Test fun forceStopOnlyAcceptsChatPackages() {
        assertEquals(listOf("/system/bin/am", "force-stop", "--user", "0", "com.tencent.mm"), ChatAppStopper.command(0, "com.tencent.mm"))
        assertThrows(IllegalArgumentException::class.java) { ChatAppStopper.command(0, "com.android.settings") }
        assertThrows(IllegalArgumentException::class.java) { ChatPrivateCleaner.parse(JSONObject().put("folders", JSONArray())
            .put("scannedAt", now).put("olderThanDays", 7)).let { ChatPrivateCleaner(dataRoot, repository()).clean(it) } }
        assertThrows(IllegalArgumentException::class.java) { ChatPrivateCleaner(dataRoot, repository()).clean(
            ChatPrivateCleaner.Request(listOf(folderRequest(wx("video").also { File(dataRoot, it).mkdirs() })), now, 45, false)) }
    }
}
