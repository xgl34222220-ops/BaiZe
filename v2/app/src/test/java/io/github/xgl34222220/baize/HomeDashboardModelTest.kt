package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.storageRingFractions
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.cleanToolEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeDashboardModelTest {
    @Test fun storageRingNeverOverflowsOrGoesNegative() {
        val normal = storageRingFractions(used = 60, cleanable = 10, total = 100)
        assertEquals(.5f, normal.occupied, 1e-6f)
        assertEquals(.1f, normal.cleanable, 1e-6f)
        assertEquals(.6f, normal.used, 1e-6f)

        val overReported = storageRingFractions(used = 140, cleanable = 500, total = 100)
        assertEquals(1f, overReported.used, 1e-6f)
        assertEquals(0f, overReported.occupied, 1e-6f)

        val unknown = storageRingFractions(used = 10, cleanable = 5, total = 0)
        assertEquals(0f, unknown.used, 0f)
        val negative = storageRingFractions(used = -1, cleanable = -1, total = 100)
        assertEquals(0f, negative.used, 0f)
    }

    @Test fun cleanTabToolsAreUniqueAndEachOpensItsOwnPage() {
        val opened = mutableListOf<String>()
        val actions = CleanUiActions(
            onAutomaticCleaningChanged = {}, onCategoryEnabledChanged = { _, _ -> }, onCategoryIntervalChanged = { _, _ -> },
            onScheduleModeChanged = {}, onDailyTimeChanged = { _, _ -> }, onDailyGraceChanged = {},
            onScan = { opened += "scan" }, onLargeFiles = { opened += "large" }, onDuplicates = { opened += "duplicates" },
            onStorageView = { opened += "view:${it.name}" }, onApkScan = { opened += "apk" }, onCorpses = { opened += "corpses" },
            onFileOrganizer = { opened += "organize" }, onPhotoCompression = { opened += "photo" },
            onSwipeReview = { opened += "swipe" }, onShizukuCache = { opened += "shizuku" }
        )
        val tools = cleanToolEntries(actions)
        // 每个专项工具在清理 Tab 只出现一次，且动作互不重复（原首页微信专清/QQ 专清两行指向同一页面）。
        assertEquals(tools.size, tools.map { it.title }.toSet().size)
        assertEquals(tools.size, tools.map { it.key }.toSet().size)
        tools.forEach { it.onClick() }
        assertEquals(tools.size, opened.toSet().size)
        assertEquals(1, opened.count { it == "view:CHAT_MEDIA" })
        // 一键扫描只在首页 Hero，不在清理 Tab 的工具列表里。
        assertFalse("scan" in opened)
        assertFalse(tools.any { it.title == "一键扫描" || it.title == "存储分析" })
    }
}
