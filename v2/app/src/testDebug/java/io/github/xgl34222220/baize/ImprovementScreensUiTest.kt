package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCompressionScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<PhotoCompressionActivity>()
    @Test fun previewScreenExplainsOriginalAndMetadataBeforeAnyExport() {
        compose.onNodeWithText("照片瘦身").assertIsDisplayed()
        compose.onNodeWithText("选择照片").assertIsDisplayed()
        compose.onNodeWithText("生成压缩预览").assertIsNotEnabled()
        compose.onNodeWithText("选择位置，另存副本").assertDoesNotExist()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/round-photo-compression-320.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("照片瘦身").assertIsDisplayed()
        compose.onNodeWithText("选择位置，另存副本").assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileTrashScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<FileTrashActivity>()
    @Test fun trashScreenDoesNotOfferSilentAutomaticPurge() {
        // 页头与「文件回收站 / 隔离区」页内切换各有一个「文件回收站」文字。
        compose.onAllNodesWithText("文件回收站").onFirst().assertIsDisplayed()
        compose.onNodeWithText("1 GiB").assertDoesNotExist()
        compose.onNodeWithText("回收站为空").assertIsDisplayed()
        compose.onNodeWithText("永久删除").assertDoesNotExist()
        compose.onNodeWithText("清空回收站").assertIsNotEnabled()
        compose.onNodeWithText("说明与容量设置").performClick()
        compose.onNodeWithText("1 GiB").performScrollTo().performClick()
        compose.onNodeWithText("1 GiB").assertIsSelected()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(1024L * 1024 * 1024,
                compose.activity.getSharedPreferences("ordinary-trash", android.content.Context.MODE_PRIVATE).getLong("budget", 0))
        }
        compose.onNodeWithText("清空回收站").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("永久删除这 0 项").assertDoesNotExist()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/round-file-trash-320.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

