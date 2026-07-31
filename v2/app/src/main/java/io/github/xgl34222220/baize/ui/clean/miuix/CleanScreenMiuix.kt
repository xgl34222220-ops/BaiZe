package io.github.xgl34222220.baize.ui.clean.miuix

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanCategoryUiItem
import io.github.xgl34222220.baize.ui.clean.CleanScheduleMode
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.clean.TimeValueDialog
import io.github.xgl34222220.baize.ui.clean.formatMinutes
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

private val miuixIntervals = listOf(30, 60, 180, 360, 720, 1_440, 4_320, 10_080, 43_200)

/** MIUIX 独立清理页：大标题、超椭圆独立卡片、较大行高与自有标签组件。 */
@Composable
fun CleanScreenMiuix(
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
            description = "固定时间到达后，如果执行条件暂时不满足，会在此时间内继续等待。",
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
            description = "只影响后台自动清理。手动安装包扫描始终显示全部安装包。",
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixCleanHeader() }
        item {
            MiuixTabs(
                labels = listOf("计划", "类别", "工具"),
                selected = selectedTab,
                onSelected = { selectedTab = it }
            )
        }

        when (selectedTab) {
            0 -> {
                item { MiuixAutomaticCard(state, actions) }
                item { MiuixSectionTitle("执行方式", state.scheduleSummary) }
                item {
                    MiuixScheduleCard(
                        state = state,
                        actions = actions,
                        onEditTime = { showDailyTime = true },
                        onEditGrace = { showDailyGrace = true }
                    )
                }
                item { MiuixSectionTitle("附加清理", "安装包使用独立保留时间") }
                item {
                    MiuixSwitchCard(
                        icon = Icons.Rounded.InstallMobile,
                        title = "过期安装包",
                        subtitle = "自动处理 APK、APKS、XAPK 与 APKM",
                        checked = state.apkPackagesEnabled,
                        onCheckedChange = actions.onApkPackagesChanged
                    )
                }
                item {
                    MiuixValueCard(
                        icon = Icons.Rounded.CalendarMonth,
                        title = "安装包保留时间",
                        subtitle = "手动安装包扫描不受影响",
                        value = if (state.apkPackageDays <= 0) "不保留" else "${state.apkPackageDays} 天",
                        enabled = state.apkPackagesEnabled,
                        onClick = { showApkDays = true }
                    )
                }
                item {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 18.dp)
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = !state.saving, onClick = actions.onSave),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (state.saving) "正在保存…" else "保存并应用",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            1 -> {
                item { MiuixSectionTitle("自动清理类别", "每个类别保持独立卡片与独立周期") }
                state.categories.forEach { item ->
                    item(key = item.id.name) {
                        MiuixCategoryCard(
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
                item { MiuixSectionTitle("专项工具", "低频入口不与自动计划混在一起") }
                item { MiuixToolGrid(actions) }
            }
        }
    }
}

@Composable
private fun MiuixCleanHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 18.dp, top = 24.dp, bottom = 8.dp)
    ) {
        Text("清理", fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("计划、类别与专项工具", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

@Composable
private fun MiuixTabs(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Surface(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelected(index) },
                shape = RoundedCornerShape(14.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceRaised,
                contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MiuixAutomaticCard(state: CleanUiState, actions: CleanUiActions) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 19.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiuixIconBox(Icons.Rounded.CleaningServices, true, large = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("自动清理", fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (state.automaticCleaningEnabled) "${state.enabledCategoryCount} 个类别已启用" else "所有自动任务已暂停",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Switch(checked = state.automaticCleaningEnabled, onCheckedChange = actions.onAutomaticCleaningChanged)
        }
    }
}

@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun MiuixScheduleCard(
    state: CleanUiState,
    actions: CleanUiActions,
    onEditTime: () -> Unit,
    onEditGrace: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CleanScheduleMode.entries.forEach { mode ->
                    val active = state.scheduleMode == mode
                    Surface(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .clickable { actions.onScheduleModeChanged(mode) },
                        shape = RoundedCornerShape(13.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceOverlay,
                        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            Text(mode.title, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(state.scheduleMode.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
        MiuixValueCard(
            icon = Icons.Rounded.CalendarMonth,
            title = "执行时间",
            subtitle = "每天固定执行已启用类别",
            value = state.dailyTimeText,
            onClick = onEditTime
        )
        MiuixValueCard(
            icon = Icons.Rounded.AutoAwesome,
            title = "补做窗口",
            subtitle = "条件不满足时继续等待",
            value = formatMinutes(state.dailyGraceMinutes),
            onClick = onEditGrace
        )
    }
}

@Composable
private fun MiuixCategoryCard(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyMode: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiuixIconBox(categoryIcon(item.id), item.enabled)
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = item.enabled && !dailyMode, onClick = onExpandedChanged)
                ) {
                    Text(item.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.enabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (dailyMode) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
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
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    miuixIntervals.forEach { minutes ->
                        val active = item.intervalMinutes == minutes
                        Surface(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onIntervalChanged(minutes) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else BaiZeTokens.colors.surfaceOverlay,
                            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                Text(formatMinutes(minutes), fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixSwitchCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            MiuixIconBox(icon, checked)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun MiuixValueCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            MiuixIconBox(icon, enabled)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Text(
                value,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun MiuixToolGrid(actions: CleanUiActions) {
    Column(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MiuixToolCard(Icons.Rounded.Search, "扫描工作台", "按不可变快照清理", actions.onScan, Modifier.weight(1f), true)
            MiuixToolCard(Icons.Rounded.InstallMobile, "安装包扫描", "识别重复与过期安装包", actions.onApkScan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MiuixToolCard(Icons.Rounded.CleaningServices, "即时缓存", "快速处理应用缓存", actions.onInstantCache, Modifier.weight(1f))
            MiuixToolCard(Icons.Rounded.FolderCopy, "文件归类", "整理下载与文档", actions.onFileOrganizer, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MiuixToolCard(Icons.Rounded.AutoAwesome, "深度清理", "扩大范围并保留保护", actions.onDeepClean, Modifier.weight(1f))
            MiuixToolCard(Icons.Rounded.FolderDelete, "卸载残留", "检查卸载应用遗留", actions.onCorpses, Modifier.weight(1f))
        }
        MiuixToolCard(Icons.Rounded.Security, "规则与安全审计", "查看规则命中、保护项与潜在风险", actions.onAudit, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MiuixToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = false
) {
    Surface(
        modifier = modifier
            .height(120.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = if (primary) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .76f) else BaiZeTokens.colors.surfaceRaised
    ) {
        Column(Modifier.padding(15.dp)) {
            MiuixIconBox(icon, true)
            Spacer(Modifier.height(10.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MiuixIconBox(icon: ImageVector, enabled: Boolean, large: Boolean = false) {
    Box(
        modifier = Modifier
            .size(if (large) 48.dp else 42.dp)
            .clip(RoundedCornerShape(if (large) 16.dp else 14.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(if (large) 25.dp else 21.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
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
