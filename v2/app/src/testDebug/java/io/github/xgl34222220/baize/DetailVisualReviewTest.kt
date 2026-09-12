package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.baize.databinding.ActivityWhitelistBinding
import io.github.xgl34222220.baize.databinding.ItemWhitelistAppBinding
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
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

/** Actual secondary routes; all sample results below exist only in this visual test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailVisualReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cacheEmpty() = render("cache-empty") {
        CacheScreen(CacheUiState(connected = true, scanConnected = true, cleanConnected = true), {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun cacheResults() = render("cache-results") { cacheResultScreen() }
    @Test fun cacheDark() = render("cache-dark", dark = true) { cacheResultScreen() }

    @Test fun apkEmpty() = render("apk-empty") { ApkScanScreen(ApkScanUiState(connected = true), {}, {}, {}, {}, {}) }

    @Test fun apkResults() {
        var cleanRequests = 0
        render("apk-results") { ApkScanScreen(apkResults, {}, {}, { cleanRequests++ }, {}, {}) }
        compose.onNodeWithText("清理 121 个安装包").performClick()
        assertEquals("The result action must reuse the existing scan", 1, cleanRequests)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun apkNarrowLargeFont() = render("apk-narrow-large-font", fontScale = 1.3f) {
        ApkScanScreen(apkResults, {}, {}, {}, {}, {})
    }

    @Test fun profileResults() = render("profile-results") {
        ProfileScreenMaterial(
            ProfileUiState(
                profile = "rules", title = "规则垃圾", subtitle = "查看应用残留、隐藏垃圾和过期日志",
                connected = true, serviceText = "清理服务已连接", summaryText = "扫描完成，发现 24 项可清理内容。",
                total = 24, quickCleanReady = true, showCandidates = true, cleanButtonText = "清理 24 项规则垃圾",
                selectionText = "24 项 · 自动保留白名单中的文件", safetyText = "清理前会核对文件变化与保护设置。",
                items = listOf(ProfileUiItem("sample", "示例应用", "example.app", "临时文件", "low", "/storage/emulated/0/Android/data/example.app/cache", 512L * 1024 * 1024, 1024, 12, true, true, ""))
            ),
            ProfileUiActions({}, {}, {}, {}, {}, {}), {}
        )
    }

    @Test fun organizer() = render("organizer") {
        FileOrganizerScreen(
            FileOrganizerUiState(connected = true, status = "上次归类完成，可继续整理新下载的文件", lastTotal = 36, lastBytes = 612L * 1024 * 1024),
            FileOrganizerScheduleSettings(), "", {}, {}, {}, {}, {}, {}
        )
    }

    @Test fun whitelistXml() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val context = ContextThemeWrapper(application, ThemeManager.currentPalette(application).themeRes)
        val binding = ActivityWhitelistBinding.inflate(LayoutInflater.from(context))
        binding.selectionText.text = "已保护 2 个应用"
        binding.statusText.text = "选择要保留的应用后保存"
        binding.loadingIndicator.visibility = View.GONE
        binding.filterAll.isChecked = true
        binding.appList.layoutManager = LinearLayoutManager(context)
        binding.appList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 8
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = ItemWhitelistAppBinding.inflate(LayoutInflater.from(context), parent, false)
                return object : RecyclerView.ViewHolder(row.root) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = ItemWhitelistAppBinding.bind(holder.itemView)
                row.appName.text = listOf("示例浏览器", "示例聊天应用", "示例相册", "示例下载工具")[position % 4]
                row.packageName.text = "example.application.$position"
                row.appIcon.setImageResource(R.mipmap.ic_baize)
                row.selectedCheck.isChecked = position < 2
                row.typeText.text = "用户"
            }
        }
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, 393, 852)
        val bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888)
        binding.root.draw(Canvas(bitmap))
        save("whitelist", bitmap)
    }

    @Composable private fun cacheResultScreen() {
        CacheScreen(
            CacheUiState(
                connected = true, scanConnected = true, cleanConnected = true, phase = "扫描完成，已检查当前应用缓存。",
                snapshotId = "visual-fixture", total = 32, totalFiles = 1682, totalBytes = 842L * 1024 * 1024, quickCleanReady = true,
                items = listOf(
                    CacheCandidateUi("示例浏览器", "example.browser", "应用缓存", "/data/user/0/example.browser/cache", 524L * 1024 * 1024, 1264, 24),
                    CacheCandidateUi("示例播放器", "example.player", "外部缓存", "/storage/emulated/0/Android/data/example.player/cache", 318L * 1024 * 1024, 418, 12)
                )
            ), {}, {}, {}, {}, {}, {}, {}
        )
    }

    private val apkResults = ApkScanUiState(
        connected = true, phase = "扫描完成，安装文件来自 3 个存储来源。", cleanReady = true,
        totalFiles = 121, totalBytes = 2486L * 1024 * 1024,
        items = listOf(
            ApkScanItem("示例应用_4.2.apk", 1, 148L * 1024 * 1024, 0, "/storage/emulated/0/Download/示例应用_4.2.apk"),
            ApkScanItem("示例工具_1.6.apks", 1, 86L * 1024 * 1024, 0, "/storage/emulated/0/Download/示例工具_1.6.apks")
        ),
        coverage = listOf(ScanCoverageItem("scanned", "内部存储", 121, 2486L * 1024 * 1024, "/data/media/0", ""))
    )

    private fun render(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BaiZeTheme(AppearanceSettings(uiStyle = UiStyle.MIUIX, themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, monetEnabled = false, glassEnabled = false, blurEnabled = false)) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertIsDisplayed()
        save(name, compose.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val target = File("build/reports/ui-screenshots/detail-$name.png")
        target.parentFile.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
