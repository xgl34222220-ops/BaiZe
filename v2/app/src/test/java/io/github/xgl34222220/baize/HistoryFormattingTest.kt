package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.history.miuix.formatLifetimeElapsedCompact
import io.github.xgl34222220.baize.ui.history.miuix.splitHistoryMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryFormattingTest {
    @Test fun lifetimeElapsedIsInterpretedAsSeconds() {
        // AppTaskHistoryStore and HistoryRepository both accumulate seconds.
        assertEquals("9s", formatLifetimeElapsedCompact(9))
        assertEquals("2m 5s", formatLifetimeElapsedCompact(125))
        assertEquals("1h 0m", formatLifetimeElapsedCompact(3_600))
        assertEquals("2h 30m", formatLifetimeElapsedCompact(9_000))
        assertEquals("0s", formatLifetimeElapsedCompact(-5))
    }

    @Test fun formattedSizesSplitIntoNumberAndUnit() {
        assertEquals("12.5" to "MB", splitHistoryMetric("12.5 MB"))
        assertEquals("1,024" to "KB", splitHistoryMetric("1,024 KB"))
        assertEquals("64" to "MB", splitHistoryMetric("64\u00A0MB"))
        assertEquals("0" to "B", splitHistoryMetric(" 0 B "))
    }

    @Test fun nonSizeValuesFallBackToPlainText() {
        assertNull(splitHistoryMetric("无法测量"))
        assertNull(splitHistoryMetric("MB"))
        assertNull(splitHistoryMetric("12 MB 3"))
    }
}
