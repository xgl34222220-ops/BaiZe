package io.github.xgl34222220.baize

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.components.BaiZeProgress
import io.github.xgl34222220.baize.ui.components.DetailPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
internal fun LegacyProtectionRecoveryScreen(
    state: LegacyProtectionRecoveryUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTogglePackage: (String) -> Unit,
    onTogglePath: (String) -> Unit,
    onSave: () -> Unit
) {
    val snapshot = state.snapshot
    var confirming by rememberSaveable(snapshot?.fingerprint) { mutableStateOf(false) }
    // A save belongs to the retained ViewModel. Don't allow navigation to cancel it halfway through.
    BackHandler(enabled = state.saving) { }
    LaunchedEffect(state.canReview) { if (!state.canReview) confirming = false }
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = {
            DetailPageHeader("检查旧版保护", "本地核对，无需连接 Root", { if (!state.saving) onBack() }) {
                IconButton(onRefresh, enabled = !state.busy, modifier = Modifier.testTag("legacy-recovery-refresh")) {
                    Icon(Icons.Rounded.Refresh, "刷新旧版保护")
                }
            }
        },
        bottomBar = {
            if (snapshot?.pending == true) Surface(color = BaiZeTokens.colors.surfaceRaised) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("保留 ${state.selectedPackages.size} 个旧版应用、${state.selectedPaths.size} 个旧版路径",
                        style = MaterialTheme.typography.bodySmall)
                    Button({ confirming = true }, enabled = state.canReview,
                        modifier = Modifier.fillMaxWidth().testTag("legacy-recovery-save")) {
                        Text(if (state.saving) "正在保存并核对…" else "核对并保存")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("legacy-recovery-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LuoShuGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when {
                            state.error.isNotBlank() -> "旧版保护暂不可确认"
                            state.confirmed -> "旧版保护已确认"
                            snapshot?.pending == true -> "旧版保护待确认"
                            snapshot == null && !state.loading -> "旧版保护暂不可读取"
                            snapshot == null -> "正在检查旧版保护"
                            else -> "没有待确认的旧版保护"
                        }, modifier = Modifier.testTag(if (state.confirmed) "legacy-recovery-success" else "legacy-recovery-status"),
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (snapshot?.pending == true)
                            "旧版外观迁移留下了历史保护记录，但它们可能已过期。请逐项核对；默认勾选仅供选择，确认保存前不会写入。待确认期间，相关清理会保留文件。"
                        else if (state.confirmed)
                            "你的选择已保存并重新核对。返回后请重新扫描；Root 保护仍需连接服务核对，不能仅凭本次确认判断清理安全。"
                        else "这里仅核对本机的旧版应用和路径保护记录。Root 服务中的保护仍由原保护名单管理。",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("不会删除文件，也不会修改 Root 保护。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.busy) item { BaiZeProgress(Modifier.fillMaxWidth().testTag("legacy-recovery-progress")) }
            if (state.error.isNotBlank()) item {
                Text(state.error, modifier = Modifier.testTag("legacy-recovery-error"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (snapshot != null) {
                item {
                    LuoShuGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("现有本地保护保持不变", style = MaterialTheme.typography.titleSmall)
                            Text("已存在的当前记录（包括明确保存的空名单）优先，本页不会用历史记录覆盖它们。",
                                style = MaterialTheme.typography.bodySmall)
                            if (snapshot.currentPackagesPresent || snapshot.currentPackages.isNotEmpty())
                                RuleList("当前应用", snapshot.currentPackages)
                            if (snapshot.currentPathsPresent || snapshot.currentPaths.isNotEmpty())
                                RuleList("当前路径", snapshot.currentPaths)
                            if (!snapshot.currentPackagesPresent && !snapshot.currentPathsPresent &&
                                snapshot.currentPackages.isEmpty() && snapshot.currentPaths.isEmpty())
                                Text("本机尚无当前本地名单记录；如有历史候选，请在下方核对。", style = MaterialTheme.typography.bodySmall)
                            if (state.needsRefresh) Text("以上为上次读取结果，请刷新核对。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (snapshot.packageReviewRequired) {
                    item { RecoverySection("历史应用候选", snapshot.pendingPackages.size, snapshot.currentPackagesPresent) }
                    items(snapshot.pendingPackages.sorted(), key = { "package:$it" }) { candidate ->
                        RecoveryChoice(candidate, candidate in state.selectedPackages, state.canReview,
                            "legacy-pending-package:$candidate") { onTogglePackage(candidate) }
                    }
                }
                if (snapshot.pathReviewRequired) {
                    item { RecoverySection("历史路径候选", snapshot.pendingPaths.size, snapshot.currentPathsPresent) }
                    items(snapshot.pendingPaths.sorted(), key = { "path:$it" }) { candidate ->
                        RecoveryChoice(candidate, candidate in state.selectedPaths, state.canReview,
                            "legacy-pending-path:$candidate") { onTogglePath(candidate) }
                    }
                }
                if (snapshot.pending) item {
                    Text("取消勾选表示不恢复该条历史保护；若没有其它规则保护它，该范围在后续清理中可能可选。现有本地记录与 Root 规则仍独立生效。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirming && state.canReview && snapshot != null) {
        BaiZeDialog(onDismissRequest = { confirming = false },
            title = { Text("确认旧版保护选择？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("仅保存本次待确认的历史候选选择。请核对下面将保留和不恢复的完整记录。")
                    if (snapshot.currentPackagesPresent) RuleList("保持不变的当前应用", snapshot.currentPackages)
                    if (snapshot.currentPathsPresent) RuleList("保持不变的当前路径", snapshot.currentPaths)
                    if (snapshot.packageReviewRequired) {
                        RuleList("保留的旧版应用", state.selectedPackages)
                        RuleList("不恢复的旧版应用", snapshot.pendingPackages - state.selectedPackages)
                    }
                    if (snapshot.pathReviewRequired) {
                        RuleList("保留的旧版路径", state.selectedPaths)
                        RuleList("不恢复的旧版路径", snapshot.pendingPaths - state.selectedPaths)
                    }
                    Text("现有本地保护（包括已保存的空名单）及 Root 规则不变。未保留的历史候选可能不再提供保护；不会删除文件。")
                }
            },
            confirmButton = {
                BaiZeDialogButton({ confirming = false; if (state.canReview) onSave() },
                    modifier = Modifier.testTag("legacy-recovery-confirm"), enabled = state.canReview) { Text("确认保存") }
            },
            dismissButton = { BaiZeDialogButton({ confirming = false }) { Text("返回核对") } })
    }
}

@Composable
private fun RecoverySection(title: String, count: Int, currentPresent: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$title · $count 条", style = MaterialTheme.typography.titleSmall)
        if (count == 0) Text(if (currentPresent)
            "此项已有当前记录，本次仅完成未结束的确认，当前记录保持不变。"
        else "历史记录为空，仍需明确确认保留空名单。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecoveryChoice(value: String, selected: Boolean, enabled: Boolean, tag: String, toggle: () -> Unit) {
    LuoShuGroup {
        Row(Modifier.fillMaxWidth().testTag(tag).toggleable(selected, enabled, Role.Checkbox) { toggle() }
            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, onCheckedChange = null, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            // No ellipsis or line limit: exact paths/package names remain reviewable at large font sizes.
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RuleList(title: String, values: Set<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title · ${values.size} 条", style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(if (values.isEmpty()) "无" else values.sorted().joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
