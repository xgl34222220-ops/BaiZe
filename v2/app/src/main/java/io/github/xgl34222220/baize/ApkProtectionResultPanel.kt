package io.github.xgl34222220.baize

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApkProtectionResultPanel(details: ApkProtectionDetails, historical: Boolean = false,
    enabled: Boolean = true, onManage: () -> Unit, onTrash: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text((if (historical) "上次核对 · " else "保护原因 · ") + details.summary,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow {
            TextButton({ expanded = true }) { Text("查看保护原因") }
            when {
                details.internalTrash -> TextButton(onTrash, enabled = enabled) { Text("到回收站处理") }
                details.manageable -> TextButton(onManage, enabled = enabled) { Text("管理此文件保护") }
            }
        }
    }
    if (expanded) BaiZeDialog(onDismissRequest = { expanded = false }, title = { Text("此文件的保护原因") },
        text = { Column {
            if (historical) Text("以下为上次核对结果。请重新扫描确认当前规则。")
            SelectionContainer { Text(details.explanation) }
        } },
        confirmButton = { BaiZeDialogButton({ expanded = false }) { Text("知道了") } })
}
