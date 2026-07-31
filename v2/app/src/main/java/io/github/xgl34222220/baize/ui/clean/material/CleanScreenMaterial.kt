package io.github.xgl34222220.baize.ui.clean.material

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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanCategoryUiItem
import io.github.xgl34222220.baize.ui.clean.CleanScheduleMode
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.clean.TimeValueDialog
import io.github.xgl34222220.baize.ui.clean.formatMinutes

private val materialIntervals = listOf(30, 60, 180, 360, 720, 1_440, 4_320, 10_080, 43_200)

/** Material 3 独立清理页：标准控件、紧凑卡片与单独工具入口。 */
@Composable
fun CleanScreenMaterial(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showDailyTime by remember { mutableStateOf(false) }
    var showDailyGrace by remember { mutableStateOf(false) }
    var showApkDays by remember { mutableStateOf(false) }

    if (showDailyTime) {
        TimeValueDialog(
            initialHour = state.dailyHour,
            initialMinute = state.dailyMinute,
            onDismiss = { showDailyTime = false },
            onConfirm = actions.onDailyTimeChanged
        )
    }
    if (showDailyGrace) {
        IntValueDialog(
            title = "补做窗口",
            description = "固定时间到达后，如果设备条件暂时不满足，会在此时间内继续等待。",
            initialValue = state.dailyGraceMinutes,
            range = 15..720,
            suffix = "分钟",
            onDismiss = { showDailyGrace = false },
            onConfirm = actions.onDailyGraceChanged
        )
    }
    if (showApkDays) {
        IntValueDialog(
            title = "安装包保留时间",
            description = "只影响后台自动清理；手动安装包扫描始终显示全部安装包。",
            initialValue = state.apkPackageDays,
            range = 0..365,
            suffix = "天",
            onDismiss = { showApkDays = false },
            onConfirm = actions.onApkPackageDaysChanged
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { MaterialCleanTopBar() }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("计划", "类别", "工具").forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(label) }
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                item { MaterialAutomaticCard(state, actions) }
                item { MaterialSectionTitle("执行方式", state.scheduleSummary) }
                item {
                    MaterialScheduleCard(
                        state = state,
                        actions = actions,
                        onEditTime = { showDailyTime = true },
                        onEditGrace = { showDailyGrace = true }
                    )
                }
                item { MaterialSectionTitle("附加清理", "安装包使用独立保留时间") }
                item {
                    MaterialSwitchCard(
                        icon = Icons.Rounded.InstallMobile,
                        title = "过期安装包",
                        subtitle = "自动处理 APK、APKS、XAPK 与 APKM",
                        checked = state.apkPackagesEnabled,
                        onCheckedChange = actions.onApkPackagesChanged
                    )
                }
                item {
                    MaterialValueCard(
                        icon = Icons.Rounded.CalendarMonth,
                        title = "安装包保留时间",
                        subtitle = "手动扫描不受此设置影响",
                        value = if (state.apkPackageDays <= 0) "不保留" else "${state.apkPackageDays} 天",
                        enabled = state.apkPackagesEnabled,
                        onClick = { showApkDays = true }
                    )
                }
                item {
                    Button(
                        onClick = actions.onSave,
                        enabled = !state.saving,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(if (state.saving) "正在保存…" else "保存并应用", fontWeight = FontWeight.Bold)
                    }
                }
            }

            1 -> {
                item { MaterialSectionTitle("自动清理类别", "每个类别独立开关与周期") }
                state.categories.forEach { item ->
                    item(key = item.id.name) {
                        MaterialCategoryCard(
                            item = item,
                            expanded = expandedCategory == item.id.name,
                            dailyMode = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && item.id != CleanCategoryId.ORGANIZE,
                            onEnabledChanged = { actions.onCategoryEnabledChanged(item.id, it) },
                            onExpandedChanged = {
                                onExpandedCategoryChanged(if (expandedCategory == item.id.name) "" else item.id.name)
                            },
                            onIntervalChanged = { actions.onCategoryIntervalChanged(item.id, it) }
                        )
                    }
                }
            }

            else -> {
                item { MaterialSectionTitle("专项工具", "扫描与低频工具集中在这里") }
                item { MaterialToolGrid(actions) }
            }
        }
    }
}

@Composable
private fun MaterialCleanTopBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(68.dp)
    ) {
        Text("清理", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun MaterialAutomaticCard(state: CleanUiState, actions: CleanUiActions) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MaterialIconBox(Icons.Rounded.CleaningServices, primary = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("自动清理", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.automaticCleaningEnabled) "${state.enabledCategoryCount} 个类别已启用" else "所有自动任务已暂停",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = state.automaticCleaningEnabled, onCheckedChange = actions.onAutomaticCleaningChanged)
        }
    }
}

@Composable
private fun MaterialSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaterialScheduleCard(
    state: CleanUiState,
    actions: CleanUiActions,
    onEditTime: () -> Unit,
    onEditGrace: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
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
            Text(state.scheduleMode.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
        Spacer(Modifier.height(0.dp))
        MaterialValueCard(
            icon = Icons.Rounded.CalendarMonth,
            title = "执行时间",
            subtitle = "每天固定执行已启用类别",
            value = state.dailyTimeText,
            onClick = onEditTime
        )
        MaterialValueCard(
            icon = Icons.Rounded.AutoAwesome,
            title = "补做窗口",
            subtitle = "条件不满足时继续等待",
            value = formatMinutes(state.dailyGraceMinutes),
            onClick = onEditGrace
        )
    }
}

@Composable
private fun MaterialCategoryCard(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyMode: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MaterialIconBox(categoryIcon(item.id), primary = item.enabled)
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = item.enabled && !dailyMode, onClick = onExpandedChanged)
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.enabled) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (dailyMode) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Switch(checked = item.enabled, onCheckedChange = onEnabledChanged)
            }
            if (expanded && item.enabled && !dailyMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    materialIntervals.forEach { minutes ->
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
private fun MaterialSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MaterialIconBox(icon, checked)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun MaterialValueCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            MaterialIconBox(icon, enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(value, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MaterialToolGrid(actions: CleanUiActions) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialToolCard(Icons.Rounded.Search, "扫描工作台", "先扫描，再按快照清理", actions.onScan, Modifier.weight(1f), true)
            MaterialToolCard(Icons.Rounded.InstallMobile, "安装包扫描", "识别重复与过期安装包", actions.onApkScan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialToolCard(Icons.Rounded.CleaningServices, "即时缓存", "快速处理应用缓存", actions.onInstantCache, Modifier.weight(1f))
            MaterialToolCard(Icons.Rounded.FolderCopy, "文件归类", "整理下载与文档", actions.onFileOrganizer, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialToolCard(Icons.Rounded.AutoAwesome, "深度清理", "扩大范围并保留保护", actions.onDeepClean, Modifier.weight(1f))
            MaterialToolCard(Icons.Rounded.FolderDelete, "卸载残留", "检查卸载应用遗留", actions.onCorpses, Modifier.weight(1f))
        }
        MaterialToolCard(Icons.Rounded.Security, "规则与安全审计", "查看规则命中、保护项与风险", actions.onAudit, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MaterialToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = false
) {
    Card(
        modifier = modifier
            .height(112.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            MaterialIconBox(icon, true)
            Spacer(Modifier.height(9.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MaterialIconBox(icon: ImageVector, primary: Boolean) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (primary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = if (primary) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline
            )
        }
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
