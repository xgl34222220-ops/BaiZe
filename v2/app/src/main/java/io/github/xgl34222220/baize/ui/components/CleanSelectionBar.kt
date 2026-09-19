package io.github.xgl34222220.baize.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised,
        shadowElevation = 3.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 $selectedCount 项 · $sizeLabel", Modifier.weight(1f).padding(end = 6.dp)
                    .semantics { contentDescription = "已选 $selectedCount / $totalCount 项，$sizeLabel" },
                    fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.triStateToggleable(checkState, enabled = enabled && totalCount > 0,
                        role = Role.Checkbox, onClick = onToggleAll).heightIn(min = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (allSelected) "取消全选" else selectLabel, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.width(5.dp))
                    TriStateCheckbox(checkState, onClick = null, enabled = enabled && totalCount > 0,
                        modifier = Modifier.size(24.dp))
                }
            }
            GlassActionButton(cleanLabel, onClean, Modifier.fillMaxWidth(), enabled = cleanEnabled && selectedCount > 0,
                compact = true)
        }
    }
}
