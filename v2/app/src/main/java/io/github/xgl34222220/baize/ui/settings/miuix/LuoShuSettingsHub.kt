package io.github.xgl34222220.baize.ui.settings.miuix

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.BuildConfig
import io.github.xgl34222220.baize.UninstallWatcherSettings
import io.github.xgl34222220.baize.WechatUsage
import io.github.xgl34222220.baize.formatUsageBytes
import io.github.xgl34222220.baize.wechatUsageTierHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.components.DetailStatusText
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.settings.SettingsUiActions
import io.github.xgl34222220.baize.ui.settings.SettingsUiState
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** LuoShu SettingsHubScreen structure: overview -> navigation groups -> separate detail page. */
@Composable
fun LuoShuSettingsHub(state: SettingsUiState, actions: SettingsUiActions,
    onDetailChanged: (Boolean) -> Unit = {},
    runtimeLogs: (@Composable (onBack: () -> Unit) -> Unit)? = null) {
    var section by rememberSaveable { mutableStateOf("") }
    val sectionState = rememberSaveableStateHolder()
    val notify by rememberUpdatedState(onDetailChanged)
    LaunchedEffect(section) { notify(section.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { notify(false) } }
    fun closeDetail() {
        section = ""
    }
    BackHandler(enabled = section.isNotEmpty()) { closeDetail() }
    AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            io.github.xgl34222220.baize.ui.theme.BaiZeMotionSpecs.detailTransition(forward = targetState.isNotEmpty())
        },
        label = "settingsHub"
    ) { target ->
        sectionState.SaveableStateProvider(target) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = BaiZeTokens.colors.surfaceBase
            ) {
                when (target) {
                    "service" -> ServiceDetails(state, actions, ::closeDetail)
                    "logs" -> runtimeLogs?.invoke(::closeDetail)
                    else -> SettingsHome(state, actions, { section = it }, runtimeLogs != null)
                }
            }
        }
    }
}

@Composable
private fun pagePadding(detail: Boolean = false): PaddingValues = PaddingValues(start = 16.dp, end = 16.dp,
    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (detail) 32.dp else 132.dp)

@Composable
private fun SettingsHome(state: SettingsUiState, actions: SettingsUiActions, open: (String) -> Unit,
    runtimeLogsAvailable: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val colors = BaiZeTokens.colors
    val statusColor = if (state.connectionFailed) scheme.error else if (state.ready) colors.success else colors.warning
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { LuoShuPageHeader("设置") }
        item {
            Surface(onClick = { open("service") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
                color = colors.surfaceRaised, shadowElevation = 0.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(52.dp), shape = RoundedCornerShape(18.dp), color = scheme.primary.copy(alpha = .10f)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("泽", color = scheme.primary, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("白泽状态", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.connectionLabel, fontSize = 12.sp, lineHeight = 18.sp, color = statusColor)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = scheme.primary.copy(alpha = .045f)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("自动清理", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            Text(if (state.scheduler.enabled) "自动计划已开启" else "自动计划已暂停",
                                fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                            if ((!state.ready || state.connectionFailed) && state.serviceText.isNotBlank()) {
                                DetailStatusText(state.serviceText)
                            }
                        }
                    }
                }
            }
        }
        item { LuoShuSection("你的白泽") }
        item {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.Palette, "外观与主题", "颜色与显示效果", actions.onOpenAppearance)
            }
        }
        item { LuoShuSection("管理与维护") }
        item {
            LuoShuGroup {
                // 唯一的规则与保护中心：保护名单、清理策略、规则版本与试跑、自定义路径规则、检查旧版保护。
                LuoShuNavigationRow(Icons.Rounded.Shield, "规则与保护", "保护名单、清理策略与规则版本", actions.onOpenRulesCenter)
                if (runtimeLogsAvailable) {
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Description, "运行日志", "任务日志与原始输出", { open("logs") })
                }
                LuoShuGroupDivider()
                UninstallWatcherSwitchRow()
            }
        }
        // 性能工具（实验）：独立页面，全部默认关闭。
        item { LuoShuSection("性能工具（实验）", "Dex2oat、数据库、进程与内存压制，默认全部关闭") }
        item { LuoShuGroup { io.github.xgl34222220.baize.PerfToolsEntryRow() } }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("白泽 · ${BuildConfig.VERSION_NAME}", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                Text("清理有据，保留有度", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

/** 卸载残留提醒开关：本地偏好 + 接收器组件启用状态，无需连接清理服务。 */
@Composable
private fun UninstallWatcherSwitchRow() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    // 偏好读取与组件状态写入都离开主线程。
    LaunchedEffect(Unit) { enabled = withContext(Dispatchers.IO) { UninstallWatcherSettings.isEnabled(context) } }
    LuoShuSwitchRow(Icons.Rounded.NotificationsActive, "卸载残留提醒", "卸载应用后低优先级提示扫描残留", enabled == true, { value ->
        enabled = value
        scope.launch(Dispatchers.IO) { UninstallWatcherSettings.setEnabled(context, value) }
    }, enabled = enabled != null)
}

@Composable
private fun ServiceDetails(state: SettingsUiState, actions: SettingsUiActions, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(detail = true), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { LuoShuPageHeader("连接与诊断", back) }
        item {
            LuoShuGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("清理服务 · ${state.connectionLabel}", style = MaterialTheme.typography.titleMedium)
                    DetailStatusText(listOf(state.serviceText, state.schedulerText).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                }
            }
        }
        if (state.versionDetails.isNotBlank()) item {
            LuoShuGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("版本信息", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        DetailStatusText(state.versionDetails)
                    }
                }
            }
        }
        item {
            LuoShuGroup {
                LuoShuNavigationRow(Icons.Rounded.PlayArrow, "旧版任务恢复", "恢复升级前保存的清理计划", actions.onOpenResumableScan)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Refresh, if (state.connecting) "正在连接" else "重新连接服务",
                    if (state.connecting) "等待当前连接完成" else "重新连接", { if (!state.connecting) actions.onReconnect() })
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.BugReport, "崩溃与诊断信息", "异常与故障记录", actions.onOpenCrashDiagnostics)
                LuoShuGroupDivider()
                LuoShuNavigationRow(Icons.Rounded.Speed, "重置扫描性能基准", "扫描明显变慢或换机后使用，下次扫描重新测量", actions.onResetScanPerformance)
            }
        }
    }
}

internal fun appProfileTierLabel(tier: Int): String = when (tier) {
    0 -> "保守"
    2 -> "增强"
    else -> "标准"
}

internal fun appProfileTierDescription(tier: Int): String = when (tier) {
    0 -> "只清理日志、崩溃记录与纯缓存（含微信朋友圈、头像缓存）"
    2 -> "再加朋友圈、小程序等较大的可重下载缓存"
    else -> "再加可自动重建的资源与图片缓存（推荐）"
}
