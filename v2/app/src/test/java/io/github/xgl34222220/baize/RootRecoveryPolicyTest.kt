package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class RootRecoveryPolicyTest {
    @Test fun repeatedShortConnectionsEventuallyStop() {
        val policy = RootRecoveryPolicy()
        listOf(1_000L, 3_000L, 10_000L).forEachIndexed { index, delay ->
            policy.connected(index * 2_000L)
            policy.disconnected(index * 2_000L + 100L)
            assertEquals(delay, policy.nextDelay())
            // The last scheduled attempt must still be allowed to bind.
            assertFalse(policy.exhausted)
        }
        policy.connected(20_000)
        policy.disconnected(20_100)
        assertNull(policy.nextDelay())
        assertTrue(policy.exhausted)
    }

    @Test fun stableConnectionRestoresRetryBudget() {
        val policy = RootRecoveryPolicy()
        repeat(3) { policy.nextDelay() }
        policy.connected(0)
        policy.connected(20_000) // Another engine must not restart the stability clock.
        policy.disconnected(30_000)
        assertEquals(1_000L, policy.nextDelay())
        assertFalse(policy.exhausted)
    }

    @Test fun duplicateDisconnectDoesNotRestoreBudget() {
        val policy = RootRecoveryPolicy()
        policy.connected(0)
        policy.disconnected(100)
        assertEquals(1_000L, policy.nextDelay())
        policy.disconnected(40_000)
        assertEquals(3_000L, policy.nextDelay())
    }

    @Test fun manualRetryRestoresExhaustedBudget() {
        val policy = RootRecoveryPolicy()
        repeat(4) { policy.nextDelay() }
        assertTrue(policy.exhausted)
        policy.reset()
        assertFalse(policy.exhausted)
        assertEquals(1_000L, policy.nextDelay())
    }
}
