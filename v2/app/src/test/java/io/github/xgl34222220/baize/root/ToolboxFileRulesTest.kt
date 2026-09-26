package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ToolboxFileRulesTest {
    @Test fun parsesReferenceSyntaxAndDoesNotTreatFilenameAsRegex() {
        val rule = ToolboxFileRules.parse("# comment\n/sdcard/Download+*.apk&&*.zip+/sdcard/BaiZe归类/安装包").single()
        assertEquals("/data/media/0/Download", rule.source)
        assertTrue(rule.accepts(File("/data/media/0/Download/My App.APK")))
        assertTrue(rule.accepts(File("/data/media/0/Download/nested/save.zip")))
        assertFalse(rule.accepts(File("/data/media/0/Download/keep.jpg")))
        assertFalse(rule.accepts(File("/data/media/10/Download/app.apk")))
    }
    @Test fun supportsAppDirectoryWildcardAndLiteralPunctuation() {
        val rule = ToolboxFileRules.parse("/storage/emulated/0/Android/data/*/files/Download+*.apks+/sdcard/BaiZe归类/安装包").single()
        assertTrue(rule.accepts(File("/data/media/0/Android/data/com.app.store/files/Download/a.apks")))
        assertFalse(ToolboxFileRules.glob("a.zip").matches("aXzip"))
    }
    @Test fun rejectsTraversalLoopsAndPrivilegedDestinations() {
        for (text in listOf("/sdcard/Download+*.apk+/data/adb/a", "/sdcard/Download/../DCIM+*.jpg+/sdcard/Out", "/sdcard/Download+*.apk+/sdcard/Download/Out", "/sdcard/Download+*.apk+/sdcard/*"))
            assertThrows(text, IllegalArgumentException::class.java) { ToolboxFileRules.parse(text) }
    }
}
