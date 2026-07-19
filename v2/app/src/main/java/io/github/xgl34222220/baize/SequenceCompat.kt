package io.github.xgl34222220.baize

/** Small compatibility helper for concise tail summaries from line sequences. */
internal fun <T> Sequence<T>.takeLast(count: Int): List<T> = toList().takeLast(count)
