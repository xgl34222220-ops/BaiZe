package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class ReviewHydrationGateTest {
    @Test fun anEarlyClickOrStopCannotReplaceTheUnreadReview() {
        val gate = ReviewHydrationGate()
        assertTrue(gate.loading)
        assertFalse(gate.canPersist)
        repeat(3) { assertFalse(gate.beginReplacement()) }
        assertFalse(gate.canPersist)
        gate.finish(true)
        assertFalse(gate.loading)
        assertTrue(gate.canPersist)
        assertTrue(gate.beginReplacement())
    }

    @Test fun failedOrCancelledRestorationUnlocksActionsWithoutWritingAnEmptyRecord() {
        val gate = ReviewHydrationGate()
        gate.finish(false)
        assertFalse(gate.loading)
        assertFalse(gate.canPersist)
        assertTrue(gate.beginReplacement())
        assertTrue(gate.canPersist)
    }
}
