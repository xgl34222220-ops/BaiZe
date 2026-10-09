package io.github.xgl34222220.baize

import java.io.File

/**
 * 目录占用的层级索引（参考 SD Maid SE StorageAnalyzer 的逐层钻取与 XClean 的环形占用图）。
 * 由扫描线程一次性构建：父目录 -> 按大小降序的子目录，之后钻取、分页和环形图都是 O(子项) 查询，
 * 不再在主线程对上万个目录做全量过滤。
 */
internal class DirectoryUsageTree(roots: List<String>, directories: List<StorageDirectory>) {
    private val rootList: List<StorageDirectory>
    private val childrenByParent: Map<String, List<StorageDirectory>>
    private val byPath: Map<String, StorageDirectory>

    init {
        val rootSet = roots.toSet()
        val tops = ArrayList<StorageDirectory>()
        val grouped = HashMap<String, MutableList<StorageDirectory>>()
        val paths = HashMap<String, StorageDirectory>(directories.size * 2)
        for (directory in directories) {
            paths[directory.path] = directory
            if (directory.path in rootSet) tops += directory
            else File(directory.path).parent?.let { grouped.getOrPut(it) { ArrayList() } += directory }
        }
        val order = compareByDescending<StorageDirectory> { it.bytes }.thenBy { it.path }
        rootList = tops.sortedWith(order)
        childrenByParent = grouped.mapValues { (_, list) -> list.sortedWith(order) }
        byPath = paths
    }

    fun directory(path: String?): StorageDirectory? = path?.let(byPath::get)

    fun children(parent: String?): List<StorageDirectory> =
        if (parent == null) rootList else childrenByParent[parent].orEmpty()

    /** 分页读取子目录；page 从 0 开始。 */
    fun page(parent: String?, page: Int, pageSize: Int = PAGE_SIZE): List<StorageDirectory> {
        require(pageSize > 0 && page >= 0)
        val all = children(parent)
        val from = page.toLong() * pageSize
        if (from >= all.size) return emptyList()
        return all.subList(from.toInt(), minOf(all.size, from.toInt() + pageSize))
    }

    /**
     * 环形图扇区：内圈为当前目录的子目录，外圈为孙目录，角度按占当前目录总量的比例。
     * 小于 [minFraction] 的扇区合并为“其他”（path = null，不可钻取）；目录自身直属文件留作空隙。
     */
    fun sunburst(parent: String?, depth: Int = 2, minFraction: Double = .015, maxPerRing: Int = 24): List<SunburstSegment> {
        val total = (directory(parent)?.bytes ?: children(null).sumOf { it.bytes }).coerceAtLeast(0)
        if (total <= 0L) return emptyList()
        val segments = ArrayList<SunburstSegment>()
        fun ring(parentPath: String?, level: Int, start: Double, span: Double, parentBytes: Long) {
            if (level >= depth || parentBytes <= 0L) return
            var cursor = start
            var otherBytes = 0L
            var shown = 0
            for (child in children(parentPath)) {
                if (child.bytes <= 0L) continue
                val fraction = child.bytes.toDouble() / total
                val sweep = span * child.bytes.toDouble() / parentBytes
                if (fraction < minFraction || shown >= maxPerRing) { otherBytes += child.bytes; continue }
                segments += SunburstSegment(child.path, child.path.substringAfterLast('/'), level, cursor, sweep, child.bytes)
                ring(child.path, level + 1, cursor, sweep, child.bytes)
                cursor += sweep
                shown++
            }
            if (otherBytes > 0L) {
                val sweep = span * otherBytes.toDouble() / parentBytes
                segments += SunburstSegment(null, "其他", level, cursor, sweep, otherBytes)
            }
        }
        ring(parent, 0, 0.0, 1.0, total)
        return segments
    }

    companion object { const val PAGE_SIZE = 40 }
}

/** start / sweep 为 0..1 的圆周比例。 */
internal data class SunburstSegment(val path: String?, val label: String, val level: Int,
    val start: Double, val sweep: Double, val bytes: Long) {
    fun contains(fraction: Double): Boolean = fraction >= start && fraction < start + sweep
}

/**
 * 把点击位置换算成扇区。角度从 12 点方向顺时针；半径按环宽分层，中心圆内返回 null。
 */
internal fun sunburstHit(segments: List<SunburstSegment>, dx: Float, dy: Float, innerRadius: Float, ringWidth: Float): SunburstSegment? {
    val radius = kotlin.math.sqrt(dx * dx + dy * dy)
    if (radius < innerRadius || ringWidth <= 0f) return null
    val level = ((radius - innerRadius) / ringWidth).toInt()
    var angle = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble()))
    if (angle < 0) angle += 360.0
    val fraction = angle / 360.0
    return segments.firstOrNull { it.level == level && it.contains(fraction) }
}
