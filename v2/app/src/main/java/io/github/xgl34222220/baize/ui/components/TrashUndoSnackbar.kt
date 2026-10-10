package io.github.xgl34222220.baize.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * 删除进入回收站后弹出带「撤销」的 Snackbar。
 * [batch] 是本次批次的标识（如回收记录编号列表）；为空表示没有可撤销的批次。
 * 批次变化或被清空（已撤销、重新扫描）时，旧 Snackbar 自动收起，不会对过期批次执行撤销。
 */
@Composable
internal fun TrashUndoSnackbarEffect(
    hostState: SnackbarHostState,
    batch: Any?,
    message: String,
    actionLabel: String = "撤销",
    onUndo: () -> Unit
) {
    val undo by rememberUpdatedState(onUndo)
    val haptics = rememberBaiZeHaptics()
    // 只以批次为键：页面进入运行态再恢复时不会重复弹出；撤销本身由页面状态再次拦截运行中的请求。
    LaunchedEffect(hostState, batch) {
        if (batch == null) {
            hostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        hostState.currentSnackbarData?.dismiss()
        val result = hostState.showSnackbar(message, actionLabel = actionLabel, withDismissAction = true,
            duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            haptics.tick()
            undo()
        }
    }
}
