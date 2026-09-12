package io.github.xgl34222220.baize.root

import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTimingTest {
    @Test fun `strict interval ignores old adaptive delay`() {
        assertEquals(4_000L, SchedulerTiming.intervalDue(4_000, 1_000, 3_000, false, 20_000, 3_000, 1_000, 3_900))
    }
    @Test fun `current smart schedule uses separate adaptive deadline`() {
        assertEquals(20_000L, SchedulerTiming.intervalDue(4_000, 1_000, 3_000, true, 20_000, 3_000, 1_000, 3_900))
    }
    @Test fun `interval edits and newer completions invalidate advice`() {
        assertEquals(4_000L, SchedulerTiming.intervalDue(4_000, 1_000, 3_000, true, 20_000, 6_000, 1_000, 3_900))
        assertEquals(6_500L, SchedulerTiming.intervalDue(4_000, 3_500, 3_000, true, 20_000, 3_000, 1_000, 3_900))
    }
    @Test fun `stale or future controller advice cannot freeze cleanup`() {
        assertEquals(40_000L, SchedulerTiming.intervalDue(40_000, 1_000, 3_000, true, 90_000, 3_000, 1_000, 3_900))
        assertEquals(4_000L, SchedulerTiming.intervalDue(4_000, 1_000, 3_000, true, 20_000, 3_000, 1_000, 5_000))
    }
    @Test fun `clock rollback and first run are immediately due`() {
        assertEquals(4_000L, SchedulerTiming.intervalDue(4_000, 90_000, 3_000, false))
        assertEquals(4_000L, SchedulerTiming.intervalDue(4_000, 0, 3_000, false))
    }
    @Test fun `cancellation postpones without forging completion`() {
        assertEquals(7_000L, SchedulerTiming.intervalDue(4_000, 1_000, 3_000, false, deferredUntil = 7_000))
    }
}
