package io.github.xgl34222220.baize.ui.clean.material

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanCategoryUiItem
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState

private data class MaterialQuickAction(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)

@Composable
fun CleanScreenMaterial(
    state: CleanUiState,
    actions: CleanUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val quickActions = listOf(
        MaterialQuickAction(Icons.Rounded.Search, "垃圾扫描", "只扫描并生成可清理快照", actions.onScan),
        MaterialQuickAction(Icons.Rounded.InstallMobile, "安装包扫描", "查找 APK、APKS 与 XAPK", actions.onApkScan),
        MaterialQuickAction(Icons.Rounded.DeleteSweep, "深度清理", "扫描日志、临时文件与常见残留", actions.onDeepClean),
        MaterialQuickAction(Icons.Rounded.FolderDelete, "卸载残留", "扫描无主 data、obb 与 media 目录", actions.onCorpses),
        MaterialQuickAction(Icons.Rounded.Rule, "清理明细", "查看规则、范围与最近命中", actions.onAudit)
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { MaterialCleanHeader() }
        item { MaterialCleanOverview(state) }
        item {
            MaterialSectionHeader(
                eyebrow = "AUTOMATIC CLEANING",
                title = "自动清理类别",
                subtitle = "两套皮肤共用同一份调度配置"
            )
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动清理总开关", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            if (state.automaticCleaningEnabled) "已启用，会按各类别周期执行" else "已关闭，手动扫描和清理仍可使用",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = state.automaticCleaningEnabled,
                        onCheckedChange = actions.onAutomaticCleaningChanged
                    )
                }
            }
        }
        items(state.categories, key = { it.id.name }) { item ->
            MaterialCategoryCard(item, actions)
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.InstallMobile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自动清理过期安装包", fontWeight = FontWeight.Bold)
                        Text(
                            "当前保留 ${state.apkPackageDays} 天",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = state.apkPackagesEnabled,
                        onCheckedChange = actions.onApkPackagesChanged
                    )
                }
            }
        }
        item {
            Button(
                onClick = actions.onSave,
                enabled = !state.saving,
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(58.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text(
                    if (state.saving) "正在保存…" else "保存自动清理设置",
                    fontWeight = FontWeight.Bold
                )
            }
        }
        item {
            MaterialSectionHeader(
                eyebrow = "MANUAL TOOLS",
                title = "手动清理工具",
                subtitle = "直接执行扫描、深度清理或查看规则明细"
            )
        }
        items(quickActions, key = { it.title }) { action ->
            OutlinedButton(
                onClick = action.onClick,
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(66.dp),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(action.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        action.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialCleanHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            "CLEANING CATEGORIES",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Spacer(Modifier.height(5.dp))
        Text("清理中心", style = MaterialTheme.typography.headlineLarge)
        Text(
            "选择自动清理类别，或直接运行手动工具",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MaterialCleanOverview(state: CleanUiState) {
    val statusText = when {
        state.running -> "清理任务执行中"
        state.scanSnapshotReady -> "扫描快照已就绪"
        state.engineReady -> "清理引擎已就绪"
        else -> "清理引擎未就绪"
    }
    val scheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)))
                .padding(22.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                if (state.engineReady || state.scanSnapshotReady) Color(0xFF7BE8B6)
                                else Color(0xFFFFD36B),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.size(9.dp))
                    Text(statusText, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "${state.enabledCategoryCount} / ${state.categories.size}",
                    color = Color.White,
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "个自动清理类别已启用",
                    color = Color.White.copy(alpha = .76f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    state.serviceText,
                    color = Color.White.copy(alpha = .68f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MaterialCategoryCard(
    item: CleanCategoryUiItem,
    actions: CleanUiActions
) {
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            categoryIcon(item.id),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { actions.onCategoryEnabledChanged(item.id, it) }
                )
            }
            if (item.enabled) {
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                Text(
                    "执行周期：${formatHours(item.intervalHours)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 6, 12, 24).forEach { hours ->
                        FilledTonalButton(
                            onClick = { actions.onCategoryIntervalChanged(item.id, hours) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (item.intervalHours == hours) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text("${hours}h", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialSectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 5.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.AutoAwesome
    CleanCategoryId.DEEP -> Icons.Rounded.Security
}

private fun formatHours(hours: Int): String = when {
    hours % 24 == 0 -> "${hours / 24} 天"
    else -> "$hours 小时"
}
