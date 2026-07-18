package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.clean.CleanCategoryId
import io.github.xgl34222220.baize.ui.clean.CleanCategoryUiItem
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState

private data class MiuixQuickAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
fun CleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val quickActions = listOf(
        MiuixQuickAction(Icons.Rounded.Search, "垃圾扫描", "生成快照，不立即删除", actions.onScan),
        MiuixQuickAction(Icons.Rounded.InstallMobile, "安装包扫描", "查找 APK / APKS / XAPK", actions.onApkScan),
        MiuixQuickAction(Icons.Rounded.DeleteSweep, "深度清理", "日志、临时文件和常见残留", actions.onDeepClean),
        MiuixQuickAction(Icons.Rounded.FolderDelete, "卸载残留", "无主 data、obb 与 media 目录", actions.onCorpses),
        MiuixQuickAction(Icons.Rounded.Rule, "清理明细", "规则范围和最近命中", actions.onAudit)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 146.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixCleanHeader() }
        item {
            MiuixCleanOverview(
                state = state,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item {
            MiuixSectionHeader(
                eyebrow = "AUTOMATIC CLEANING",
                title = "自动清理类别",
                subtitle = "紧凑分组，点击周期可快速切换"
            )
        }
        item {
            MiuixGroupCard(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                MiuixMasterSwitchRow(
                    title = "自动清理",
                    subtitle = if (state.automaticCleaningEnabled) "按各类别周期自动执行" else "已关闭，手动工具仍可使用",
                    checked = state.automaticCleaningEnabled,
                    onCheckedChange = actions.onAutomaticCleaningChanged
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                state.categories.forEachIndexed { index, item ->
                    MiuixCategoryRow(item, actions)
                    if (index != state.categories.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 59.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                MiuixMasterSwitchRow(
                    title = "过期安装包",
                    subtitle = "保留 ${state.apkPackageDays} 天后自动清理",
                    checked = state.apkPackagesEnabled,
                    onCheckedChange = actions.onApkPackagesChanged,
                    icon = Icons.Rounded.InstallMobile
                )
            }
        }
        item {
            MiuixSaveButton(
                saving = state.saving,
                onClick = actions.onSave,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        item {
            MiuixSectionHeader(
                eyebrow = "MANUAL TOOLS",
                title = "手动清理工具",
                subtitle = "扫描、深度清理和规则明细"
            )
        }
        item {
            MiuixGroupCard(
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                quickActions.forEachIndexed { index, action ->
                    MiuixQuickActionRow(action)
                    if (index != quickActions.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 59.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixCleanHeader() {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            "CLEANING CATEGORIES",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "清理中心",
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Black
        )
        Text(
            "选择类别、周期和手动清理工具",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MiuixCleanOverview(
    state: CleanUiState,
    modifier: Modifier = Modifier
) {
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val shape = RoundedCornerShape(34.dp)
    val status = when {
        state.running -> "清理任务执行中"
        state.scanSnapshotReady -> "扫描快照已就绪"
        state.engineReady -> "清理引擎已就绪"
        else -> "清理引擎未就绪"
    }

    Box(
        modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(
                when {
                    amoled -> Color(0xFF090909)
                    dark -> scheme.surfaceContainerHigh
                    else -> scheme.surface
                }
            )
            .border(1.dp, scheme.onSurface.copy(alpha = .06f), shape)
            .drawBehind {
                if (!amoled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(scheme.primary.copy(alpha = if (dark) .20f else .15f), Color.Transparent),
                            center = Offset(size.width, 0f),
                            radius = size.width * .72f
                        ),
                        radius = size.width * .72f,
                        center = Offset(size.width, 0f)
                    )
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark) .05f else .30f), Color.Transparent)
                    ),
                    cornerRadius = CornerRadius(34.dp.toPx()),
                    size = size.copy(height = size.height * .42f)
                )
            }
            .padding(22.dp)
    ) {
        Column {
            Text(
                "已启用",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    state.enabledCategoryCount.toString(),
                    fontSize = 46.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    " / ${state.categories.size} 类",
                    modifier = Modifier.padding(bottom = 5.dp),
                    color = scheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(21.dp))
                    .background(scheme.onSurface.copy(alpha = if (dark) .055f else .04f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.engineReady || state.scanSnapshotReady) Color(0xFF2DBE87)
                            else Color(0xFFF2A93B)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        state.serviceText,
                        color = scheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val amoled = dark && settings.amoledBlack
    val shape = RoundedCornerShape(30.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(
                when {
                    amoled -> Color(0xFF090909)
                    dark -> scheme.surfaceContainer
                    else -> scheme.surface
                }
            )
            .border(1.dp, scheme.onSurface.copy(alpha = .05f), shape)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        content = content
    )
}

@Composable
private fun MiuixMasterSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector = Icons.Rounded.CleaningServices
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixIconTile(icon)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MiuixSuperSwitch(checked, onCheckedChange)
    }
}

@Composable
private fun MiuixCategoryRow(
    item: CleanCategoryUiItem,
    actions: CleanUiActions
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIconTile(categoryIcon(item.id))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    item.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            MiuixSuperSwitch(
                checked = item.enabled,
                onCheckedChange = { actions.onCategoryEnabledChanged(item.id, it) }
            )
        }
        if (item.enabled) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.padding(start = 58.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf(1, 6, 12, 24).forEach { hours ->
                    val active = item.intervalHours == hours
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary.copy(alpha = .16f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)
                            )
                            .clickable { actions.onCategoryIntervalChanged(item.id, hours) }
                            .padding(horizontal = 11.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "${hours}h",
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixQuickActionRow(action: MiuixQuickAction) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = action.onClick)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixIconTile(action.icon)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(action.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                action.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "进入",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MiuixIconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(45.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .11f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(23.dp)
        )
    }
}

@Composable
private fun MiuixSuperSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val background by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = .16f),
        label = "miuixCleanSwitchColor"
    )
    val thumbOffset by animateDpAsState(
        if (checked) 24.dp else 3.dp,
        label = "miuixCleanSwitchThumb"
    )
    Box(
        Modifier
            .width(50.dp)
            .height(29.dp)
            .clip(CircleShape)
            .background(background)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset, y = 3.dp)
                .size(23.dp)
                .clip(CircleShape)
                .background(Color.White)
                .shadow(2.dp, CircleShape)
        )
    }
}

@Composable
private fun MiuixSaveButton(
    saving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                )
            )
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .28f), Color.Transparent)
                    ),
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    size = size.copy(height = size.height * .54f)
                )
            }
            .clickable(enabled = !saving, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (saving) "正在保存…" else "保存自动清理设置",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun MiuixSectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(horizontal = 21.dp, vertical = 5.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
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
