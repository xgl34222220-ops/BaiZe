package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.BaiZeProgress
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.components.DetailPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.miuix.VideoTabs
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhitelistManagerScreen(
    state: WhitelistUiState, onBack: () -> Unit, onRefresh: () -> Unit,
    onToggle: (String) -> Unit, onClearApps: () -> Unit,
    onRemovePath: (String) -> Unit, onAddPath: (String) -> Unit = {}
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusOnly by rememberSaveable(state.focusFile) { mutableStateOf(state.focusFile != null) }
    var focusTabChosen by rememberSaveable(state.focusFile) { mutableStateOf(false) }
    LaunchedEffect(state.focusFile, state.focusDetails) {
        val details = state.focusDetails
        if (state.focusFile != null && !focusTabChosen && details != null && details.unavailableReason == null) {
            tab = if (details.paths.isEmpty() && details.packages.isNotEmpty()) 0 else 1
            focusTabChosen = true
        }
    }
    val matchingPaths = state.paths.filter { path -> state.focusDetails?.paths.orEmpty().any {
        it.identity == path || it.rule in state.pathAliases[path].orEmpty()
    } }.toSet()
    val matchingPackages = state.focusDetails?.packages.orEmpty()
    var protectedOnly by rememberSaveable { mutableStateOf(false) }
    var showClear by rememberSaveable { mutableStateOf(false) }
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pathInput by rememberSaveable { mutableStateOf("") }
    var submittedPath by rememberSaveable { mutableStateOf("") }
    var addRevision by rememberSaveable { mutableIntStateOf(0) }
    var broadAcknowledged by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.pathSaveRevision) {
        if (adding && state.pathSaveRevision > addRevision) {
            adding = false
            pathInput = ""
            submittedPath = ""
        }
    }
    val edit = state.connected && state.packagesLoaded && !state.loading && !state.saving
    val pathEdit = state.connected && state.pathsLoaded && !state.loading && !state.saving
    val leave: () -> Unit = { if (!state.saving) onBack() }
    val visible = remember(state.apps, state.draft.selected, query, protectedOnly, focusOnly, matchingPackages) {
        state.apps.filter { (!focusOnly || it.packageName in matchingPackages) && (!protectedOnly || it.packageName in state.draft.selected) &&
            (it.label.contains(query, true) || it.packageName.contains(query, true)) }
    }
    val visiblePaths = remember(state.paths, state.pathAliases, query, focusOnly, matchingPaths) { state.paths.filter { path ->
        (!focusOnly || path in matchingPaths) &&
            (path.contains(query, true) || state.pathAliases[path].orEmpty().any { it.contains(query, true) })
    } }
    Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
        Column {
            DetailPageHeader("保护名单", "", leave) {
                IconButton(onRefresh, enabled = !state.loading && !state.saving) { Icon(Icons.Rounded.Refresh, "刷新白名单") }
            }
            VideoTabs(listOf("应用保护", "路径保护"), tab, { tab = it; query = "" })
            LazyColumn(Modifier.weight(1f).navigationBarsPadding().imePadding().testTag("whitelist-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.focusFile?.let { file -> item {
                    LuoShuGroup { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("正在管理此文件的保护", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(file, style = MaterialTheme.typography.bodySmall) }
                        val details = state.focusDetails
                        Text(when {
                            state.loading || details == null -> "正在核对此文件匹配的路径和应用保护…"
                            !state.connected || !state.pathsLoaded || !state.packagesLoaded -> "尚未核对当前保护，下面仅供参考；重连并刷新后才能修改。"
                            else -> details.summary
                        }, style = MaterialTheme.typography.bodySmall)
                        if (details?.manageable == true) Text("匹配 ${matchingPaths.size} 个路径、${matchingPackages.size} 个应用。各条规则独立生效；取消一条后仍可能受其它规则保护。",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton({ focusOnly = !focusOnly; query = "" }) {
                            Text(if (focusOnly) "显示全部保护" else "仅看此文件匹配保护")
                        }
                    } }
                } }
                item {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(18.dp), leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text(if (tab == 0) "搜索应用名称或包名" else "搜索保护路径") })
                }
                item { Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.loading || state.saving) item { BaiZeProgress(Modifier.fillMaxWidth()) }
                if (tab == 0) {
                    item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !protectedOnly, onClick = { protectedOnly = false }, label = { Text("全部应用") })
                        FilterChip(selected = protectedOnly, onClick = { protectedOnly = true }, label = { Text("已保护 ${state.draft.selected.size}") })
                        if (state.focusFile == null) TextButton({ showClear = true }, enabled = edit && state.draft.selected.isNotEmpty()) { Text("取消全部应用保护") }
                    } }
                    if (visible.isEmpty() && !state.loading) item { Text("没有匹配的应用", style = MaterialTheme.typography.bodyMedium) }
                    items(visible, key = { "app:${it.packageName}" }) { app ->
                        LuoShuGroup {
                            Row(Modifier.fillMaxWidth().clickable(enabled = edit, role = Role.Checkbox) { onToggle(app.packageName) }
                                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                ApplicationIcon(app.packageName, app.label, Modifier.size(40.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.titleSmall)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (app.packageName in matchingPackages) Text("此文件位于该应用的 Android/data 目录，受此项保护",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    if (app.packageName in state.legacyPackages) Text("包含旧版保护记录",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(app.packageName in state.draft.selected, { onToggle(app.packageName) }, enabled = edit,
                                    modifier = Modifier.semantics { contentDescription = "保护${app.label}" })
                            }
                        }
                    }
                    item { Text(if (state.saving) "正在自动保存修改…" else "勾选或取消后立即保存；不会删除应用或文件。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("文件或文件夹都可保护，文件夹包含全部子项。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ adding = true; addRevision = state.pathSaveRevision; broadAcknowledged = false }, enabled = pathEdit) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("添加路径")
                        }
                    } }
                    if (visiblePaths.isEmpty() && !state.loading) item { LuoShuGroup {
                        Text(if (state.pathsLoaded) "没有匹配的保护路径" else "路径名单尚未读取，原保护不变",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    } }
                    items(visiblePaths, key = { "path:$it" }) { path -> LuoShuGroup {
                        Column(Modifier.padding(16.dp).testTag("whitelist-path:$path")) {
                            SelectionContainer { Text(path, style = MaterialTheme.typography.bodyMedium) }
                            if (path in matchingPaths) Text(
                                if (state.focusDetails?.paths.orEmpty().any { it.identity == path && it.ancestor }) "此文件受该父目录保护，包含目录内全部子项"
                                else "此文件匹配此路径保护",
                                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text(when {
                                path in state.legacyPaths && path in state.rootPaths -> "清理服务与旧版设置中的同一路径"
                                path in state.legacyPaths -> "来自旧版设置"
                                else -> "已保存到清理服务"
                            }, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ removal = path }, enabled = pathEdit) { Text("移除此路径保护") }
                        }
                    } }
                }
            }
        }
    }
    if (showClear) BaiZeDialog(onDismissRequest = { showClear = false }, title = { Text("取消全部应用保护？") },
        text = { Text("将取消当前选择的 ${state.draft.selected.size} 个应用保护，并立即保存。手动路径保护不变，不会删除任何文件。") },
        confirmButton = { BaiZeDialogButton({ showClear = false; onClearApps() }, enabled = edit) { Text("取消这些保护") } },
        dismissButton = { BaiZeDialogButton({ showClear = false }) { Text("返回") } })
    removal?.let { path -> BaiZeDialog(onDismissRequest = { removal = null }, title = { Text("移除路径白名单？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(path) }
            val records = state.pathAliases[path].orEmpty()
            if (records.size > 1 || records.singleOrNull()?.let { it != path } == true) {
                Text("将移除同一位置的以下路径记录：")
                SelectionContainer { Text(records.joinToString("\n")) }
            }
            Text("仅移除此路径的保护（包含旧版设置及同义路径）。其父目录、子目录及应用保护不会一并取消；文件不变。完成后请重新扫描。")
        } },
        confirmButton = { BaiZeDialogButton({ removal = null; onRemovePath(path) }, enabled = pathEdit && path in state.paths) { Text("确认移除") } },
        dismissButton = { BaiZeDialogButton({ removal = null }) { Text("保留") } }) }

    if (adding) {
        val parsed = remember(pathInput) { WhitelistPathInput.parse(pathInput) }
        BaiZeDialog(onDismissRequest = { if (!state.addingPath) adding = false }, title = { Text("添加保护路径") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入完整的文件或文件夹路径。文件夹内的全部子文件和子文件夹都会受到保护，不会被清理。")
                OutlinedTextField(pathInput, { pathInput = it; broadAcknowledged = false },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.addingPath, minLines = 2, maxLines = 4,
                    label = { Text("完整路径") }, placeholder = { Text("/storage/emulated/0/Download/需要保留的文件夹") },
                    isError = pathInput.isNotEmpty() && parsed.path == null,
                    supportingText = { if (pathInput.isNotEmpty() && parsed.path == null) Text(parsed.error) })
                parsed.path?.let { path ->
                    SelectionContainer { Text("保护范围\n$path", style = MaterialTheme.typography.bodyMedium) }
                    if (parsed.broad) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.addingPath) { broadAcknowledged = !broadAcknowledged },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(broadAcknowledged, { broadAcknowledged = it }, enabled = !state.addingPath)
                            Text("我确认保护整个存储目录及其全部内容", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.addingPath) Text("正在保存并核对…", style = MaterialTheme.typography.bodySmall)
                else if (submittedPath == pathInput && state.pathSaveError.isNotBlank()) Text(state.pathSaveError,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("相同位置的路径会合并显示；不会搬移文件，也不会取消已有保护。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { BaiZeDialogButton({
                submittedPath = pathInput; addRevision = state.pathSaveRevision
                parsed.path?.let(onAddPath)
            }, enabled = pathEdit && parsed.path != null && (!parsed.broad || broadAcknowledged)) { Text("添加保护") } },
            dismissButton = { BaiZeDialogButton({ adding = false }, enabled = !state.addingPath) { Text("返回") } })
    }
}

