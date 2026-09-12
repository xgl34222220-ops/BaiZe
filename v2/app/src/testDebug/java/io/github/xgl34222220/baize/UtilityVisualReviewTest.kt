package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.appearance.miuix.AppearanceScreenMiuix
import io.github.xgl34222220.baize.ui.logs.LogLevel
import io.github.xgl34222220.baize.ui.logs.LogUiItem
import io.github.xgl34222220.baize.ui.logs.LogsUiActions
import io.github.xgl34222220.baize.ui.logs.LogsUiState
import io.github.xgl34222220.baize.ui.logs.miuix.LogsScreenMiuix
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real utility pages. All device state and task results below are test fixtures only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UtilityVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun logsLight() = renderLogs("logs-light")
    @Test fun logsDark() = renderLogs("logs-dark", dark = true)
    @Test fun appearanceLight() = renderAppearance("appearance-light")
    @Test fun appearanceDark() = renderAppearance("appearance-dark", dark = true)

    private fun renderLogs(name: String, dark: Boolean = false) {
        render(name, dark) { LogsScreenMiuix(logFixture, logActions) }
        compose.onNodeWithText("运行日志").assertIsDisplayed()
    }

    private fun renderAppearance(name: String, dark: Boolean = false) {
        render(name, dark) { settings -> AppearanceScreenMiuix(settings, appearanceActions) }
        compose.onNodeWithText("界面与主题").assertIsDisplayed()
    }

    private fun render(name: String, dark: Boolean, content: @Composable (AppearanceSettings) -> Unit) {
        val settings = AppearanceSettings(
            uiStyle = UiStyle.MIUIX,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            monetEnabled = false,
            glassEnabled = true,
            blurEnabled = false,
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppearanceSettings provides settings) {
                BaiZeTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
                        content(settings)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        val bitmap = compose.runOnIdle { captureActivityContent(compose.activity) }
        require(bitmap.width >= 320 && bitmap.height >= 640) { "Unexpected utility page bounds" }
        val target = File("build/reports/ui-screenshots/utility-$name.png")
        requireNotNull(target.parentFile).mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val logFixture = LogsUiState(
        connected = true,
        ready = true,
        running = false,
        serviceText = "清理服务已连接",
        taskPhase = "上次扫描已完成，1 个目录无法读取",
        schedulerText = "安装包计划每 1 小时执行，等待下次任务",
        device = "界面验证设备",
        android = "Android 15",
        rawLogName = "示例任务输出",
        rawLog = "09:41 开始扫描\n09:41 1 个目录无法读取\n09:41 扫描完成",
        logs = listOf(
            LogUiItem(
                key = "visual-error", time = "今天 09:41", title = "示例缓存扫描",
                message = "已完成可访问目录的扫描，1 个目录无法读取。",
                trigger = "手动", bytes = 842L * 1024 * 1024, files = 128, errors = 1, level = LogLevel.ERROR,
            ),
            LogUiItem(
                key = "visual-success", time = "今天 08:30", title = "示例安装包清理",
                message = "已清理过期安装包，白名单内容已保留。",
                trigger = "自动", bytes = 612L * 1024 * 1024, files = 6, errors = 0, level = LogLevel.SUCCESS,
            ),
            LogUiItem(
                key = "visual-info", time = "昨天 22:10", title = "示例文件归类",
                message = "没有需要归类的新文件。",
                trigger = "手动", bytes = 0, files = 0, errors = 0, level = LogLevel.INFO,
            ),
        ),
    )

    private val logActions = LogsUiActions(
        onRefresh = {}, onReconnect = {}, onOpenAudit = {}, onOpenCrashDiagnostics = {},
        onClearRawLog = {}, onClearTaskLogs = {},
    )

    private val appearanceActions = AppearanceUiActions(
        onBack = {}, onUiStyle = {}, onThemeMode = {}, onSeedArgb = {}, onKolorStyle = {},
        onMonetEnabled = {}, onAmoledBlack = {}, onGlassEnabled = {}, onBlurEnabled = {},
        onFloatingDock = {}, onRefreshRateMode = {}, onAdaptiveSmoothMode = {},
    )
}
