package io.github.xgl34222220.baize.root

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleTodayStatsTest {
    @Test
    fun sameDayAccumulatesAndNewDayResets() {
        val previous = JSONObject().put("today", "2026-10-11").put("today_runs", 2L)
            .put("today_files", 10L).put("today_bytes", 1_048_576L)
        val same = ModuleTodayStats.next(previous, "2026-10-11", 1, 5, 1_048_576)
        assertEquals(ModuleTodayStats.Today("2026-10-11", 3, 15, 2_097_152), same)
        val next = ModuleTodayStats.next(previous, "2026-10-12", 1, 5, 7)
        assertEquals(ModuleTodayStats.Today("2026-10-12", 1, 5, 7), next)
    }

    @Test
    fun negativeInputsAndOverflowAreClamped() {
        val previous = JSONObject().put("today", "d").put("today_bytes", Long.MAX_VALUE - 1)
        val result = ModuleTodayStats.next(previous, "d", -1, -3, 10)
        assertEquals(0L, result.runs)
        assertEquals(0L, result.files)
        assertEquals(Long.MAX_VALUE, result.bytes)
    }

    @Test
    fun summaryMatchesShellFormat() {
        val text = ModuleTodayStats.summary(ModuleTodayStats.Today("d", 2, 15, 0)) { "${it} B" }
        assertEquals("今日清理 0 B · 15 项 · 2 次", text)
    }
}
