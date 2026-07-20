package io.github.xgl34222220.baize.ui.clean

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun IntValueDialog(
    title: String,
    description: String,
    initialValue: Int,
    range: IntRange,
    suffix: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in range

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description)
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    suffix = { Text(suffix) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && !valid,
                    supportingText = {
                        Text("允许范围：${range.first}–${range.last}$suffix")
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(requireNotNull(value))
                    onDismiss()
                }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun TimeValueDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    var hourText by remember(initialHour) { mutableStateOf(initialHour.toString()) }
    var minuteText by remember(initialMinute) { mutableStateOf(initialMinute.toString()) }
    val hour = hourText.toIntOrNull()
    val minute = minuteText.toIntOrNull()
    val valid = hour != null && hour in 0..23 && minute != null && minute in 0..59

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置每日执行时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("使用 24 小时制。到点后若执行条件不满足，会在补做窗口内继续等待。")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { input -> hourText = input.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f),
                        label = { Text("小时") },
                        placeholder = { Text("0–23") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = hourText.isNotBlank() && (hour == null || hour !in 0..23)
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { input -> minuteText = input.filter(Char::isDigit).take(2) },
                        modifier = Modifier.weight(1f),
                        label = { Text("分钟") },
                        placeholder = { Text("0–59") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = minuteText.isNotBlank() && (minute == null || minute !in 0..59)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(requireNotNull(hour), requireNotNull(minute))
                    onDismiss()
                }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
