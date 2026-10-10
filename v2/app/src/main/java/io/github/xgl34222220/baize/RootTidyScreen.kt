package io.github.xgl34222220.baize

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class RootTidyViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(RootTidyUiState())
    val state = mutable.asStateFlow()
    private val context get() = getApplication<Application>()
    private val prefs get() = context.getSharedPreferences("root-tidy", 0)
    @Volatile private var remote: IProfileRootService? = null
    private var bound = false
    private var closed = false
    private val cancelled = AtomicBoolean(false)
    private var initialized = false
    private val connection = object : RootService.Connection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed || binder == null) return
            remote = RootServiceClients.profile(binder, context.cacheDir); bound = true
            mutable.update { it.copy(rootConnected = true) }
            syncFromModule()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null; bound = false; mutable.update { it.copy(rootConnected = false) } }
        override fun onBindingFailed(name: ComponentName?, reason: RootService.BindingFailure) { remote = null; bound = false }
        override fun onNullBinding(name: ComponentName?) = onBindingFailed(name, RootService.BindingFailure.NULL_BINDING)
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        mutable.update { it.copy(rules = RootDirectoryOrganizer.decodeRules(prefs.getString("rules", null)),
            history = prefs.getString("history", null)?.let { raw -> runCatching { JSONArray(raw).let { a -> (0 until a.length()).map(a::getString) } }.getOrNull() }.orEmpty()) }
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }
        scan()
    }
    fun resumePermission() { if (state.value.permissionRequired && SharedStorageAccess.granted(context)) scan() }

    /** 模块侧是规则的权威副本（自动整理读取它）；只在连接成功后合并一次。 */
    private fun syncFromModule() {
        val source = remote ?: return
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { runCatching { JSONObject(RootServiceClients.profileExchange(source, context.cacheDir, "readRootTidy")) }.getOrNull() }
                ?: return@launch
            val module = RootDirectoryOrganizer.decodeRules(json.optString("rules"))
            val local = state.value.rules
            val merged = local.copy(allow = local.allow + module.allow, block = local.block + module.block)
            val log = json.optJSONArray("log")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            mutable.update { it.copy(rules = merged, moduleSummary = if (log.isEmpty()) "" else "自动整理最近记录：\n" + log.takeLast(5).joinToString("\n")) }
            if (merged != module) persistRules(merged)
            reclassify()
        }
    }

    private fun root(): File = @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()
    private fun installed(): Set<String> = runCatching {
        @Suppress("DEPRECATION") context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    private var lastEntries: List<RootEntry> = emptyList()
    private var lastInstalled: Set<String> = emptySet()
    private fun reclassify() {
        val rules = state.value.rules
        val reviews = lastEntries.map { RootDirectoryOrganizer.classify(it, lastInstalled, rules) }
            .sortedWith(compareBy<RootEntryReview> { ROOT_KIND_ORDER.indexOf(it.kind) }.thenByDescending { it.entry.bytes }.thenBy { it.entry.name.lowercase() })
        mutable.update { current -> current.copy(reviews = reviews, selected = current.selected.filter { name -> reviews.any { it.entry.name == name && it.removable } }.toSet()) }
    }

    fun scan(keepStatus: Boolean = false) {
        if (state.value.running) return
        if (!SharedStorageAccess.granted(context)) { mutable.update { it.copy(permissionRequired = true, status = "开启文件访问后，查看根目录") }; return }
        cancelled.set(false)
        val previous = state.value.status
        mutable.update { it.copy(running = true, permissionRequired = false, status = "正在读取根目录…", selected = emptySet()) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RootTidyFiles.scan(root(), cancelled::get) to installed() } }
            result.onSuccess { (entries, packages) ->
                lastEntries = entries; lastInstalled = packages
                reclassify()
                val reviews = state.value.reviews
                mutable.update { it.copy(running = false, status = (if (keepStatus) "$previous · " else "") +
                    "根目录 ${entries.size} 项 · 空文件夹 ${reviews.count { r -> r.kind == RootEntryKind.EMPTY }} · 已卸载残留 ${reviews.count { r -> r.kind == RootEntryKind.ORPHAN }}") }
            }.onFailure { error -> mutable.update { it.copy(running = false, status = if (error is java.util.concurrent.CancellationException) "已停止" else "读取失败：${error.message ?: error.javaClass.simpleName}") } }
        }
    }
    fun stop() = cancelled.set(true)
    fun toggle(name: String) = mutable.update { it.toggle(name) }
    fun toggleAll() = mutable.update { it.toggleAll() }

    private fun persistRules(rules: RootTidyRules) {
        prefs.edit().putString("rules", RootDirectoryOrganizer.encodeRules(rules)).apply()
        val source = remote ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { RootServiceClients.profileExchange(source, context.cacheDir, "writeRootTidyRules", JSONArray().put(RootDirectoryOrganizer.encodeRules(rules))) }
        }
    }
    private fun record(line: String) {
        val stamp = android.text.format.DateFormat.format("MM-dd HH:mm", System.currentTimeMillis())
        val history = (state.value.history + "$stamp $line").takeLast(50)
        prefs.edit().putString("history", JSONArray(history).toString()).apply()
        mutable.update { it.copy(history = history) }
    }

    fun setAllowed(name: String, allowed: Boolean) {
        if (state.value.running || !RootDirectoryOrganizer.ruleName(name)) return
        val rules = state.value.rules.let { if (allowed) it.copy(allow = it.allow + name) else it.copy(allow = it.allow.filterNot { n -> n.equals(name, true) }.toSet()) }
        mutable.update { it.copy(rules = rules, status = if (allowed) "已加入白名单：$name，自动整理不会处理" else "已移出白名单：$name") }
        persistRules(rules); reclassify(); record(if (allowed) "白名单 + $name" else "白名单 - $name")
    }

    /** 返回空字符串表示成功，否则为提示。 */
    fun block(name: String, acknowledgedSensitive: Boolean): String {
        val review = state.value.reviews.firstOrNull { it.entry.name == name } ?: return "条目已变化，请重新扫描"
        RootDirectoryOrganizer.blockAllowed(review, acknowledgedSensitive)?.let { return it }
        if (review.entry.directory && !review.entry.empty) return "文件夹仍有内容；请先移除或清理后再禁止重建"
        val result = RootTidyFiles.createPlaceholder(root(), name)
        if (result != RootTidyFiles.PlaceholderResult.CREATED && result != RootTidyFiles.PlaceholderResult.EXISTS) return result.message
        val rules = state.value.rules.let { it.copy(block = it.block + name, allow = it.allow.filterNot { n -> n.equals(name, true) }.toSet()) }
        mutable.update { it.copy(rules = rules, status = "已禁止重建：$name") }
        persistRules(rules); record("禁止重建 $name"); scan(keepStatus = true)
        return ""
    }
    fun unblock(name: String) {
        if (state.value.running) return
        if (!RootTidyFiles.removePlaceholder(root(), name)) { mutable.update { it.copy(status = "同名文件不是白泽创建的空占位，未删除") }; return }
        val rules = state.value.rules.let { it.copy(block = it.block.filterNot { n -> n.equals(name, true) }.toSet()) }
        mutable.update { it.copy(rules = rules, status = "已撤销禁止重建：$name") }
        persistRules(rules); record("撤销禁止重建 $name"); scan(keepStatus = true)
    }

    /** 文件逐个经内容摘要移入回收站，目录在全部移走后自下而上删除；任何一项失败就保留该文件夹剩余内容。 */
    fun removeSelected() {
        val snapshot = state.value
        if (snapshot.running || snapshot.selected.isEmpty()) return
        val chosen = snapshot.reviews.filter { it.entry.name in snapshot.selected && it.removable }
        cancelled.set(false)
        mutable.update { it.copy(running = true, status = "正在移入回收站…") }
        val source = remote
        viewModelScope.launch {
            val (undo, kept) = withContext(Dispatchers.IO) {
                val protection = ApkProtectionStore.refresh(context, ApkProtectionStore.source(context, source))
                val guard = ApkDeletionGuard.forContext(context)
                val trash = OrdinaryFileTrash.forContext(context)
                val budget = OrdinaryFileTrash.budget(context)
                val trashIds = ArrayList<String>(); val dirs = ArrayList<String>(); val zero = ArrayList<String>(); val kept = ArrayList<String>()
                fun protectedPath(path: String): Boolean = guard.protectionDetails(path, protection).let { it.isProtected || it.unavailableReason != null }
                for (review in chosen) {
                    if (cancelled.get()) break
                    val base = File(root(), review.entry.name)
                    if (protectedPath(base.path)) { kept += review.entry.name; continue }
                    try {
                        if (review.entry.directory) {
                            val walk = RootTidyFiles.walk(base, RootDirectoryOrganizer.MAX_FILES_PER_FOLDER, cancelled::get)
                            if (walk.limited || walk.links > 0) { kept += review.entry.name; continue }
                            var failed = false
                            for (file in walk.files) {
                                if (cancelled.get() || protectedPath(file.path)) { failed = true; break }
                                val length = file.length(); val modified = file.lastModified()
                                if (length == 0L) { if (file.delete()) zero += file.path else { failed = true; break }; continue }
                                val hash = OrdinaryFileTrash.digest(file)
                                trashIds += trash.move(file, length, hash, budget) { file.length() == length && file.lastModified() == modified && !protectedPath(file.path) }.id
                            }
                            if (failed) { kept += review.entry.name; continue }
                            val removed = walk.directories.filter { java.nio.file.Files.isDirectory(it.toPath()) }
                            RootTidyFiles.pruneEmptyDirectories(walk.directories)
                            dirs += removed.filterNot { it.exists() }.map { it.path }
                        } else if (review.entry.bytes == 0L) {
                            if (base.delete()) zero += base.path else kept += review.entry.name
                        } else {
                            val length = base.length(); val modified = base.lastModified()
                            trashIds += trash.move(base, length, OrdinaryFileTrash.digest(base), budget) { base.length() == length && base.lastModified() == modified && !protectedPath(base.path) }.id
                        }
                    } catch (_: java.util.concurrent.CancellationException) { kept += review.entry.name; break
                    } catch (_: Exception) { kept += review.entry.name }
                }
                RootTidyUndo(trashIds, dirs, zero) to kept
            }
            val done = chosen.size - kept.size
            record("移除 $done 项${if (kept.isNotEmpty()) "，保留 ${kept.size} 项" else ""}")
            mutable.update { it.copy(running = false, undo = undo, selected = emptySet(),
                status = "已移除 $done 项（文件在回收站保留 30 天）" + if (kept.isNotEmpty()) " · ${kept.size} 项受保护或已变化，已保留" else "") }
            scan(keepStatus = true)
        }
    }

    /** 撤销本次：先按原路径重建目录（父目录在前），再恢复回收站文件与零字节文件。 */
    fun undo() {
        val undo = state.value.undo
        if (state.value.running || undo.empty) return
        mutable.update { it.copy(running = true, status = "正在撤销…") }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                undo.directories.sortedBy { it.length }.forEach { File(it).mkdirs() }
                val trash = OrdinaryFileTrash.forContext(context)
                val entries = runCatching { trash.entries().filter { it.id in undo.trashIds } }.getOrDefault(emptyList())
                var ok = 0
                entries.forEach { entry -> runCatching { File(entry.original).parentFile?.mkdirs(); trash.restore(entry.id, expected = entry) }.onSuccess { ok++ } }
                undo.emptyFiles.forEach { path -> runCatching { File(path).parentFile?.mkdirs(); if (File(path).createNewFile()) ok++ } }
                ok + undo.directories.size
            }
            record("撤销 $restored 项")
            mutable.update { it.copy(running = false, undo = RootTidyUndo(), status = "已撤销，恢复 $restored 项") }
            scan(keepStatus = true)
        }
    }

    override fun onCleared() {
        closed = true; cancelled.set(true)
        if (bound) runCatching { RootService.unbind(connection) }
        super.onCleared()
    }
}

