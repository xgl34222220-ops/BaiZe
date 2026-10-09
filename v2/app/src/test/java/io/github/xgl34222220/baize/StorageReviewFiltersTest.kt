package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 截图录屏 / 旧下载 / 聊天媒体 / 自定义规则的选择与保护逻辑。 */
class StorageReviewFiltersTest {
    private val root = "/storage/emulated/0"
    private val now = 1_800_000_000L
    private fun record(path: String, mime: String = "", bytes: Long = 1_000, ageDays: Int = 100) =
        StorageFileRecord(path.hashCode().toLong(), "content://media/external/file/${path.hashCode()}", path,
            path.substringAfterLast('/'), bytes, now - ageDays * StorageReviewFilters.DAY_SECONDS, mime)

    @Test fun screenshotsAndRecordingsAreRecognisedAcrossVendors() {
        val shot = StorageReviewFilters.screenCaptureKind("$root/DCIM/Screenshots/Screenshot_2026.jpg", "Screenshot_2026.jpg", "image/jpeg")
        assertEquals(ScreenCaptureKind.SCREENSHOT, shot)
        assertEquals(ScreenCaptureKind.SCREENSHOT, StorageReviewFilters.screenCaptureKind("$root/Pictures/Screenshots/a.png", "a.png", ""))
        assertEquals(ScreenCaptureKind.RECORDING, StorageReviewFilters.screenCaptureKind("$root/DCIM/ScreenRecorder/r.mp4", "r.mp4", "video/mp4"))
        assertEquals(ScreenCaptureKind.RECORDING, StorageReviewFilters.screenCaptureKind("$root/Movies/Screen recordings/x.mp4", "x.mp4", "video/mp4"))
        assertEquals(ScreenCaptureKind.SCREENSHOT, StorageReviewFilters.screenCaptureKind("$root/DCIM/截屏/截屏_1.jpg", "截屏_1.jpg", "image/jpeg"))
        // 普通相机照片与文档不算截图。
        assertNull(StorageReviewFilters.screenCaptureKind("$root/DCIM/Camera/IMG_1.jpg", "IMG_1.jpg", "image/jpeg"))
        assertNull(StorageReviewFilters.screenCaptureKind("$root/DCIM/Screenshots/notes.pdf", "notes.pdf", "application/pdf"))
    }

    @Test fun ageFilterUsesModifiedTimeAndKeepsUnknownTimesOut() {
        assertTrue(StorageReviewFilters.olderThan(now - 31 * 86_400L, now, 30))
        assertFalse(StorageReviewFilters.olderThan(now - 29 * 86_400L, now, 30))
        assertFalse(StorageReviewFilters.olderThan(0, now, 30))
        assertTrue(StorageReviewFilters.olderThan(0, now, 0))
        val recent = record("$root/DCIM/Screenshots/Screenshot_new.png", "image/png", ageDays = 3)
        val old = record("$root/DCIM/Screenshots/Screenshot_old.png", "image/png", ageDays = 60)
        assertFalse(StorageReviewFilters.visible(StorageToolMode.SCREENSHOTS, recent, now, 30, emptyList(), null))
        assertTrue(StorageReviewFilters.visible(StorageToolMode.SCREENSHOTS, old, now, 30, emptyList(), null))
        assertTrue(StorageReviewFilters.visible(StorageToolMode.SCREENSHOTS, recent, now, 0, emptyList(), null))
    }

    @Test fun oldDownloadsOnlyCoverTheSharedDownloadFolder() {
        assertTrue(StorageReviewFilters.candidate(StorageToolMode.OLD_DOWNLOADS, record("$root/Download/a.zip"), emptyList()))
        assertTrue(StorageReviewFilters.candidate(StorageToolMode.OLD_DOWNLOADS, record("/storage/1234-ABCD/Download/sub/b.pdf"), emptyList()))
        assertFalse(StorageReviewFilters.candidate(StorageToolMode.OLD_DOWNLOADS, record("$root/Documents/Download/c.pdf"), emptyList()))
        assertFalse(StorageReviewFilters.candidate(StorageToolMode.OLD_DOWNLOADS, record("$root/Android/data/x.y/files/Download/d.bin"), emptyList()))
    }

    @Test fun chatMediaNeverIncludesDatabasesOrAccountDirectories() {
        assertEquals("微信", StorageReviewFilters.chatMediaSource("$root/Pictures/WeiXin/mmexport1.jpg"))
        assertEquals("微信", StorageReviewFilters.chatMediaSource("$root/Download/WeiXin/report.pdf"))
        assertEquals("QQ", StorageReviewFilters.chatMediaSource("$root/Pictures/QQ/a.jpg"))
        assertEquals("QQ", StorageReviewFilters.chatMediaSource("$root/tencent/QQfile_recv/a.doc"))
        assertTrue(StorageReviewFilters.candidate(StorageToolMode.CHAT_MEDIA, record("$root/Pictures/WeiXin/mmexport1.jpg", "image/jpeg"), emptyList()))
        // 数据库、账号目录与应用私有目录一律不进入结果。
        listOf("$root/tencent/MicroMsg/abcdef/EnMicroMsg.db", "$root/tencent/MicroMsg/abcdef/image2/a.jpg",
            "$root/Android/media/com.tencent.mm/MicroMsg/x/video/a.mp4", "$root/Pictures/WeiXin/cache.db",
            "$root/Pictures/WeiXin/msg.db-wal", "$root/Download/WeiXin/.hidden/a.jpg", "$root/Android/data/com.tencent.mobileqq/a.jpg",
            "$root/Download/WeiXin/index.sqlite"
        ).forEach { path ->
            assertTrue(path, StorageReviewFilters.forbidden(path))
            assertFalse(path, StorageReviewFilters.candidate(StorageToolMode.CHAT_MEDIA, record(path, "image/jpeg"), emptyList()))
        }
        assertFalse(StorageReviewFilters.candidate(StorageToolMode.CHAT_MEDIA, record("$root/Pictures/WeiXin/a.apk"), emptyList()))
    }

