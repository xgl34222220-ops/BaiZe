package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real protection screens, with synthetic records and no Root service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectionVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun protectedResults() {
        var cleanRequests = 0
        render("protected-results") {
            ProtectedReviewScreen(protectedFixture, {}, {}, {}, {}, {}, { cleanRequests++ })
        }
        compose.onNodeWithText("示例聊天附件").performClick()
        compose.onNodeWithText("复制详情").assertIsDisplayed().performClick()
        compose.onNodeWithText("已复制").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("清理所选 1 项").performClick()
        assertEquals("Opening confirmation must not delete", 0, cleanRequests)
        compose.onNodeWithText("确认清理").performClick()
        assertEquals(1, cleanRequests)
    }

    @Test fun protectedDark() = render("protected-dark", dark = true) {
        ProtectedReviewScreen(protectedFixture, {}, {}, {}, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun protectedNarrowError() = render("protected-narrow-error", fontScale = 1.3f) {
        ProtectedReviewScreen(protectedFixture.copy(failed = true, status = "清理失败：部分文件暂时无法访问，所选内容已保留。"), {}, {}, {}, {}, {}, {})
    }

    @Test fun quarantineResults() {
        var restores = 0
        var deletions = 0
        render("quarantine-results") {
            QuarantineScreen(quarantineFixture.copy(items = quarantineFixture.items.take(1)), {}, {}, { restores++ }, { deletions++ }, {})
        }
        compose.onNodeWithText("示例旧版备份").performClick()
        compose.onNodeWithText("复制详情").assertIsDisplayed().performClick()
        compose.onNodeWithText("已复制").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("恢复").performClick()
        assertEquals(0, restores)
        compose.onNodeWithText("确认恢复").performClick()
        assertEquals(1, restores)
        compose.onNodeWithText("永久删除").performClick()
        assertEquals(0, deletions)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, deletions)
        compose.onNodeWithText("永久删除").performClick()
        compose.onNodeWithText("确认永久删除").performClick()
        assertEquals(1, deletions)
    }

    @Test fun quarantineDark() = render("quarantine-dark", dark = true) {
        QuarantineScreen(quarantineFixture, {}, {}, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun quarantineNarrowError() = render("quarantine-narrow-error", fontScale = 1.3f) {
        QuarantineScreen(quarantineFixture.copy(failed = true, message = "恢复失败：原目录暂时无法写入，隔离文件已保留。"), {}, {}, {}, {}, {})
    }

    @Test fun quarantineEmpty() = render("quarantine-empty") {
        QuarantineScreen(QuarantineUiState(connected = true, message = "隔离区为空"), {}, {}, {}, {}, {})
    }

    private fun render(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val appearance = AppearanceSettings(uiStyle = UiStyle.MIUIX,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            monetEnabled = false, glassEnabled = true, blurEnabled = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppearanceSettings provides appearance,
                LocalDensity provides Density(density.density, fontScale)) {
                BaiZeTheme(appearance) { content() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        val target = File("build/reports/ui-screenshots/protection-$name.png")
        requireNotNull(target.parentFile).mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val protectedFixture = ProtectedReviewState(
        connected = true, status = "扫描完成，可按需选择；带锁项目将继续保留。", total = 3, selected = setOf("optional"),
        items = listOf(
            ProtectedReviewItem("optional", "示例聊天附件", "", "下载附件", "/storage/emulated/0/Android/media/example.chat/archive/2026/attachment-cache", "high", "", 72L * 1024 * 1024, true),
            ProtectedReviewItem("whitelist", "示例相册", "", "缩略图", "/storage/emulated/0/Pictures/example-album/thumbnails", "low", "应用已加入白名单", 18L * 1024 * 1024, false),
            ProtectedReviewItem("critical", "系统重要数据", "", "系统文件", "/data/system/example/important-state", "critical", "系统关键路径", -1L, false)
        )
    )

    private val quarantineFixture = QuarantineUiState(
        connected = true, message = "暂存内容可在到期前恢复。", retentionDays = 7,
        items = listOf(
            QuarantineItem("backup", "/storage/emulated/0/Download/example/old-backups/archive.zip", "示例旧版备份", "deep", "high", 1_789_170_000L, 1_789_774_800L, 82L * 1024 * 1024, 12, 1),
            QuarantineItem("logs", "/storage/emulated/0/Android/media/example.tool/archive/logs", "示例日志归档", "rules", "medium", 1_789_170_000L, 1_789_774_800L, 4L * 1024 * 1024, 8, 1)
        )
    )
}