@Composable
internal fun RootTidyScreen(state: RootTidyUiState, onBack: () -> Unit, onView: (StorageToolMode) -> Unit, onScan: () -> Unit,
    onStop: () -> Unit, onToggle: (String) -> Unit, onToggleAll: () -> Unit, onRemove: () -> Unit, onUndo: () -> Unit,
    onAllow: (String, Boolean) -> Unit, onBlock: (String, Boolean) -> String, onUnblock: (String) -> Unit, onPermission: () -> Unit) {
    val context = LocalContext.current
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { DetailPageHeader("根目录整理", "让存储根目录只留需要的文件夹", onBack,
            extra = { StorageViewDropdown(StorageToolMode.ROOT, !state.running, onView) }) {} },
        bottomBar = { if (state.removable.isNotEmpty() && !state.running) CleanSelectionBar(state.selected.size, state.removable.size,
            Formatter.formatFileSize(context, state.selectedBytes), state.recommended.isNotEmpty() && state.selected.containsAll(state.recommended),
            state.recommended.isNotEmpty() || state.selected.isNotEmpty(), onToggleAll, { confirmRemove = true },
            cleanLabel = "移除所选 ${state.selected.size} 项", selectLabel = "选中空文件夹与残留", cleanEnabled = state.selected.isNotEmpty()) }
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                DetailGlassPanel {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    Text("系统标准目录始终保护；有内容的文件夹移入回收站，可撤销。自动整理在“自动任务 → 系统维护”中开启，只处理空文件夹。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.moduleSummary.isNotBlank()) Text(state.moduleSummary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    when {
                        state.permissionRequired -> GlassActionButton("开启${SharedStorageAccess.label}", onPermission, Modifier.fillMaxWidth())
                        state.running -> GlassActionButton("停止", onStop, Modifier.fillMaxWidth(), secondary = true)
                        !state.undo.empty -> GlassActionButton("撤销本次（${state.undo.count} 项）", onUndo, Modifier.fillMaxWidth(),
                            icon = Icons.AutoMirrored.Rounded.Undo, secondary = true)
                        else -> GlassActionButton("重新扫描", onScan, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, secondary = true)
                    }
                }
            }
            ROOT_KIND_ORDER.forEach { kind ->
                val rows = state.reviews.filter { it.kind == kind }
                if (rows.isNotEmpty()) {
                    item(key = "kind-${kind.name}") { DetailSectionHeader(kind.label, "${rows.size} 项") }
                    items(rows, key = { "root-${it.entry.name}" }) { review ->
                        val size = when { !review.entry.directory -> Formatter.formatFileSize(context, review.entry.bytes)
                            review.kind == RootEntryKind.STANDARD -> "受保护"; review.entry.empty -> "空"
                            else -> Formatter.formatFileSize(context, review.entry.bytes) + if (review.entry.limited) "+" else "" }
                        val owner = review.ownerLabel?.let { label -> if (review.installedOwners.isNotEmpty()) "$label（已安装）" else "$label（未安装）" } ?: "未识别归属"
                        DetailResultRow(review.entry.name, size, "$owner · ${review.kind.label}${if (review.entry.limited) " · 文件过多，仅统计部分" else ""}",
                            "/sdcard/${review.entry.name}", "", if (review.entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
                            first = true, last = true, selected = if (review.removable) review.entry.name in state.selected else null,
                            selectionEnabled = !state.running, onToggle = { onToggle(review.entry.name) }, onDetails = { detail = review.entry.name })
                    }
                }
            }
            if (state.history.isNotEmpty()) item {
                DetailGlassPanel {
                    Text("整理记录", style = MaterialTheme.typography.titleMedium)
                    state.history.takeLast(8).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    val review = state.reviews.firstOrNull { it.entry.name == detail }
    if (review != null) RootEntryDialog(review, state.rules, !state.running, onDismiss = { detail = null },
        onAllow = { onAllow(review.entry.name, it); detail = null }, onBlock = { onBlock(review.entry.name, it) },
        onUnblock = { onUnblock(review.entry.name); detail = null })
    if (confirmRemove) BaiZeDialog(onDismissRequest = { confirmRemove = false }, title = { Text("移除 ${state.selected.size} 项") },
        text = { Text("文件夹内的文件逐个核对后移入回收站（保留 30 天），随后删除已清空的目录。共 ${Formatter.formatFileSize(context, state.selectedBytes)}，完成后可一键撤销。受保护或期间变化的内容会保留。") },
        confirmButton = { BaiZeDialogButton(onClick = { confirmRemove = false; onRemove() }) { Text("移入回收站") } },
        dismissButton = { BaiZeDialogButton(onClick = { confirmRemove = false }) { Text("取消") } })
}

@Composable
private fun RootEntryDialog(review: RootEntryReview, rules: RootTidyRules, enabled: Boolean, onDismiss: () -> Unit,
    onAllow: (Boolean) -> Unit, onBlock: (Boolean) -> String, onUnblock: () -> Unit) {
    var acknowledged by rememberSaveable(review.entry.name) { mutableStateOf(false) }
    var confirmBlock by rememberSaveable(review.entry.name) { mutableStateOf(false) }
    var message by rememberSaveable(review.entry.name) { mutableStateOf("") }
    val allowed = rules.allow.any { it.equals(review.entry.name, true) }
    BaiZeDialog(onDismissRequest = onDismiss, title = { Text(review.entry.name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${review.kind.label} · ${review.ownerLabel ?: "未识别归属"}")
            if (review.ownerPackages.isNotEmpty()) Text("可能来自：${review.ownerPackages.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (review.kind == RootEntryKind.STANDARD) Text("Android 公共目录，始终保护。", style = MaterialTheme.typography.bodySmall)
            if (confirmBlock) {
                Text("禁止重建会在原位置放一个同名空文件，应用将无法再创建这个文件夹，可能导致它保存失败或反复报错。可随时撤销。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (review.sensitive) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, { acknowledged = it })
                    Text("我了解这是微信 / QQ 等常用应用的目录", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (review.kind !in setOf(RootEntryKind.STANDARD, RootEntryKind.PROTECTED, RootEntryKind.PLACEHOLDER))
                TextButton(onClick = { onAllow(!allowed) }, enabled = enabled) { Text(if (allowed) "移出白名单" else "加入白名单（不再整理）") }
            if (review.kind == RootEntryKind.PLACEHOLDER) TextButton(onClick = onUnblock, enabled = enabled) { Text("撤销禁止重建") }
            else if (review.canBlock && !confirmBlock) TextButton(onClick = { confirmBlock = true }, enabled = enabled) { Text("禁止重建…") }
        }
    }, confirmButton = {
        if (confirmBlock) BaiZeDialogButton(onClick = { message = onBlock(acknowledged); if (message.isBlank()) onDismiss() }) { Text("确认禁止重建") }
        else BaiZeDialogButton(onClick = onDismiss) { Text("关闭") }
    }, dismissButton = if (confirmBlock) {{ BaiZeDialogButton(onClick = { confirmBlock = false }) { Text("取消") } }} else null)
}

/** 存储分析的视图切换：大文件、重复文件、截图等都是同一页面的视图，而不是独立工具。
 *  放在页头的紧凑“视图：…”下拉里，不占用列表内容，页面只保留一个可滚动容器。 */
@Composable
internal fun StorageViewDropdown(current: StorageToolMode, enabled: Boolean, onSelect: (StorageToolMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 2.dp)) {
        Row(Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClickLabel = "切换视图") { expanded = true }
            .padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("视图：${storageToolTitle(current)}", style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            STORAGE_VIEWS.forEach { mode ->
                DropdownMenuItem(text = { Text(storageToolTitle(mode)) },
                    onClick = { expanded = false; if (mode != current) onSelect(mode) },
                    trailingIcon = if (mode == current) {{ Icon(Icons.Rounded.Check, "当前视图") }} else null)
            }
        }
    }
}

internal val STORAGE_VIEWS = listOf(StorageToolMode.ANALYSIS, StorageToolMode.LARGE, StorageToolMode.DUPLICATES,
    StorageToolMode.SCREENSHOTS, StorageToolMode.OLD_DOWNLOADS, StorageToolMode.CHAT_MEDIA, StorageToolMode.ROOT, StorageToolMode.CUSTOM)
