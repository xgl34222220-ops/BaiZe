package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val intervals = listOf(30, 60, 180, 360, 720, 1_440, 4_320, 10_080, 43_200)

/**
 * 真正的 MIUIX 清理页。页面容器、按钮、卡片、开关与偏好项均来自
 * compose-miuix-ui；Material 只保留原有业务对话框，避免更改数据逻辑。
 */
@Composable
fun CleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
        contentPadding = PaddingValues(bottom = bottomInset + 132.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Header() }
        item { Tabs(tab = tab, onTabChanged = { tab = it }) }

        when (tab) {
            0 -> {
                item { AutomaticCard(state, actions) }
                item { SectionTitle("执行方式", state.scheduleSummary) }
                item {
                    ScheduleCard(
                        state = state,
                        actions = actions,
                        onEditTime = { showDailyTime = true },
                        onEditGrace = { showDailyGrace = true }
                    )
                }
                item { SectionTitle("附加清理", "安装包自动清理使用独立保留时间") }
                item {
                    PreferenceCard {
                        SwitchPreference(
                            checked = state.apkPackagesEnabled,
                            onCheckedChange = actions.onApkPackagesChanged,
                            title = "过期安装包",
                            summary = "自动处理 APK、APKS、XAPK 与 APKM",
                            startAction = {
                                PreferenceIcon(Icons.Rounded.InstallMobile, state.apkPackagesEnabled)
                            }
                        )
                    }
                }
                item {
                    ValueCard(
                        icon = Icons.Rounded.CalendarMonth,
                        title = "安装包保留时间",
                        summary = "手动扫描不受此设置影响",
                        value = if (state.apkPackageDays <= 0) "不保留" else "${state.apkPackageDays} 天",
                        enabled = state.apkPackagesEnabled,
                        onClick = { showApkDays = true }
                    )
                }
                item {
                    MiuixButton(
                        onClick = actions.onSave,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = !state.saving,
                        minHeight = 50.dp,
                        cornerRadius = 18.dp,
                        colors = MiuixButtonDefaults.buttonColorsPrimary(),
                        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 11.dp)
                    ) {
                        MiuixText(
                            if (state.saving) "正在保存…" else "保存并应用",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            1 -> {
                item { SectionTitle("自动清理类别", "每个类别独立开关、周期与状态") }
                state.categories.forEach { item ->
                    item(key = item.id.name) {
                        CategoryCard(
                            item = item,
                            expanded = expandedCategory == item.id.name,
                            dailyMode = state.scheduleMode == CleanScheduleMode.FIXED_DAILY && item.id != CleanCategoryId.ORGANIZE,
                            onEnabledChanged = { actions.onCategoryEnabledChanged(item.id, it) },
                            onExpandedChanged = {
                                onExpandedCategoryChanged(
                                    if (expandedCategory == item.id.name) "" else item.id.name
                                )
                            },
                            onIntervalChanged = { actions.onCategoryIntervalChanged(item.id, it) }
                        )
                    }
                }
            }

            else -> {
                item { SectionTitle("专项工具", "手动扫描和低频工具集中在这里") }
                item { ToolGrid(actions) }
            }
        }
    }
}

@Composable
private fun Header() {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)
    ) {
        MiuixText("清理", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        MiuixText(
            "计划、类别与专项工具",
            color = colors.onSurfaceContainer.copy(alpha = .60f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun Tabs(tab: Int, onTabChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("计划", "类别", "工具").forEachIndexed { index, label ->
            MiuixButton(
                onClick = { onTabChanged(index) },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                minHeight = 42.dp,
                cornerRadius = 15.dp,
                colors = if (tab == index) {
                    MiuixButtonDefaults.buttonColorsPrimary()
                } else {
                    MiuixButtonDefaults.buttonColors()
                },
                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                MiuixText(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AutomaticCard(state: CleanUiState, actions: CleanUiActions) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(0.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        SwitchPreference(
            checked = state.automaticCleaningEnabled,
            onCheckedChange = actions.onAutomaticCleaningChanged,
            title = "自动清理",
            summary = if (state.automaticCleaningEnabled) {
                "${state.enabledCategoryCount} 个类别已启用"
            } else {
                "所有自动任务已暂停"
            },
            startAction = { PreferenceIcon(Icons.Rounded.CleaningServices, true) }
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) {
        MiuixText(title, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(1.dp))
        MiuixText(
            subtitle,
            color = colors.onSurfaceContainer.copy(alpha = .58f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun ScheduleCard(
    state: CleanUiState,
    actions: CleanUiActions,
    onEditTime: () -> Unit,
    onEditGrace: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 14.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            CleanScheduleMode.entries.forEach { mode ->
                MiuixButton(
                    onClick = { actions.onScheduleModeChanged(mode) },
                    minHeight = 36.dp,
                    cornerRadius = 13.dp,
                    colors = if (state.scheduleMode == mode) {
                        MiuixButtonDefaults.buttonColorsPrimary()
                    } else {
                        MiuixButtonDefaults.buttonColors()
                    },
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    MiuixText(mode.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MiuixText(
            state.scheduleMode.description,
            color = colors.onSurfaceContainer.copy(alpha = .58f),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }

    if (state.scheduleMode == CleanScheduleMode.FIXED_DAILY) {
        ValueCard(
            icon = Icons.Rounded.CalendarMonth,
            title = "执行时间",
            summary = "每天固定执行已启用类别",
            value = state.dailyTimeText,
            onClick = onEditTime
        )
        ValueCard(
            icon = Icons.Rounded.AutoAwesome,
            title = "补做窗口",
            summary = "条件不满足时继续等待",
            value = formatMinutes(state.dailyGraceMinutes),
            onClick = onEditGrace
        )
    }
}

@Composable
private fun CategoryCard(
    item: CleanCategoryUiItem,
    expanded: Boolean,
    dailyMode: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onExpandedChanged: () -> Unit,
    onIntervalChanged: (Int) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        ),
        onClick = {
            if (item.enabled && !dailyMode) onExpandedChanged()
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreferenceIcon(categoryIcon(item.id), item.enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    item.title,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Clip
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    item.description,
                    color = colors.onSurfaceContainer.copy(alpha = .56f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.enabled) {
                    Spacer(Modifier.height(3.dp))
                    MiuixText(
                        if (dailyMode) "跟随每日固定时间" else "每 ${formatMinutes(item.intervalMinutes)}执行",
                        color = colors.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MiuixSwitch(
                checked = item.enabled,
                onCheckedChange = onEnabledChanged
            )
        }

        if (expanded && item.enabled && !dailyMode) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                intervals.forEach { minutes ->
                    MiuixButton(
                        onClick = { onIntervalChanged(minutes) },
                        minHeight = 34.dp,
                        cornerRadius = 12.dp,
                        colors = if (item.intervalMinutes == minutes) {
                            MiuixButtonDefaults.buttonColorsPrimary()
                        } else {
                            MiuixButtonDefaults.buttonColors()
                        },
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        MiuixText(formatMinutes(minutes), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceCard(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        )
    ) {
        content()
    }
}

@Composable
private fun ValueCard(
    icon: ImageVector,
    title: String,
    summary: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        ),
        onClick = { if (enabled) onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreferenceIcon(icon, enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MiuixText(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    summary,
                    color = colors.onSurfaceContainer.copy(alpha = .56f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            MiuixText(
                value,
                color = if (enabled) colors.primary else colors.onSurfaceContainer.copy(alpha = .36f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            MiuixIcon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceContainer.copy(alpha = .55f)
            )
        }
    }
}

@Composable
private fun ToolGrid(actions: CleanUiActions) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCard(Icons.Rounded.Search, "扫描工作台", "按不可变快照清理", actions.onScan, Modifier.weight(1f), true)
            ToolCard(Icons.Rounded.InstallMobile, "安装包扫描", "重复与过期安装包", actions.onApkScan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCard(Icons.Rounded.CleaningServices, "即时缓存", "快速处理应用缓存", actions.onInstantCache, Modifier.weight(1f))
            ToolCard(Icons.Rounded.FolderCopy, "文件归类", "整理下载与文档", actions.onFileOrganizer, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCard(Icons.Rounded.AutoAwesome, "深度清理", "扩大范围并保留保护", actions.onDeepClean, Modifier.weight(1f))
            ToolCard(Icons.Rounded.FolderDelete, "卸载残留", "检查卸载应用遗留", actions.onCorpses, Modifier.weight(1f))
        }
        ToolCard(
            Icons.Rounded.Security,
            "规则与安全审计",
            "查看规则命中、保护项与潜在风险",
            actions.onAudit,
            Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = false
) {
    val colors = MiuixTheme.colorScheme
    MiuixCard(
        modifier = modifier.height(108.dp),
        cornerRadius = 19.dp,
        insideMargin = PaddingValues(13.dp),
        colors = MiuixCardDefaults.defaultColors(
            color = if (primary) colors.surfaceContainerHigh else colors.surfaceContainer,
            contentColor = colors.onSurfaceContainer
        ),
        onClick = onClick
    ) {
        PreferenceIcon(icon, true)
        Spacer(Modifier.height(7.dp))
        MiuixText(
            title,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(1.dp))
        MiuixText(
            summary,
            color = colors.onSurfaceContainer.copy(alpha = .54f),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PreferenceIcon(icon: ImageVector, enabled: Boolean) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (enabled) colors.primary.copy(alpha = .10f)
                else colors.onSurfaceContainer.copy(alpha = .05f)
            ),
        contentAlignment = Alignment.Center
    ) {
        MiuixIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) colors.primary else colors.onSurfaceContainer.copy(alpha = .36f)
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
