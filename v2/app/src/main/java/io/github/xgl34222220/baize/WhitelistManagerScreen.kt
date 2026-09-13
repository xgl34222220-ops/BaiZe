package io.github.xgl34222220.baize

import androidx.activity.compose.BackHandler
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
    onToggle: (String) -> Unit, onClearApps: () -> Unit, onSaveApps: () -> Unit,
    onRemovePath: (String) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var protectedOnly by rememberSaveable { mutableStateOf(false) }
    var showClear by rememberSaveable { mutableStateOf(false) }
    var showLeave by rememberSaveable { mutableStateOf(false) }
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    val edit = state.connected && state.packagesLoaded && !state.loading && !state.saving
    val pathEdit = state.connected && state.pathsLoaded && !state.loading && !state.saving
    val leave: () -> Unit = { if (state.draft.dirty) showLeave = true else onBack() }
    BackHandler(state.draft.dirty, onBack = { showLeave = true })
    val visible = remember(state.apps, state.draft.selected, query, protectedOnly) {
        state.apps.filter { (!protectedOnly || it.packageName in state.draft.selected) &&
            (it.label.contains(query, true) || it.packageName.contains(query, true)) }
    }
    val visiblePaths = remember(state.paths, query) { state.paths.filter { it.contains(query, true) } }
    val inset = Modifier.padding(horizontal = 20.dp)

    Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
        Column {
            DetailPageHeader("白名单", "", leave) {
                IconButton(onRefresh, enabled = !state.loading && !state.saving) { Icon(Icons.Rounded.Refresh, "刷新白名单") }
            }
            VideoTabs(listOf("应用保护", "路径保护"), tab, { tab = it; query = "" })
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, modifier = inset.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(18.dp), leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(if (tab == 0) "搜索应用名称或包名" else "搜索保护路径") })
            Text(state.message, inset.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == 0) {
                FlowRow(inset.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !protectedOnly, onClick = { protectedOnly = false }, label = { Text("全部应用") })
                    FilterChip(selected = protectedOnly, onClick = { protectedOnly = true }, label = { Text("已保护 ${state.draft.selected.size}") })
                    TextButton({ showClear = true }, enabled = edit && state.draft.selected.isNotEmpty()) { Text("取消全部应用保护") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visible.isEmpty() && !state.loading) item { Text("没有匹配的应用", style = MaterialTheme.typography.bodyMedium) }
                    items(visible, key = { it.packageName }) { app ->
                        LuoShuGroup {
                            Row(Modifier.fillMaxWidth().clickable(enabled = edit, role = Role.Checkbox) { onToggle(app.packageName) }
                                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ApplicationIcon(app.packageName, app.label, Modifier.size(42.dp))
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.titleSmall)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(app.packageName in state.draft.selected, { onToggle(app.packageName) }, enabled = edit,
                                    modifier = Modifier.semantics { contentDescription = "保护${app.label}" })
                            }
                        }
                    }
                }
                Column(inset.navigationBarsPadding().imePadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (state.draft.dirty) "有未保存修改：新增 ${state.draft.added.size}，取消 ${state.draft.removed.size}。"
                        else "取消勾选后点保存；不会删除应用或文件。", style = MaterialTheme.typography.bodySmall)
                    Button(onSaveApps, enabled = edit && state.draft.dirty,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                        Text(if (state.saving) "正在保存…" else "保存应用白名单")
                    }
                }
            } else {
                Text("这里只列手动添加的路径。移除仅取消此条保护，不删除文件，也不解除其它白名单或关键数据限制。",
                    inset.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (visiblePaths.isEmpty() && !state.loading) item { LuoShuGroup {
                        Text(if (state.pathsLoaded) "没有匹配的手动保护路径" else "路径名单尚未读取，原保护不变",
                            Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                    } }
                    items(visiblePaths, key = { it }) { path -> LuoShuGroup {
                        Column(Modifier.padding(16.dp)) {
                            SelectionContainer { Text(path, style = MaterialTheme.typography.bodyMedium) }
                            TextButton({ removal = path }, enabled = pathEdit) { Text("移除此路径保护") }
                        }
                    } }
                }
            }
        }
    }
    if (showClear) AlertDialog(onDismissRequest = { showClear = false }, title = { Text("取消全部应用保护？") },
        text = { Text("将取消当前选择的 ${state.draft.selected.size} 个应用保护，需要再点保存才生效。手动路径保护不变，不会删除任何文件。") },
        confirmButton = { TextButton({ showClear = false; onClearApps() }, enabled = edit) { Text("取消这些保护") } },
        dismissButton = { TextButton({ showClear = false }) { Text("返回") } })
    removal?.let { path -> AlertDialog(onDismissRequest = { removal = null }, title = { Text("移除路径白名单？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(path) }
            Text("仅移除这条记录。其父目录、子目录及应用保护不会一并取消；文件不变。完成后请重新扫描。")
        } },
        confirmButton = { TextButton({ removal = null; onRemovePath(path) }, enabled = pathEdit && path in state.paths) { Text("确认移除") } },
        dismissButton = { TextButton({ removal = null }) { Text("保留") } }) }
    if (showLeave) AlertDialog(onDismissRequest = { showLeave = false }, title = { Text("应用白名单尚未保存") },
        text = { Text("离开不会把未保存的修改写入清理引擎。") },
        confirmButton = { TextButton({ showLeave = false; onBack() }) { Text("不保存并离开") } },
        dismissButton = { TextButton({ showLeave = false }) { Text("继续编辑") } })
}
