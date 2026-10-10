package io.github.xgl34222220.baize.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerPublicSourcesTest {
    @Test
    fun auroraDownloadRootsAreAllowed() {
        listOf(
            "123云盘/a.apk", "BaiduNetdisk/x/a.zip", "AliYunPan/a.zip", "微云保存的文件/a.pdf",
            "Quark/Download/a.apk", "UCDownloads/a.apk", "UCTurbo/Download/a.apk", "QQBrowser/a.apk",
            "Download/a.apk.1", "Tencent/QQfile_recv/a.xapk", "Telegram/Telegram Files/a.apks"
        ).forEach { assertTrue(it, OrganizerPublicSources.allows(it)) }
    }

    @Test
    fun cameraGalleryAndUnknownRootsAreNotSources() {
        listOf(
            "DCIM/Camera/a.jpg", "Pictures/Screenshots/a.png", "Movies/a.mp4", "MIUI/ScreenRecorder/a.mp4",
            "Quark/Cache/a.apk", "UCTurbo/a.apk", "Download/../DCIM/a.jpg", "Android/data/x/files/a.apk"
        ).forEach { assertFalse(it, OrganizerPublicSources.allows(it)) }
    }

    @Test
    fun appPrivateDownloadDirectories() {
        assertTrue(OrganizerPublicSources.allowsAppPath("com.tencent.android.qqdownloader", "files/tassistant/apk/a.apk"))
        assertTrue(OrganizerPublicSources.allowsAppPath("com.tencent.mobileqq", "Tencent/QQfile_recv/a.apk.1"))
        assertTrue(OrganizerPublicSources.allowsAppPath("com.tencent.tim", "Tencent/TIMfile_recv/a.zip"))
        assertTrue(OrganizerPublicSources.allowsAppPath("org.telegram.messenger", "files/Telegram/Telegram Files/a.apk"))
        assertFalse(OrganizerPublicSources.allowsAppPath("com.tencent.mobileqq", "Tencent/MobileQQ/x/file_recv/a.apk"))
        assertFalse(OrganizerPublicSources.allowsAppPath("com.example", "Tencent/QQfile_recv/a.apk"))
        assertFalse(OrganizerPublicSources.allowsAppPath("com.example.qqdownloader", "files/tassistant/apk/a.apk"))
        assertFalse(OrganizerPublicSources.allowsAppPath("org.telegram.messenger", "cache/a.apk"))
        assertFalse(OrganizerPublicSources.allowsAppPath("com.tencent.mobileqq", "Tencent/QQfile_recv/../../a.apk"))
    }
}
