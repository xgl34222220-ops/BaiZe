package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardRefreshFeedbackTest {
    @Test fun connectedRefreshStaysQuietBecauseDataUpdatesInPlace() {
        assertNull(DashboardRefreshFeedback.message(serviceConnected = true, connecting = false))
        assertNull(DashboardRefreshFeedback.message(serviceConnected = true, connecting = true))
    }

    @Test fun refreshWithoutServiceExplainsWhatActuallyHappened() {
        assertEquals(DashboardRefreshFeedback.CONNECTING,
            DashboardRefreshFeedback.message(serviceConnected = false, connecting = true))
        assertEquals(DashboardRefreshFeedback.DISCONNECTED,
            DashboardRefreshFeedback.message(serviceConnected = false, connecting = false))
    }
}
