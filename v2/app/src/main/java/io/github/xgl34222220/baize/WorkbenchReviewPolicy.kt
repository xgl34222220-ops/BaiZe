package io.github.xgl34222220.baize

internal const val REVIEW_HISTORY_HINT = "列表和大小来自上次扫描，不代表清理后的剩余垃圾；重新扫描后才能继续选择。"

/** One selection gate shared by callbacks and the visible controls. */
internal fun reviewSelectionBlockReason(state: WorkbenchUiState, now: Long): String? = when {
    state.loadingResults -> "正在读取结果，读取完成后可选择"
    state.running -> "任务正在执行，请等待完成或停止"
    !state.connected -> "清理服务未连接，请重新连接后扫描"
    !state.scanReady -> "当前为历史记录，请重新扫描后选择"
    now >= state.expiresAtRealtime -> "扫描结果已过期，请重新扫描"
    else -> null
}

internal fun reviewRiskSelection(items: List<WorkbenchItem>, risks: Set<String>): Set<String> =
    items.asSequence().filter { it.selectable && it.risk in risks && it.risk in setOf("low", "medium") }
        .mapTo(linkedSetOf()) { it.id }

internal fun reviewItemRestriction(item: WorkbenchItem, global: String?): String? = when {
    item.risk == "critical" -> "关键数据不可清理；取消白名单也不会解除此限制。" + item.reason
    !item.selectable -> "不可选：" + item.reason.ifBlank { "扫描引擎已保护该项目" }
    global != null -> global
    else -> null
}

internal fun reviewRecordTitle(state: WorkbenchUiState): String = when {
    state.phase.contains("过期") -> "扫描结果已过期 · 需重新扫描"
    state.phase.contains("未完成") && state.items.all { it.outcome.isBlank() } -> "结果读取未完成 · 需重新扫描"
    state.notice == WorkbenchNotice.SUCCESS -> "清理已完成 · 记录已保留"
    state.items.any { it.outcome.isNotBlank() && it.outcome != "未勾选，保留" &&
        it.outcome !in setOf("已清理", "已按所选缓存执行清理") } -> "部分项目未完成 · 需重新扫描"
    else -> "历史记录 · 需重新扫描"
}
