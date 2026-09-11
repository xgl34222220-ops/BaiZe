package io.github.xgl34222220.baize

/** Publishes geometrically growing immutable reviews, keeping total copy work linear. */
internal class ProgressiveScanResults<T>(
    private val id: (T) -> String,
    private val defaultSelected: (T) -> Boolean
) {
    data class Review<T>(val items: List<T>, val selectedIds: Set<String>)

    private val items = ArrayList<T>()
    private val ids = HashSet<String>()
    private val selected = LinkedHashSet<String>()
    private var publishedSize = 0

    @Synchronized
    fun append(batch: List<T>): Review<T>? {
        batch.forEach { item ->
            val key = id(item)
            if (ids.add(key)) {
                items.add(item)
                if (defaultSelected(item)) selected.add(key)
            }
        }
        if (items.isEmpty() || (publishedSize > 0 && items.size.toLong() < publishedSize.toLong() * 2)) return null
        publishedSize = items.size
        return Review(items.toList(), selected.toSet())
    }

    @Synchronized
    fun finish(comparator: Comparator<T>): Review<T> = Review(items.sortedWith(comparator), selected.toSet())
}

/** A short page is valid; an empty page before total is not a complete review. */
internal class ScanPageCursor(private val snapshotId: String) {
    var offset: Int = 0
        private set
    var total: Int? = null
        private set
    val complete: Boolean get() = total == offset

    fun accept(returnedSnapshotId: String, returnedOffset: Int, returnedTotal: Int, count: Int) {
        check(returnedSnapshotId == snapshotId) { "扫描快照已改变，请重新扫描" }
        check(returnedOffset == offset) { "扫描结果分页位置不一致" }
        check(returnedTotal >= 0 && (total == null || total == returnedTotal)) { "扫描结果总数已改变" }
        check(count >= 0 && count <= returnedTotal - offset) { "扫描结果分页越界" }
        check(count > 0 || offset == returnedTotal) { "扫描结果尚未读取完整" }
        total = returnedTotal
        offset += count
    }
}

/** Main-thread generation guard also rejects late, non-cancellable Binder replies. */
internal class ScanLoadGeneration {
    private var generation = 0L
    private var active = false
    fun start(): Long = (++generation).also { active = true }
    fun accepts(token: Long): Boolean = active && token == generation
    fun invalidate() { generation++; active = false }
}
