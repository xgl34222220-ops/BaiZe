package io.github.xgl34222220.baize.ui.clean.material

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanCategoryUiItem
import io.github.xgl34222220.baize.ui.clean.CleanScheduleMode
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.clean.TimeValueDialog
import io.github.xgl34222220.baize.ui.clean.formatMinutes

private val intervalOptions = listOf(30, 60, 180, 360, 720, 1_440, 10_080, 43_200)

@Composable
fun CleanScreenMaterial(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showDailyTimeDialog by remember { mutableStateOf(false) }
    var showDailyGraceDialog by remember { mutableStateOf(false) }

    if (showDailyTimeDialog) {
        TimeValueDialog(
            initialHour = state.dailyHour,
            initialMinute = state.dailyMinute,
            onDismiss = { showDailyTimeDialog = false },
            onConfirm = actions.onDailyTimeChanged
        )
    }
    if (showDailyGraceDialog) {
        IntValueDialog(
            title = "补做窗口",
            description = "到达固定时间后，如果息屏、电量等条件暂时不满足，会在此时间内继续等待。",
            initialValue = state.dailyGraceMinutes,
            range = 15..720,
            suffix = "分钟",
            onDismiss = { showDailyGraceDialog = false },
            onConfirm = actions.onDailyGraceChanged
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = bottomInset + 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { MaterialCleanHeader() }
        item { MaterialAutomaticSummary(state, actions) }
        item { MaterialSectionHeader("定时模式", "智能、严格间隔或每日固定时间") }
        item {
            MaterialScheduleModeCard(
                state = state,
                actions = actions,
                onEditTime = { showDailyTimeDialog = true },
                onEditGrace = { showDailyGraceDialog = true }
            )
        }
        item { MaterialSectionHeader("任务计划", "每项任务独立开关和周期") }
        item {
            MaterialTaskPlanCard(
                state = state,
                actions = actions,
                expandedCategory = expandedCategory,
                onExpandedCategoryChanged = onExpandedCategoryChanged
            )
        }
        item { MaterialSectionHeader("附加项目", "自动处理过期安装包") }
        item { MaterialApkPackageCard(state, actions) }
        item {
            Button(
                onClick = actions.onSave,
                enabled = !state.saving,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text(if (state.saving) "正在保存…" else "保存设置", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MaterialCleanHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text("清理计划", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "选择执行模式与清理项目",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MaterialAutomaticSummary(state: CleanUiState, actions: CleanUiActions) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("自动清理", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.automaticCleaningEnabled) {
                        "已启用 ${state.enabledCategoryCount} 项自动任务"
                    } else {
                        "所有自动任务均已暂停"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = state.automaticCleaningEnabled,
                onCheckedChange = actions.onAutomaticCleaningChanged
            )
        }
    }
}

@Composable
private fun MaterialSectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaterialScheduleModeCard(
    state: CleanUiState,
    actions: CleanUiActions,
    onEditTime: () -> Unit,
    onEditGrace: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("选择执行方式", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.scheduleSummary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CleanScheduleMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.scheduleMode == mode,
                        onClick = { actions.onScheduleModeChanged(mode) },
                        label = { Text(mode.title) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                state.scheduleMode.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
            HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
            MaterialValueRow("执行时间", state.dailyTimeText, onEditTime)
            HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
            MaterialValueRow("补做窗口", formatMinutes(state.dailyGraceMinutes), onEditGrace)
            Text(
                "文件自动归类始终使用自己的独立周期。",
                modifier = Modifier.padding(start = 70.dp, end = 18.dp, bottom = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MaterialTaskPlanCard(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        state.categories.forEachIndexed { index, item ->
            val key = item.id.name
            MaterialCategoryRow(
                item = item,
                expanded = expandedCategory == key,
                dailyEnabled = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && item.id != CleanCategoryId.ORGANIZE,
                onEnabledChanged = { actions.onCategoryEnabledChanged(item.id, it) },
                onExpandedChanged = {
                    onExpandedCategoryChanged(if (expandedCategory == key) "" else key)
                },
                onIntervalChanged = { actions.onCategoryIntervalChanged(item.id, it) }
            )
            if (index != state.categories.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
            }
        }
    }
}

@Composable
private fun MaterialCategoryRow(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyEnabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        categoryIcon(item.id),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
        }
        if (item.enabled) {
            Surface(
                modifier = Modifier
                    .padding(start = 70.dp, end = 16.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onExpandedChanged),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (dailyEnabled) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行一次",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!dailyEnabled) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (expanded && !dailyEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 70.dp, end = 16.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    intervalOptions.forEach { minutes ->
                        FilterChip(
                            selected = item.intervalMinutes == minutes,
                            onClick = { onIntervalChanged(minutes) },
                            label = { Text(formatMinutes(minutes)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialApkPackageCard(state: CleanUiState, actions: CleanUiActions) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        MaterialSettingSwitchRow(
            icon = Icons.Rounded.InstallMobile,
            title = "过期安装包",
            subtitle = "保留 ${state.apkPackageDays} 天后自动清理",
            checked = state.apkPackagesEnabled,
            onCheckedChange = actions.onApkPackagesChanged
        )
    }
}

@Composable
private fun MaterialSettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MaterialValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 70.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

private fun categoryIcon(id: CleanCategoryId): ImageVector = when (id) {
    CleanCategoryId.CACHE -> Icons.Rounded.CleaningServices
    CleanCategoryId.EMPTY -> Icons.Rounded.FolderDelete
    CleanCategoryId.RULES -> Icons.Rounded.Rule
    CleanCategoryId.FRAGMENTS -> Icons.Rounded.AutoAwesome
    CleanCategoryId.DEEP -> Icons.Rounded.Security
    CleanCategoryId.ORGANIZE -> Icons.Rounded.FolderCopy
}
