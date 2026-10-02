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
        compose.onNodeWithText("文件回收站").assertIsDisplayed()
        compose.onNodeWithText("容量上限").assertIsDisplayed()
        compose.onNodeWithText("回收站为空").assertIsDisplayed()
        compose.onNodeWithText("永久删除").assertDoesNotExist()
        compose.onNodeWithText("1 GiB").performClick()
        compose.onNodeWithText("1 GiB ✓").assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        File("build/reports/ui-screenshots/round-file-trash-320.png").apply { parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
