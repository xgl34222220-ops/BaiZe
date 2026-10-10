package io.github.xgl34222220.baize.root

import android.app.Application
import io.github.xgl34222220.baize.ApkFileIdentity
import io.github.xgl34222220.baize.ChatStorageRecords
import io.github.xgl34222220.baize.StorageFileRecord
import io.github.xgl34222220.baize.StorageReviewFilters
import io.github.xgl34222220.baize.StorageToolMode
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
 * 聊天媒体页的 Root 记录：勾选后由 Root 逐项核对并移入回收站（隔离区），
 * 同分区移动、可恢复、有上限；数据库与聊天目录外的文件永不处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatStorageTrashTest {
    @get:Rule val folder = TemporaryFolder()

    private val media by lazy { folder.newFolder("media") }
    private val user0 by lazy { File(media, "0").apply { mkdirs() } }
    private val state by lazy { folder.newFolder("state") }

    private fun repository(maxEntries: Int = QuarantineRepository.MAX_RECOVERABLE_ENTRIES) =
        QuarantineRepository(state, File(state, "missing.conf"), listOf(File(user0, ".baize-quarantine")), maxEntries)

    private fun trash(repository: QuarantineRepository) = ChatStorageTrash(0, repository, mediaRoot = { File(media, "$it").path },
        inChatDirectory = { path -> ChatAppPaths.LOCATIONS.any { it.pattern.matches(path.removePrefix(user0.path + "/")) } })

    private fun file(relative: String, text: String = "cache-bytes"): File =
        File(user0, relative).apply { parentFile!!.mkdirs(); writeText(text); setLastModified(1_700_000_000_000L) }

    private fun request(file: File): ChatStorageTrash.Request {
        val id = requireNotNull(ChatFileIdentity.read(file))
        return ChatStorageTrash.Request(file.canonicalPath, id.device, id.inode, id.bytes, id.modifiedSeconds)
    }

    @Test fun cacheFileMovesToRecycleBinOnTheSameVolumeAndCanBeRestored() {
        val cache = file("Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo/abc/Cache_1a2b3c")
        val repository = repository()
        val result = JSONObject(trash(repository).trash(listOf(request(cache))))
        assertEquals(result.toString(), 1, result.getInt("trashed"))
        assertFalse(cache.exists())
        val detail = result.getJSONArray("details").getJSONObject(0)
        assertEquals("quarantined", detail.getString("action"))
        val stored = File(user0, ".baize-quarantine/items").listFiles().orEmpty()
        assertEquals(1, stored.size)
        assertEquals("cache-bytes", stored.single().readText())
        assertTrue(JSONObject(repository.restore(detail.getString("id"))).optBoolean("success"))
        assertEquals("cache-bytes", cache.readText())
    }

    @Test fun databasesOutsideFilesAndChangedFilesAreAlwaysKept() {
        val database = file("Android/data/com.tencent.mm/MicroMsg/0123456789abcdef0123456789abcdef/image2/WxFileIndex.db")
        val camera = file("DCIM/Camera/IMG_1.jpg")
        val changed = file("Android/data/com.tencent.mm/MicroMsg/0123456789abcdef0123456789abcdef/video/v.mp4")
        val stale = request(changed)
        changed.writeText("rewritten after scan")
        val result = JSONObject(trash(repository()).trash(listOf(request(database), request(camera), stale)))
        assertEquals(0, result.getInt("trashed"))
        assertTrue(database.exists()); assertTrue(camera.exists()); assertEquals("rewritten after scan", changed.readText())
        val reasons = (0 until 3).map { result.getJSONArray("details").getJSONObject(it).getString("reason") }
        assertTrue(reasons.toString(), reasons.any { it.contains("数据库") })
        assertTrue(reasons.toString(), reasons.any { it.contains("聊天目录") })
        assertTrue(reasons.toString(), reasons.any { it.contains("变化") })
    }

    @Test fun recycleBinCapKeepsRemainingFiles() {
        val first = file("Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo/a/Cache_one")
        val second = file("Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo/a/Cache_two")
        val result = JSONObject(trash(repository(maxEntries = 1)).trash(listOf(request(first), request(second))))
        assertEquals(1, result.getInt("trashed"))
        assertTrue(first.exists() xor second.exists())
        val kept = (0 until 2).map { result.getJSONArray("details").getJSONObject(it) }.single { it.getString("action") == "protected" }
        assertTrue(kept.getString("reason").contains("上限"))
    }

    @Test fun otherUsersStorageIsRejected() {
        val other = File(media, "10/Android/data/com.tencent.mm/MicroMsg/Download/a.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        val id = requireNotNull(ChatFileIdentity.read(other))
        val result = JSONObject(trash(repository()).trash(listOf(ChatStorageTrash.Request(other.canonicalPath, id.device, id.inode, id.bytes, id.modifiedSeconds))))
        assertEquals(0, result.getInt("trashed"))
        assertTrue(other.exists())
    }

    @Test fun appSideRootRecordsAreSelectableCacheIsBulkAndLabelSaysRoot() {
        fun record(name: String, identity: Boolean = true) = StorageFileRecord(-1, ChatStorageRecords.URI_PREFIX + "/storage/emulated/0/x/$name",
            "/storage/emulated/0/x/$name", name, 2_000_000, 1_700_000_000, "video/*", "QQ · 聊天视频",
            if (identity) ApkFileIdentity("/storage/emulated/0/x/$name", 1, 2, 2_000_000, 1_700_000_000, 0, 0, 0) else null)
        val cache = record("Cache_9f8e")
        val video = record("v.mp4")
        val unknown = record("w.mp4", identity = false)
        assertTrue(ChatStorageRecords.isCache(cache))
        assertNull(StorageReviewFilters.rowLock(StorageToolMode.CHAT_MEDIA, cache))
        assertTrue(StorageReviewFilters.bulkSelectable(StorageToolMode.CHAT_MEDIA, cache))
        assertNull(StorageReviewFilters.rowLock(StorageToolMode.CHAT_MEDIA, video))
        assertFalse(StorageReviewFilters.bulkSelectable(StorageToolMode.CHAT_MEDIA, video))
        assertEquals(ChatStorageRecords.IDENTITY_MISSING_LABEL, StorageReviewFilters.rowLock(StorageToolMode.CHAT_MEDIA, unknown))
        assertTrue(StorageReviewFilters.candidate(StorageToolMode.CHAT_MEDIA, cache, emptyList()))
        assertEquals("Root · QQ · 聊天视频 · 缓存", StorageReviewFilters.sourceLabel(StorageToolMode.CHAT_MEDIA, cache))
        assertFalse(StorageReviewFilters.sourceLabel(StorageToolMode.CHAT_MEDIA, video)!!.contains("仅查看"))

        val outcomes = ChatStorageRecords.parseTrash(JSONObject().put("details", org.json.JSONArray()
            .put(JSONObject().put("path", cache.path).put("action", "quarantined").put("id", "x"))
            .put(JSONObject().put("path", video.path).put("action", "protected").put("reason", "文件在扫描后已变化"))).toString(),
            mapOf(cache.path to cache, video.path to video))
        assertTrue(outcomes.getValue(cache.uri).trashed)
        assertFalse(outcomes.getValue(video.uri).trashed)
        assertFalse(outcomes.getValue(video.uri).deleted)
    }
}
