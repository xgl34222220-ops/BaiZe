package io.github.xgl34222220.baize.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.selection.triStateToggleable
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
    // HyperOS 垃圾清理底栏：浅色渐隐底 + 一行选择摘要 + 整宽主色胶囊（带实时总量）。
    val base = BaiZeTokens.colors.surfaceBase
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(base.copy(alpha = 0f), base.copy(alpha = .96f), base), endY = 48f))
            .navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $selectedCount 项 · $sizeLabel", Modifier.weight(1f).padding(end = 6.dp)
                .semantics { contentDescription = "已选 $selectedCount / $totalCount 项，$sizeLabel" },
                fontSize = 13.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.clip(CircleShape).triStateToggleable(checkState, enabled = enabled && totalCount > 0,
                    role = Role.Checkbox, onClick = onToggleAll).heightIn(min = 36.dp).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (allSelected) "取消全选" else selectLabel, fontSize = 13.sp, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                BaiZeRoundCheck(checkState, onClick = null, enabled = enabled && totalCount > 0,
                    modifier = Modifier.padding(end = 4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        BaiZePillButton(cleanLabel, onClean, Modifier.fillMaxWidth(),
            trailing = if (selectedCount > 0 && sizeLabel.any(Char::isDigit)) sizeLabel else "",
            enabled = cleanEnabled && selectedCount > 0)
    }
}
