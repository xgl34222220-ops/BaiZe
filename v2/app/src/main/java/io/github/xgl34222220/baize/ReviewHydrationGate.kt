package io.github.xgl34222220.baize

/** A screen must not overwrite disk or start a replacement before reading its last review. */
internal class ReviewHydrationGate {
    var loading: Boolean = true
        private set
    var canPersist: Boolean = false
        private set

    fun finish(success: Boolean) {
        loading = false
        canPersist = success
    }

    /** A deliberate new scan can replace an unreadable record, never an initial empty UI. */
    fun beginReplacement(): Boolean {
        if (loading) return false
        canPersist = true
        return true
    }
}
