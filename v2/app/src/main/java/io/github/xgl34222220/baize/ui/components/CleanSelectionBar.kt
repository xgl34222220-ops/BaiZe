package io.github.xgl34222220.baize.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** Fixed outside the scrolling results so selection and cleanup remain reachable. */
@Composable
internal fun CleanSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    sizeLabel: String,
    allSelected: Boolean,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onClean: () -> Unit,
    cleanLabel: String = "清理已选 $selectedCount 项",
    selectLabel: String = "全选",
    cleanEnabled: Boolean = enabled
) {
    val checkState = when {
        allSelected -> ToggleableState.On
        selectedCount > 0 -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    Surface(
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.triStateToggleable(checkState, enabled = enabled && totalCount > 0,
                        role = Role.Checkbox, onClick = onToggleAll).heightIn(min = 48.dp).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TriStateCheckbox(checkState, onClick = null, enabled = enabled && totalCount > 0)
                    Spacer(Modifier.width(6.dp))
                    Text(if (allSelected) "取消全选" else selectLabel, style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("已选 $selectedCount / $totalCount 项", style = MaterialTheme.typography.labelLarge)
                    Text(sizeLabel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            GlassActionButton(cleanLabel, onClean, Modifier.fillMaxWidth(), enabled = cleanEnabled && selectedCount > 0)
        }
    }
}
