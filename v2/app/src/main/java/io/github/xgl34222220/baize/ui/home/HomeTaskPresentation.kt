package io.github.xgl34222220.baize.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xgl34222220.baize.SchedulerUiState
import kotlinx.coroutines.delay

internal data class HomeTaskPresentation(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val nextEpoch: Long
)

internal fun SchedulerUiState.homeTaskItems(): List<HomeTaskPresentation> = listOf(
    HomeTaskPresentation("cache", "应用缓存", cacheEnabled && enabled, cacheNextEpoch),
    HomeTaskPresentation("empty", "空文件与空目录", emptyEnabled && enabled, emptyNextEpoch),
    HomeTaskPresentation("rules", "规则垃圾与日志", rulesEnabled && enabled, rulesNextEpoch),
    HomeTaskPresentation("fragment", "残留碎片", fragmentEnabled && enabled, fragmentNextEpoch),
    HomeTaskPresentation("deep", "深度清理", deepEnabled && enabled, deepNextEpoch),
    HomeTaskPresentation("organize", "文件自动归类", organizeEnabled && enabled, organizeNextEpoch)
)

internal fun List<HomeTaskPresentation>.nextTask(nowEpoch: Long): HomeTaskPresentation? {
    val enabledItems = filter(HomeTaskPresentation::enabled)
    if (enabledItems.isEmpty()) return null
    enabledItems.firstOrNull { it.nextEpoch in 1..nowEpoch }?.let { return it }
    return enabledItems
        .filter { it.nextEpoch > nowEpoch }
        .minByOrNull(HomeTaskPresentation::nextEpoch)
        ?: enabledItems.first()
}

internal fun taskCountdownLabel(task: HomeTaskPresentation?, nowEpoch: Long): String {
    if (task == null || !task.enabled) return "自动任务已关闭"
    val remaining = task.nextEpoch - nowEpoch
    if (task.nextEpoch <= 0L) return "正在计算执行时间"
    if (remaining <= 30L) return "已到期，等待自动执行"
    val minutes = (remaining + 59L) / 60L
    if (minutes < 60L) return "还有 ${minutes} 分钟执行"
    val hours = minutes / 60L
    val minutePart = minutes % 60L
    if (hours < 24L) {
        return if (minutePart == 0L) {
            "还有 ${hours} 小时执行"
        } else {
            "还有 ${hours} 小时 ${minutePart} 分钟执行"
        }
    }
    val days = hours / 24L
    val hourPart = hours % 24L
    return if (hourPart == 0L) {
        "还有 ${days} 天执行"
    } else {
        "还有 ${days} 天 ${hourPart} 小时执行"
    }
}

@Composable
internal fun rememberHomeNowEpoch(): Long {
    var nowEpoch by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowEpoch = System.currentTimeMillis() / 1000L
        }
    }
    return nowEpoch
}
