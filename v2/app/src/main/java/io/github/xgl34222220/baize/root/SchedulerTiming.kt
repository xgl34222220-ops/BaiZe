package io.github.xgl34222220.baize.root

/** Shared timing semantics with scheduler-v2.5.sh; completion and advice stay separate. */
internal object SchedulerTiming {
    fun intervalDue(
        now: Long,
        completedAt: Long,
        intervalSeconds: Long,
        adaptiveEnabled: Boolean,
        adaptiveDue: Long = 0L,
        adaptiveBase: Long = 0L,
        adaptiveActual: Long = 0L,
        adaptiveUpdated: Long = 0L,
        deferredUntil: Long = 0L
    ): Long {
        val actual = if (completedAt > now) now - intervalSeconds else completedAt
        var due = if (actual <= 0L) now else actual + intervalSeconds
        val adviceIsCurrent = adaptiveEnabled && adaptiveBase == intervalSeconds &&
            adaptiveActual == actual && adaptiveUpdated in (now - 7_200L)..now
        if (adviceIsCurrent) due = maxOf(due, adaptiveDue)
        return maxOf(now, due, deferredUntil)
    }
}
