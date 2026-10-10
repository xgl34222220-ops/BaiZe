package io.github.xgl34222220.baize

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 首页「上次扫描」四格的数据来源：各专项工具（聊天媒体 / 安装包 / 大文件 / 重复文件）
 * 在用户手动扫描完成或处理后写入的一份极小摘要（每格只有容量、数量、时间）。
 *
 * - 只在对应页面自己的扫描结束时写入；首页、开机与冷启动都不会触发任何扫描。
 * - 从未扫描过的格子没有记录，首页显示「未扫描」，绝不填充估算值。
 * - 存储固定 4 个键 × 4 个字段，体积有界。
 */
@Immutable
internal data class HomeScanTile(val bytes: Long, val count: Long, val atMillis: Long, val partial: Boolean = false)

@Immutable
internal data class HomeScanSummary(
    val chat: HomeScanTile? = null,
    val apk: HomeScanTile? = null,
    val large: HomeScanTile? = null,
    val duplicates: HomeScanTile? = null
) {
    val tiles: List<HomeScanTile> get() = listOfNotNull(chat, apk, large, duplicates)
    val scanned: Boolean get() = tiles.isNotEmpty()
    val latestAtMillis: Long get() = tiles.maxOfOrNull { it.atMillis } ?: 0L
    /** 只累加已扫描的格子；四类互不重叠的来源相加不会重复计数同一类结果。 */
    val totalBytes: Long get() = tiles.sumOf { it.bytes.coerceAtLeast(0L) }
}

internal object HomeScanSummaryStore {
    const val CHAT = "chat"
    const val APK = "apk"
    const val LARGE = "large"
    const val DUPLICATES = "duplicates"
    private val keys = listOf(CHAT, APK, LARGE, DUPLICATES)
    private const val PREFS = "home-scan-summary"

    private val revision = MutableStateFlow(0L)
    /** 每次写入后递增，首页据此重新读取（同进程内即时生效）。 */
    val changes: StateFlow<Long> = revision.asStateFlow()

    fun record(context: Context, key: String, bytes: Long, count: Long, partial: Boolean = false,
               nowMillis: Long = System.currentTimeMillis()) {
        if (key !in keys) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("$key.bytes", bytes.coerceAtLeast(0L))
            .putLong("$key.count", count.coerceAtLeast(0L))
            .putLong("$key.at", nowMillis)
            .putBoolean("$key.partial", partial)
            .apply()
        revision.value = revision.value + 1
    }

    /** 读取很小的 SharedPreferences；调用方放在 IO 线程。 */
    fun read(context: Context): HomeScanSummary {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun tile(key: String): HomeScanTile? {
            val at = prefs.getLong("$key.at", 0L)
            if (at <= 0L) return null
            return HomeScanTile(prefs.getLong("$key.bytes", 0L).coerceAtLeast(0L),
                prefs.getLong("$key.count", 0L).coerceAtLeast(0L), at, prefs.getBoolean("$key.partial", false))
        }
        return HomeScanSummary(tile(CHAT), tile(APK), tile(LARGE), tile(DUPLICATES))
    }
}

/** 首页回收站卡片的只读摘要（来自 [OrdinaryFileTrash] 现有记录与 30 天保留期）。 */
@Immutable
internal data class HomeTrashSummary(val count: Int, val bytes: Long, val earliestExpiry: Long, val expired: Int)

internal object HomePresentation {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun relativeTime(atMillis: Long, nowMillis: Long): String {
        if (atMillis <= 0L) return "未扫描"
        val delta = (nowMillis - atMillis).coerceAtLeast(0L)
        return when {
            delta < MINUTE -> "刚刚"
            delta < HOUR -> "${delta / MINUTE} 分钟前"
            delta < DAY -> "${delta / HOUR} 小时前"
            delta < 30 * DAY -> "${delta / DAY} 天前"
            else -> "30 天前"
        }
    }

    /** 「今天 03:00」「明天 03:00」「10月14日 03:00」。 */
    fun nextRunLabel(epochSeconds: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
        val date: LocalDate = at.toLocalDate()
        return when (date) {
            today -> "今天 $time"
            today.plusDays(1) -> "明天 $time"
            else -> "${date.monthValue}月${date.dayOfMonth}日 $time"
        }
    }

    /**
     * 自动清理卡副标题：能确定下次时间时为「下次 <time> · 上次释放 <size>」，
     * 其余状态（执行中、排队、计算中、已关闭）返回 null，由调用方沿用原有倒计时文案。
     */
    fun planSubtitle(nextEpoch: Long, nowEpoch: Long, showsCountdown: Boolean, lastReleased: String?): String? {
        if (!showsCountdown || nextEpoch <= nowEpoch + 30L) return null
        val next = "下次 ${nextRunLabel(nextEpoch, nowEpoch * 1000L)}"
        return if (lastReleased.isNullOrBlank()) next else "$next · 上次释放 $lastReleased"
    }

    fun trashSubtitle(summary: HomeTrashSummary?, nowMillis: Long, format: (Long) -> String): String = when {
        summary == null -> "正在读取回收站"
        summary.count <= 0 -> "回收站为空"
        summary.expired > 0 -> "${summary.count} 项 · ${format(summary.bytes)} · ${summary.expired} 项已到期"
        else -> {
            val days = ((summary.earliestExpiry - nowMillis + DAY - 1) / DAY).coerceAtLeast(1L)
            "${summary.count} 项 · ${format(summary.bytes)} · $days 天后到期"
        }
    }
}
