package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机事故回归：在“存储分析 · 分类”（文件归类）里选“图片”后点“全选当前结果 → 移入回收站”，
 * 会把 DCIM/Camera 的相机原件一起删掉。相机与相册原件永远不能进入可批量删除的分组。
 */
class CameraMediaBulkDeleteGuardTest {
    private val root = "/storage/emulated/0"
    private val now = 1_800_000_000L

    private fun record(path: String, mime: String, bytes: Long = 4_000_000, ageDays: Int = 200): StorageFileRecord {
        val modified = now - ageDays * 86_400L
        return StorageFileRecord(path.hashCode().toLong(), "content://media/external/file/${path.hashCode()}", path,
            path.substringAfterLast('/'), bytes, modified, mime,
            identity = ApkFileIdentity(path, 1L, path.hashCode().toLong(), bytes, modified, modified, 0L, 0L))
    }

    private val camera = listOf(
        record("$root/DCIM/Camera/IMG_20260101_1.jpg", "image/jpeg"),
        record("$root/DCIM/Camera/VID_20260101_1.mp4", "video/mp4"),
        record("$root/Pictures/旅行/IMG_2.jpg", "image/jpeg"),
        record("$root/Movies/家庭录像.mp4", "video/mp4"),
        record("$root/DCIM/ScreenRecorder/录屏_1.mp4", "video/mp4"),
        record("$root/MIUI/ScreenRecorder/r.mp4", "video/mp4")
    )
    private val ordinary = listOf(
        record("$root/Download/海报.jpg", "image/jpeg"),
        record("$root/Download/教程.mp4", "video/mp4")
    )

    @Test fun guardRecognisesCameraAlbumAndRecordingRoots() {
        (camera.map { it.path } + listOf("/data/media/0/DCIM/Camera/a.jpg", "/sdcard/Pictures/a.png", "/storage/1234-ABCD/DCIM/Camera/b.jpg"))
            .forEach { assertTrue(it, UserMediaGuard.isUserMedia(it)) }
        listOf("$root/Download/a.jpg", "$root/DCIM", "$root/Documents/DCIM/a.jpg", "$root/Android/data/x/files/DCIM/a.jpg", "/data/app/a.apk")
            .forEach { assertFalse(it, UserMediaGuard.isUserMedia(it)) }
    }

    @Test fun classifyCategorySelectAllNeverSelectsCameraMedia() {
        for (category in listOf("image", "video")) {
            val state = StorageToolsUiState(mode = StorageToolMode.ANALYSIS, records = camera + ordinary, category = category, nowSeconds = now)
            val selected = state.toggleAllSelection().selected
            val chosen = state.allRecords.filter { it.uri in selected }
            assertTrue(category, chosen.isNotEmpty())
            assertTrue(category, chosen.none { UserMediaGuard.isUserMedia(it.path) })
            assertTrue(category, state.recommended.none { uri -> camera.any { it.uri == uri } })
        }
    }

    @Test fun classifyCategoryCameraRowsAreReadOnlyEvenIndividually() {
        val state = StorageToolsUiState(mode = StorageToolMode.ANALYSIS, records = camera + ordinary, category = "image", nowSeconds = now)
        val photo = camera.first()
        val after = state.toggleSelection(photo.uri)
        assertFalse(photo.uri in after.selected)
        assertEquals(UserMediaGuard.READ_ONLY_LABEL, StorageReviewFilters.rowLock(StorageToolMode.ANALYSIS, photo))
        assertTrue(after.status.contains("仅查看"))
        // 普通下载图片仍可勾选。
        assertTrue(ordinary.first().uri in state.toggleSelection(ordinary.first().uri).selected)
    }

    @Test fun largeFilesAndDuplicatesKeepCameraOriginalsOutOfDefaultSelection() {
        val large = StorageToolsUiState(mode = StorageToolMode.LARGE, records = camera + ordinary, nowSeconds = now)
        assertTrue(large.recommended.none { uri -> camera.any { it.uri == uri } })
        assertTrue(large.toggleAllSelection().selected.none { uri -> camera.any { it.uri == uri } })
        // 大文件视图可以逐项勾选相机原件（删除前仍单独提示）。
        assertTrue(camera.first().uri in large.toggleSelection(camera.first().uri).selected)

        val copy = record("$root/Download/IMG_20260101_1.jpg", "image/jpeg")
        val group = DuplicateFileGroup("hash", camera.first().bytes, listOf(camera.first(), copy))
        val duplicates = StorageToolsUiState(mode = StorageToolMode.DUPLICATES, duplicateGroups = listOf(group), nowSeconds = now)
        for (preference in DuplicateKeeperPreference.entries) {
            val state = duplicates.copy(keeperPreference = preference)
            assertFalse(preference.name, camera.first().uri in state.recommended)
            assertFalse(preference.name, camera.first().uri in state.toggleAllSelection().selected)
        }
    }

    @Test fun chatMediaSelectAllSkipsUserSavedAlbumCopies() {
        val saved = record("$root/Pictures/WeiXin/mmexport1.jpg", "image/jpeg")
        val received = record("$root/Download/WeiXin/report.pdf", "application/pdf")
        val state = StorageToolsUiState(mode = StorageToolMode.CHAT_MEDIA, records = listOf(saved, received), nowSeconds = now, minimumAgeDays = 0)
        val selected = state.toggleAllSelection().selected
        assertTrue(received.uri in selected)
        assertFalse(saved.uri in selected)
    }

    @Test fun screenshotViewOnlyHitsScreenshotAndRecorderDirectories() {
        // 文件名像截图、但在相机目录或聊天导出目录的原件不算截图。
        assertNull(StorageReviewFilters.screenCaptureKind("$root/DCIM/Camera/Screenshot_1.jpg", "Screenshot_1.jpg", "image/jpeg"))
        assertNull(StorageReviewFilters.screenCaptureKind("$root/Pictures/WeiXin/Screenshot_2.jpg", "Screenshot_2.jpg", "image/jpeg"))
        assertNull(StorageReviewFilters.screenCaptureKind("$root/DCIM/Camera/录屏_3.mp4", "录屏_3.mp4", "video/mp4"))
        assertNull(StorageReviewFilters.screenCaptureKind("$root/Movies/screenrecord_4.mp4", "screenrecord_4.mp4", "video/mp4"))
        assertEquals(ScreenCaptureKind.SCREENSHOT, StorageReviewFilters.screenCaptureKind("$root/DCIM/Screenshots/Screenshot_5.jpg", "Screenshot_5.jpg", "image/jpeg"))
        assertEquals(ScreenCaptureKind.RECORDING, StorageReviewFilters.screenCaptureKind("$root/DCIM/ScreenRecorder/r.mp4", "r.mp4", "video/mp4"))
        val shots = StorageToolsUiState(mode = StorageToolMode.SCREENSHOTS, records = camera + ordinary +
            record("$root/DCIM/Screenshots/Screenshot_6.png", "image/png"), nowSeconds = now, minimumAgeDays = 30)
        val selected = shots.toggleAllSelection().selected
        val chosen = shots.allRecords.filter { it.uri in selected }.map { it.path }
        assertTrue(chosen.all { "/Screenshots/" in it || "/ScreenRecorder/" in it })
        assertTrue(chosen.none { "/DCIM/Camera/" in it })
    }
}
