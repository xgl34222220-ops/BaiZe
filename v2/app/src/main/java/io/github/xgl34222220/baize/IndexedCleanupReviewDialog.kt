package io.github.xgl34222220.baize

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton

@Composable
internal fun IndexedCleanupReviewDialog(preparing: Boolean, count: Int, message: String,
    onConfirm: () -> Unit, onCancel: () -> Unit) {
    BaiZeDialog(onDismissRequest = onCancel,
        title = { Text(when { preparing -> "正在核对已选文件"; count == 0 -> "没有可确认的文件"; else -> "删除已核对的 $count 个文件？" }) },
        text = { Text(message + "\n删除后无法在白泽内恢复，请确认不再需要。") },
        confirmButton = { val haptics = io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptics(); BaiZeDialogButton(enabled = !preparing && count > 0, onClick = { haptics.confirmDelete(); onConfirm() }) { Text("确认删除") } },
        dismissButton = { BaiZeDialogButton(onClick = onCancel) { Text("取消") } })
}
