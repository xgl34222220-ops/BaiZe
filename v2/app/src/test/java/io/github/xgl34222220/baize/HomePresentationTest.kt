package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HomePresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun millis(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val format: (Long) -> String = { "${it}B" }

    @Test fun relativeTimeNeverInventsAScan() {
        val now = millis("2026-10-11T12:00:00")
        assertEquals("未扫描", HomePresentation.relativeTime(0L, now))
        assertEquals("刚刚", HomePresentation.relativeTime(now - 10_000L, now))
        assertEquals("5 分钟前", HomePresentation.relativeTime(now - 5 * 60_000L, now))
        assertEquals("2 小时前", HomePresentation.relativeTime(now - 2 * 3_600_000L - 1L, now))
        assertEquals("3 天前", HomePresentation.relativeTime(now - 3 * 86_400_000L, now))
    }

    @Test fun nextRunLabelUsesTodayTomorrowOrDate() {
        val now = millis("2026-10-11T02:40:00")
        assertEquals("今天 03:00", HomePresentation.nextRunLabel(millis("2026-10-11T03:00:00") / 1000, now, zone))
        assertEquals("明天 03:30", HomePresentation.nextRunLabel(millis("2026-10-12T03:30:00") / 1000, now, zone))
        assertEquals("10月14日 03:00", HomePresentation.nextRunLabel(millis("2026-10-14T03:00:00") / 1000, now, zone))
    }

    @Test fun planSubtitleFallsBackWhenNoCountdown() {
        val now = 1_000_000L
        assertNull(HomePresentation.planSubtitle(now + 3_600, now, showsCountdown = false, lastReleased = "1 GB"))
        assertNull(HomePresentation.planSubtitle(now + 10, now, showsCountdown = true, lastReleased = "1 GB"))
        val text = HomePresentation.planSubtitle(now + 3_600, now, showsCountdown = true, lastReleased = "1.6 GB")!!
        assertTrue(text.startsWith("下次 "))
        assertTrue(text.endsWith(" · 上次释放 1.6 GB"))
        assertFalse(HomePresentation.planSubtitle(now + 3_600, now, true, null)!!.contains("上次释放"))
    }

    @Test fun trashSubtitleReflectsRealEntries() {
        val now = millis("2026-10-11T12:00:00")
        assertEquals("回收站为空", HomePresentation.trashSubtitle(HomeTrashSummary(0, 0L, 0L, 0), now, format))
        assertEquals("23 项 · 100B · 7 天后到期", HomePresentation.trashSubtitle(
            HomeTrashSummary(23, 100L, now + 6 * 86_400_000L + 1L, 0), now, format))
        assertEquals("2 项 · 5B · 1 项已到期", HomePresentation.trashSubtitle(HomeTrashSummary(2, 5L, now + 1L, 1), now, format))
    }

    @Test fun summaryTotalsOnlyScannedTiles() {
        val empty = HomeScanSummary()
        assertFalse(empty.scanned)
        assertEquals(0L, empty.totalBytes)
        val summary = HomeScanSummary(chat = HomeScanTile(100, 2, 10), large = HomeScanTile(50, 1, 20))
        assertTrue(summary.scanned)
        assertEquals(150L, summary.totalBytes)
        assertEquals(20L, summary.latestAtMillis)
    }
}
