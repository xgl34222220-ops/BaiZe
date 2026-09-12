package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.RootJsonTransportException
import org.json.JSONObject

/** Tracks whether an unsuccessful UI attempt can still use its original immutable review. */
internal class CleanupAttempt {
    var snapshotTouched: Boolean = false
        private set
    var cleanupSubmitted: Boolean = false
        private set

    fun authorize(block: () -> String): String = execute(authorization = true, block)
    fun mutate(block: () -> String): String = execute(authorization = false, block)

    private fun execute(authorization: Boolean, block: () -> String): String {
        val previousSnapshot = snapshotTouched
        val previousCleanup = cleanupSubmitted
        if (authorization) snapshotTouched = true else cleanupSubmitted = true
        try {
            val result = block()
            if (JSONObject(result).optBoolean("requestRejected")) {
                snapshotTouched = previousSnapshot
                cleanupSubmitted = previousCleanup
            }
            return result
        } catch (error: RootJsonTransportException) {
            if (!error.requestSubmitted) {
                snapshotTouched = previousSnapshot
                cleanupSubmitted = previousCleanup
            }
            throw error
        }
    }
}
