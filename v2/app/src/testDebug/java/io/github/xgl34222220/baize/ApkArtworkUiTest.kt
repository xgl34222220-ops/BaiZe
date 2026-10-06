package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApkArtworkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var cleanCalls = 0
    private val icon get() = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(62, 123, 250)) }
    private fun item(index: Int) = ApkScanItem("归档-$index.apk", 1, 12_345_678, 0,
        "/synthetic/download/归档-$index.apk", "content://media/$index", modifiedSeconds = 42)
    private fun parsed() = ApkArchiveInfo("示例归档应用", "synthetic.archive", "1.2.3", "1.0.0", ApkInstallStatus.NEWER,
        iconBitmap = icon, parseStatus = ApkArchiveParseStatus.PARSED)

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun copyingSingleFileDiagnosticsIsReachableAndNeverSelectsOrDeletes() {
        val first = item(1).copy(retainedReason = ApkIndexedDeleteResult.UNVERIFIED.retainedReason(),
            archive = ApkArchiveInfo(parseStatus = ApkArchiveParseStatus.FAILED, failureReason = ApkArchiveFailure.INACCESSIBLE))
        val state = ready(listOf(first))
        var diagnosticCalls = 0
        val report = "{\"testData\":true,\"allFilesAccess\":true,\"errno\":13}"
        render(dark = true, fontScale = 1.5f) { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, {},
            diagnoseFile = { requested -> assertEquals(first.uri, requested.uri); diagnosticCalls++; report }) }
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(first.name))
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("复制读取诊断").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("已复制当前文件的读取诊断").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已复制当前文件的读取诊断").performScrollTo().assertIsDisplayed()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(report, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, diagnosticCalls); assertEquals(0, cleanCalls); assertTrue(state.selected.isEmpty())
        save("apk-single-file-diagnostic-dark-large-font")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun confirmedMissingIndexProducesAZeroByteReviewWithoutACleanupAction() {
        val state = ApkScanUiState(totalFiles = 0, totalBytes = 0, confirmedMissingRecords = 1,
            phase = "当前 0 个安装包 · 已排除 1 条不存在的旧记录",
            coverage = listOf(ScanCoverageItem("scanned", "系统索引", 0, 0, "content://media/external/file", "已核对")))
        var scans = 0
        render(dark = true, fontScale = 1.5f) { ApkScanScreen(state, {}, { scans++ }, { cleanCalls++ }, {}, {}) }
        compose.onNodeWithText("0 个安装文件").assertIsDisplayed()
        compose.onNodeWithText("已确认 1 条旧记录对应的文件不存在，已从结果和容量中排除；没有删除文件。").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("清理已选", substring = true).assertCountEquals(0)
        assertEquals(0L, state.selectedBytes)
        save("apk-missing-index-empty-dark-large-font")
        compose.onNodeWithText("重新扫描").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, scans); assertEquals(0, cleanCalls)
    }

    @Test fun archiveArtworkNamesAndVersionsAreVisibleAndDetailsDoNotSelectOrDelete() {
        val first = item(1).copy(archive = parsed())
        val failed = item(2).copy(archive = ApkArchiveInfo(parseStatus = ApkArchiveParseStatus.FAILED,
            failureReason = ApkArchiveFailure.INVALID_ARCHIVE))
        var state by mutableStateOf(ready(listOf(first, failed)))
        render { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, {},
            onToggle = { uri -> state = state.copy(selected = state.selected + uri) }) }
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText("示例归档应用"))
        compose.onNodeWithText("示例归档应用").assertIsDisplayed()
        compose.onNodeWithContentDescription("来自安装包的应用图标", useUnmergedTree = true).assertIsDisplayed()
        val bounds = compose.onNodeWithTag("apk-artwork:${first.uri}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(48f, bounds.width, .1f); assertEquals(48f, bounds.height, .1f)
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("安装包详情").assertIsDisplayed()
        compose.onNodeWithText("完整路径\n${first.samplePath}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        assertTrue(state.selected.isEmpty()); assertEquals(0, cleanCalls)
        compose.onNodeWithContentDescription("选择安装包${first.name}").performClick()
        assertEquals(setOf(first.uri), state.selected)
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(failed.name))
        compose.onNodeWithContentDescription("安装包默认图标", useUnmergedTree = true).assertIsDisplayed()
        save("apk-archive-artwork-light")
    }

    @Test fun aThousandArchivesDecodeOnlyComposedRows() {
        var reads = 0
        val state = ready((0 until 1000).map(::item))
        render { ApkScanScreen(state, {}, {}, {}, {}, {}, loadArchive = { reads++; parsed() }) }
        compose.waitUntil(5_000) { reads > 0 }
        assertTrue("Offscreen archives must not be decoded eagerly: $reads", reads < 30)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun retainedProtectionReasonIsReadableInDarkLargeTextAndDetailsDoNotClean() {
        val first = item(1).copy(name = "仍需保留的长文件名称".repeat(12) + ".apk", archive = parsed(),
            retainedReason = ApkIndexedDeleteResult.PROTECTED.retainedReason())
        render(dark = true, fontScale = 1.5f) {
            ApkScanScreen(ready(listOf(first)).copy(selected = setOf(first.uri),
                phase = "清理完成：删除 1 个，保留 1 个", output = "已删除 1 个，实际释放 8 MB"),
                {}, {}, { cleanCalls++ }, {}, {})
        }
        compose.onNodeWithText("移入回收站 1 个安装包").assertIsDisplayed()
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(first.retainedReason))
        compose.onNodeWithText(first.retainedReason).assertIsDisplayed()
        save("apk-retained-protection-dark-large-font")
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("处理结果\n${first.retainedReason}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        assertEquals(0, cleanCalls)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun protectionRecoveryActionsWrapAndCannotDeleteFromTheErrorMessage() {
        var reconnects = 0
        var localModes = 0
        val state = ready(listOf(item(1))).copy(protectionNeedsAction = true, localModeAvailable = true,
            protectionMessage = "保护名单尚未核对，已保留文件。请连接 Root 后重试。")
        render(fontScale = 1.5f) { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, { reconnects++ },
            onLocalMode = { localModes++ }) }
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText("重连保护服务"))
        compose.onNodeWithText("重连保护服务").assertIsDisplayed().performClick()
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText("仅本地清理"))
        compose.onNodeWithText("仅本地清理").assertIsDisplayed().performClick()
        save("apk-protection-recovery-large-font")
        assertEquals(1, reconnects); assertEquals(1, localModes); assertEquals(0, cleanCalls)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w740dp-h320dp-mdpi")
    fun changedFileReasonAndFullPathRemainReachableInLandscape() {
        val first = item(1).copy(archive = parsed(), retainedReason = ApkIndexedDeleteResult.CHANGED.retainedReason(),
            samplePath = "/synthetic/" + "很长的下载文件夹/".repeat(12) + "package.apk")
        render(dark = true, fontScale = 1.5f) { ApkScanScreen(ready(listOf(first)), {}, {}, { cleanCalls++ }, {}, {}) }
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(first.name))
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("完整路径\n${first.samplePath}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("处理结果\n${first.retainedReason}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed()
        save("apk-retained-details-landscape")
        assertEquals(0, cleanCalls)
    }

    @Test fun emptyMetadataCacheCanAdvanceAVersionFilterWithoutSelectingUnknownArchives() {
        val release = CompletableDeferred<Unit>()
        var state by mutableStateOf(ready(listOf(item(1), item(2))))
        render { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, {},
            onFilter = { state = state.copy(filter = it, selected = emptySet()) },
            onToggleAll = { state = state.toggleAllSelection() },
            loadArchive = { candidate ->
                release.await()
                val result = parsed().copy(status = if (candidate.uri == item(2).uri) ApkInstallStatus.OLDER else ApkInstallStatus.NEWER)
                state = state.copy(items = state.items.map {
                    if (it.previewKey == candidate.previewKey) it.copy(archive = result.copy(iconBitmap = null)) else it
                })
                result
            }) }
        compose.onNodeWithContentDescription("筛选安装包").performScrollTo().performClick()
        compose.onNode(hasText("低于已装版本") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithText("全选").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, state.visibleItems.size)
            assertTrue(state.selected.isEmpty())
            release.complete(Unit)
        }
        compose.waitUntil(5_000) { state.items.none { it.archive.awaitingInspection } }
        compose.onNodeWithText("全选").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(setOf(item(2).uri), state.selected)
            assertEquals(listOf(item(2).uri), state.visibleItems.map { it.uri })
        }
        assertEquals(0, cleanCalls)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
    fun darkLargeTextKeepsLongArchiveDetailsAndSelectionReachable() {
        val first = item(1).copy(name = "W".repeat(251) + ".apk", archive = parsed(),
            samplePath = "/synthetic/" + "长目录/".repeat(20) + "W".repeat(251) + ".apk")
        val state = ready(listOf(first)).copy(selected = setOf(first.uri))
        render(dark = true, fontScale = 1.5f) { ApkScanScreen(state, {}, {}, { cleanCalls++ }, {}, {}) }
        compose.onNodeWithText("移入回收站 1 个安装包").assertIsDisplayed()
        compose.onNodeWithTag("apk-results-list").performScrollToNode(hasText(first.name))
        save("apk-archive-artwork-dark-large-font")
        compose.onNodeWithText(first.name).performClick()
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onNodeWithText("完整路径\n${first.samplePath}").performScrollTo().assertIsDisplayed()
        save("apk-archive-details-dark-large-font")
        compose.onNodeWithText("完成").performClick()
        assertEquals(0, cleanCalls)
    }

    private fun ready(items: List<ApkScanItem>) = ApkScanUiState(connected = true, cleanReady = true,
        phase = "安装包扫描完成", items = items, totalFiles = items.size.toLong(), totalBytes = items.sumOf { it.bytes })
    private fun render(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val appearance = AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            monetEnabled = false, blurEnabled = false, glassEnabled = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalAppearanceSettings provides appearance) {
                BaiZeTheme(appearance) { content() }
            }
        }
    }
    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            if (dialog == null) captureActivityContent(compose.activity)
            else Bitmap.createBitmap(dialog.width, dialog.height, Bitmap.Config.ARGB_8888).also { dialog.draw(Canvas(it)) }
        }
        File("build/reports/ui-screenshots/$name.png").apply {
            parentFile!!.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
