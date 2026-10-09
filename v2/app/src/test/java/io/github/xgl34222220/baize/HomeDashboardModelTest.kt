package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.storageRingFractions
import io.github.xgl34222220.baize.ui.home.miuix.homeTools
import org.junit.Assert.assertEquals
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

    @Test fun homeToolGridHasNoDuplicateEntries() {
        val opened = mutableListOf<String>()
        val actions = DashboardActions(
            refresh = {}, clean = {}, organize = {}, scan = {}, apkScan = { opened += "apk" },
            largeFiles = { opened += "large" }, duplicates = { opened += "duplicates" },
            storageAnalysis = { opened += "analysis" }, cleanScan = {}, dismissScan = {}, stop = {},
            deep = {}, corpses = {}, audit = {}, updateScheduler = {}, saveScheduler = {}, schedulerCommand = {},
            clearHistory = {}, clearRawLog = {}, reviewProtected = {}, whitelist = {}, resumableScan = {},
            theme = {}, reconnect = {}, resetScanPerformance = {}, crash = {},
            photoCompression = { opened += "photo" }, fileTrash = { opened += "trash" }, swipeReview = { opened += "swipe" },
            storageReview = { opened += it.name.lowercase() }
        )
        val tools = homeTools(actions)
        assertEquals(tools.size, tools.map { it.title }.toSet().size)
        tools.forEach { it.onClick() }
        assertEquals(listOf("photo", "duplicates", "trash", "apk", "large", "analysis", "swipe",
            "screenshots", "old_downloads", "chat_media", "custom"), opened)
        assertEquals(tools.size, opened.toSet().size)
    }
}