    @Test fun customFilterGlobMatchesOnlyItsScope() {
        val filter = StorageReviewFilters.validate("f1", "压缩包", "Download/**/*.zip", 30, 1).filter!!
        assertTrue(filter.matchesPath("$root/Download/a.zip"))
        assertTrue(filter.matchesPath("$root/Download/x/y/B.ZIP"))
        assertFalse(filter.matchesPath("$root/Download/a.zip.txt"))
        assertFalse(filter.matchesPath("$root/Documents/a.zip"))
        assertFalse(filter.matchesPath("$root/Download/.private/a.zip"))
        assertTrue(filter.matches(record("$root/Download/a.zip", bytes = 2L * 1024 * 1024, ageDays = 40), now))
        assertFalse(filter.matches(record("$root/Download/a.zip", bytes = 2L * 1024 * 1024, ageDays = 10), now))
        assertFalse(filter.matches(record("$root/Download/a.zip", bytes = 1024, ageDays = 40), now))
        val single = StorageReviewFilters.validate("f2", "", "Pictures/*.gif", 0, 0).filter!!
        assertTrue(single.matchesPath("$root/Pictures/a.gif"))
        assertFalse(single.matchesPath("$root/Pictures/sub/a.gif"))
        assertEquals("Pictures/*.gif", single.name)
        // 绝对路径会被规范为卷内相对路径。
        assertEquals("Download/*.apk", StorageReviewFilters.validate("f3", "", "/sdcard/Download/*.apk", 0, 0).filter!!.pattern)
    }

    @Test fun customFilterRejectsBroadOrProtectedScopes() {
        listOf("", "**", "*/a.zip", "**/*.log", "Android/data/**", ".thumbnails/**", "Download/../Android/**",
            "Download/[ab].zip", "Pictures/.hidden/**", "Download/a\nb").forEach { pattern ->
            assertNull(pattern, StorageReviewFilters.validate("x", "", pattern, 0, 0).filter)
        }
        assertNull(StorageReviewFilters.validate("x", "", "Download/*", -1, 0).filter)
        assertNull(StorageReviewFilters.validate("x", "", "Download/*", 0, -5).filter)
        // 即使规则覆盖了 tencent 目录，数据库文件也不会被匹配。
        val tencent = StorageReviewFilters.validate("t", "", "tencent/**", 0, 0).filter!!
        assertFalse(tencent.matchesPath("$root/tencent/MicroMsg/abc/EnMicroMsg.db"))
        assertFalse(tencent.matchesPath("$root/tencent/MicroMsg/abc/voice2/a.amr"))
        assertTrue(tencent.matchesPath("$root/tencent/MicroMsg/WeiXin/mmexport.jpg"))
    }

    @Test fun customFiltersRoundTripAndDropInvalidSavedEntries() {
        val filters = listOf(StorageReviewFilters.validate("a-1", "旧压缩包", "Download/**/*.zip", 30, 5).filter!!)
        val decoded = StorageReviewFilters.decode(StorageReviewFilters.encode(filters))
        assertEquals(filters, decoded)
        val tampered = """[{"id":"bad","name":"x","pattern":"Android/data/**","age":0,"bytes":0},
            {"id":"ok","name":"y","pattern":"Movies/*.mp4","age":7,"bytes":0},{"id":"../x","pattern":"Download/*"}, 5]"""
        assertEquals(listOf("ok"), StorageReviewFilters.decode(tampered).map { it.id })
        assertTrue(StorageReviewFilters.decode("not json").isEmpty())
        assertTrue(StorageReviewFilters.decode(null).isEmpty())
    }

    @Test fun customModeShowsActiveRuleOrAnyRule() {
        val zip = StorageReviewFilters.validate("zip", "", "Download/*.zip", 0, 0).filter!!
        val mp4 = StorageReviewFilters.validate("mp4", "", "Movies/*.mp4", 0, 0).filter!!
        val a = record("$root/Download/a.zip")
        val b = record("$root/Movies/b.mp4")
        assertTrue(StorageReviewFilters.visible(StorageToolMode.CUSTOM, a, now, 0, listOf(zip, mp4), null))
        assertTrue(StorageReviewFilters.visible(StorageToolMode.CUSTOM, b, now, 0, listOf(zip, mp4), null))
        assertFalse(StorageReviewFilters.visible(StorageToolMode.CUSTOM, b, now, 0, listOf(zip, mp4), "zip"))
        assertFalse(StorageReviewFilters.visible(StorageToolMode.CUSTOM, a, now, 0, emptyList(), null))
    }

    @Test fun reviewModesHaveSensibleDefaults() {
        assertEquals(30, StorageReviewFilters.defaultAgeDays(StorageToolMode.SCREENSHOTS))
        assertEquals(90, StorageReviewFilters.defaultAgeDays(StorageToolMode.OLD_DOWNLOADS))
        assertEquals(0, StorageReviewFilters.defaultAgeDays(StorageToolMode.LARGE))
        assertTrue(StorageToolMode.CUSTOM.review)
        assertFalse(StorageToolMode.DUPLICATES.review)
    }
}
