package io.github.xgl34222220.baize

/** Retry a short outage, but stop a crashing service from creating a reconnect loop. */
internal class RootRecoveryPolicy {
    private var attempts = 0
    private var connectedSince: Long? = null
    var exhausted = false
        private set

    fun connected(now: Long) {
        if (connectedSince == null) connectedSince = now
    }

    fun disconnected(now: Long) {
        if (connectedSince?.let { now - it >= 30_000L } == true) reset()
        connectedSince = null
    }

    fun nextDelay(): Long? {
        val delay = when (attempts) {
            0 -> 1_000L
            1 -> 3_000L
            2 -> 10_000L
            else -> null
        }
        if (delay == null) exhausted = true else attempts++
        return delay
    }

    fun reset() {
        attempts = 0
        connectedSince = null
        exhausted = false
    }
}
