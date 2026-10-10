package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.ApkNames
import io.github.xgl34222220.baize.ChatStorageRecords
import io.github.xgl34222220.baize.StorageToolMode
import io.github.xgl34222220.baize.StorageReviewFilters
import io.github.xgl34222220.baize.storageCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * QQ / TIM / 微信真实目录布局的夹具树：Android 11+ 收到的文件与聊天媒体都在 Android/data 下，
 * 安装包常被改名为 .apk.1。这些测试锁定路径解析、安装包识别与只读扫描的边界。
 */
class ChatAppStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val account = "0123456789abcdef0123456789abcdef"
    private val now = 1_800_000_000L

    private fun touch(root: File, relative: String, bytes: Int = 16, ageDays: Int = 1): File =
        File(root, relative).also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(ByteArray(bytes) { 1 })
            file.setLastModified((now - ageDays * 86_400L) * 1000L)
        }

    /** 模拟一部 HyperOS 手机的 /data/media/0。 */
    private fun phone(): File {
        val root = folder.newFolder("media", "0")
        // QQ 新版：Android/data
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/微信.apk", ageDays = 200)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/游戏.apk.1", ageDays = 40)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/工具.apk.2", ageDays = 3)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/子目录/深层.apk", ageDays = 3)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/报告.pdf", ageDays = 3)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/半截.apk.tmp", ageDays = 3)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/说明.apk.txt", ageDays = 3)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQ_Images/保存.jpg", ageDays = 100)
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo/abc/v.mp4", ageDays = 100)
        // QQ 旧版与 TIM、下载目录
        touch(root, "Tencent/QQfile_recv/旧版.apk", ageDays = 400)
        touch(root, "Download/QQ/下载.apk.1", ageDays = 10)
        touch(root, "Android/data/com.tencent.tim/Tencent/TIMfile_recv/tim.apk", ageDays = 10)
        // 微信：接收文件、导出目录、账号目录下的聊天媒体
        touch(root, "Android/data/com.tencent.mm/MicroMsg/Download/安装包.apk.1", ageDays = 120)
        touch(root, "Download/WeiXin/导出.pdf", ageDays = 120)
        touch(root, "Pictures/WeiXin/mmexport1.jpg", ageDays = 120)
        touch(root, "Android/data/com.tencent.mm/MicroMsg/$account/image2/ab/cd/th_abcdef0123", ageDays = 95)
        touch(root, "Android/data/com.tencent.mm/MicroMsg/$account/video/v1.mp4", ageDays = 95)
        touch(root, "Android/data/com.tencent.mm/MicroMsg/$account/voice2/12/34/msg_1.amr", ageDays = 95)
        // 绝不读取：数据库、索引、非账号目录、隐藏目录、空文件、相机原件
        touch(root, "Android/data/com.tencent.mm/MicroMsg/$account/image2/WxFileIndex3.db")
        touch(root, "Android/data/com.tencent.mm/MicroMsg/$account/image2/ab/.thumb/secret.jpg")
        touch(root, "Android/data/com.tencent.mm/MicroMsg/not-an-account/image2/x.jpg")
        touch(root, "Android/data/com.tencent.mm/MicroMsg/$account/EnMicroMsg.db")
        touch(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/空.apk", bytes = 0)
        touch(root, "DCIM/Camera/IMG_0001.jpg")
        touch(root, "DCIM/Camera/VID_0001.mp4")
        return root
    }

    private fun scanner(root: File, apksOnly: Boolean = false, maxFiles: Int = ChatStorageScanner.MAX_FILES) =
        ChatStorageScanner(listOf(root), apksOnly = apksOnly, maxFiles = maxFiles, clock = { now * 1000L },
            publicPath = { it.replace(root.path, "/storage/emulated/0") })

    @Test fun apkNamesAcceptChatAppCopiesButNotTemporaryFiles() {
        listOf("a.apk", "A.APK", "a.apks", "a.xapk", "a.apkm", "a.aab", "a.apk.1", "a.apk.2", "a.APK.12", "a.apk.999").forEach {
            assertTrue(it, ApkNames.isApk(it))
        }
        listOf("a.apk.tmp", "a.apk.txt", "a.apk.1000", "apk", ".apk.1x", "a.apkx", "a.pdf", "a.apk.").forEach {
            assertFalse(it, ApkNames.isApk(it))
        }
        assertTrue(ApkNames.hasCopySuffix("/x/QQfile_recv/a.apk.1"))
        assertFalse(ApkNames.hasCopySuffix("/x/a.apk"))
        assertTrue(ApkNames.isPlainApk("a.apk.1"))
        assertFalse(ApkNames.isPlainApk("a.apks"))
        assertTrue("%.apk.1" in ApkNames.MEDIA_STORE_PATTERNS)
    }

    @Test fun receivedApkPathsCoverQqTimAndWechatLayouts() {
        val base = "/data/media/0"
        listOf(
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/a.apk.1",
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/sub/b.apk",
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/QQfile_recv/c.apk",
            "$base/Tencent/QQfile_recv/d.apk",
            "$base/tencent/qqfile_recv/e.apk",
            "$base/Download/QQ/f.apk.2",
            "$base/Android/data/com.tencent.tim/Tencent/TIMfile_recv/g.apk",
            "$base/Android/data/com.tencent.mm/MicroMsg/Download/h.apk.1",
            "$base/Download/WeiXin/i.apk",
            "$base/Android/data/com.tencent.mm/MicroMsg/$account/attachment/j.apk"
        ).forEach { assertTrue(it, ChatAppPaths.isReceivedApk(it)) }
        listOf(
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/a.pdf",
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/a.apk.tmp",
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQ_Images/a.apk",
            "$base/Android/data/com.tencent.mm/MicroMsg/not-an-account/attachment/a.apk",
            "$base/Android/data/com.tencent.mm/MicroMsg/$account/image2/a.apk",
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/../../../a.apk",
            "$base/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/.hidden/a.apk",
            "$base/Download/a.apk",
            "$base/DCIM/Camera/a.apk",
            "/data/app/~~x/com.tencent.mm/base.apk"
        ).forEach { assertFalse(it, ChatAppPaths.isReceivedApk(it)) }
    }

    @Test fun wechatAccountExpansionOnlyMatchesRealHexAccounts() {
        val root = phone()
        File(root, "Android/data/com.tencent.mm/MicroMsg/ABCDEF0123456789ABCDEF0123456789/video").mkdirs()
        val video = ChatAppPaths.LOCATIONS.first { it.app == "微信" && it.area == ChatAppPaths.Area.CHAT_VIDEO }
        val resolved = ChatAppPaths.resolve(root, video)
        assertEquals(listOf(File(root, "Android/data/com.tencent.mm/MicroMsg/$account/video")), resolved)
    }

    @Test fun rootScanFindsQqAndWechatApksIncludingRenamedCopies() {
        val root = phone()
        val result = scanner(root, apksOnly = true).scan()
        val names = result.entries.map { it.name }.toSet()
        assertEquals(setOf("微信.apk", "游戏.apk.1", "工具.apk.2", "深层.apk", "旧版.apk", "下载.apk.1", "tim.apk", "安装包.apk.1"), names)
        assertTrue(result.entries.all { it.kind == "apk" && it.area.receivedFiles })
        assertTrue(result.entries.all { ChatAppPaths.isReceivedApk(it.publicPath) })
        assertTrue(result.entries.all { it.publicPath.startsWith("/storage/emulated/0/") })
        assertFalse(result.truncated)
    }

    @Test fun rootScanListsChatMediaWithoutDatabasesHiddenOrCameraFiles() {
        val root = phone()
        val entries = scanner(root).scan().entries
        val names = entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("th_abcdef0123", "v1.mp4", "msg_1.amr", "mmexport1.jpg", "导出.pdf", "保存.jpg", "v.mp4", "报告.pdf")))
        listOf("WxFileIndex3.db", "EnMicroMsg.db", "secret.jpg", "x.jpg", "空.apk", "IMG_0001.jpg", "VID_0001.mp4", "半截.apk.tmp").forEach {
            assertFalse(it, it in names)
        }
        // 无扩展名的微信 image2 文件按目录识别为图片，语音按 voice2 识别为音频。
        assertEquals("image", entries.first { it.name == "th_abcdef0123" }.kind)
        assertEquals("audio", entries.first { it.name == "msg_1.amr" }.kind)
        assertTrue(entries.first { it.name == "mmexport1.jpg" }.area.userSaved)
        assertTrue(entries.none { "/DCIM/" in it.path })
    }

    @Test fun rootScanHonoursFileCapAndReportsTruncation() {
        val root = phone()
        val result = scanner(root, maxFiles = 2).scan()
        assertEquals(2, result.entries.size)
        assertTrue(result.truncated)
    }

    @Test fun symlinkedChatDirectoriesAreNotFollowed() {
        val root = phone()
        val outside = folder.newFolder("outside")
        touch(outside, "private.apk")
        val link = File(root, "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(scanner(root, apksOnly = true).scan().entries.none { it.name == "private.apk" })
    }

    @Test fun ageBucketsShowWhereFilesActuallyAre() {
        val root = phone()
        val result = scanner(root).scan()
        val buckets = ChatStorageScanner.ageBuckets(result.entries, now)
        assertEquals(result.entries.size, buckets[0])
        assertTrue(buckets[7]!! < buckets[0]!!)
        assertTrue(buckets[90]!! > 0)
        assertTrue(buckets[365]!! >= 1)
        assertTrue(buckets.keys.containsAll(listOf(0, 7, 30, 90, 180, 365)))
    }

    @Test fun rootRecordsReachChatMediaViewAsReadOnlyRows() {
        val root = phone()
        val parsed = ChatStorageRecords.parse(ChatStorageScanner.json(scanner(root).scan(), now))
        assertTrue(parsed.records.isNotEmpty())
        assertEquals(parsed.records.size, parsed.ageBuckets[0])
        val video = parsed.records.first { it.name == "v1.mp4" }
        assertTrue(ChatStorageRecords.isRootRecord(video))
        assertEquals("Root · 微信 · 聊天视频", StorageReviewFilters.sourceLabel(StorageToolMode.CHAT_MEDIA, video))
        // Android/data 下的聊天媒体以前被 forbidden() 整体排除，视图永远“无匹配”。
        assertTrue(StorageReviewFilters.candidate(StorageToolMode.CHAT_MEDIA, video, emptyList()))
        assertTrue(StorageReviewFilters.candidate(StorageToolMode.CHAT_MEDIA, parsed.records.first { it.name == "th_abcdef0123" }, emptyList()))
        // Root 记录可勾选（确认后由 Root 移入回收站）；未取得文件身份时才锁定。
        assertEquals(if (video.identity == null) ChatStorageRecords.IDENTITY_MISSING_LABEL else null,
            StorageReviewFilters.rowLock(StorageToolMode.CHAT_MEDIA, video))
        assertFalse(StorageReviewFilters.bulkSelectable(StorageToolMode.CHAT_MEDIA, video))
        // 安装包进入“存储分析 · 安装包”分类，包括 .apk.1。
        val renamed = parsed.records.first { it.name == "游戏.apk.1" }
        assertEquals("apk", storageCategory(renamed))
        val counts = StorageReviewFilters.ageBucketCounts(StorageToolMode.CHAT_MEDIA, parsed.records, now)
        assertTrue(counts[0]!! > counts[90]!!)
        assertTrue(counts[90]!! > 0)
    }
}
