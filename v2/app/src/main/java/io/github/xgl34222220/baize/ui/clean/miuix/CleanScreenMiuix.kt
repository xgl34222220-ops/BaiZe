package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState

/**
 * Status and configuration page for BaiZe automatic cleaning.
 * Manual scan, clean, wake, stop and diagnostic actions are deliberately not rendered.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTitle = when {
        state.running -> "白泽正在后台清理"
        state.automaticCleaningEnabled && state.engineReady -> "全自动清理已运行"
        state.automaticCleaningEnabled -> "全自动清理已开启"
        else -> "全自动清理已关闭"
    }
    val statusBody = when {
        state.running -> "任务由独立 Root Worker 执行，关闭 App 也不会中断。"
        state.automaticCleaningEnabled -> "系统会自行等待合适时机、清理垃圾并自动恢复，无需手动扫描或唤醒。"
        else -> "开启后，缓存、空项目、规则垃圾和碎片会按计划自动处理。"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    "AUTOMATIC CLEAN",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.1.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("自动清理", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
                Text(
                    "设置一次，后续由白泽自动完成",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.automaticCleaningEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(statusTitle, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text(
                                statusBody,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Switch(
                            checked = state.automaticCleaningEnabled,
                            onCheckedChange = actions.onAutomaticCleaningChanged
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("执行计划", fontWeight = FontWeight.Bold)
                            Text(
                                state.scheduleSummary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("自动处理范围", fontWeight = FontWeight.Bold)
                            Text(
                                "已启用 ${state.enabledCategoryCount}/${state.categories.size} 类安全清理任务",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = actions.onSave,
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(58.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Text(if (state.saving) "正在保存…" else "保存自动清理设置", fontWeight = FontWeight.Black)
            }
        }

        item {
            Text(
                "所有清理与文件归类均由后台自动执行。扫描、立即清理、停止、唤醒和开发者诊断按钮已全部移除。",
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
    }
}
