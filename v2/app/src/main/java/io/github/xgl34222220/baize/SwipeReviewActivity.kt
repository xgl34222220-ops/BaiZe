package io.github.xgl34222220.baize

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sign

class SwipeReviewActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val model: SwipeReviewViewModel by viewModels()
    private val storagePermission = StoragePermissionRequest(this) { model.resumePermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model.ensureOpened()
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            val state by model.state.collectAsState()
            BaiZeTheme(appearance) {
                SwipeReviewScreen(state, SwipeReviewActions(
                    onBack = ::finish, onFolder = model::open, onDecide = model::decide, onUndo = model::undo,
                    onApply = model::requestApply, onConfirmApply = model::apply, onDismissApply = model::dismissApply,
                    onUndoBatch = model::undoBatch, onStop = model::stop, onPermission = storagePermission::launch,
                    onTrash = { CleanerNavigation.open(this, Intent(this, FileTrashActivity::class.java)) },
                    onResetSeen = model::resetSeen))
            }
        }
    }

    override fun onResume() { super.onResume(); model.resumePermission() }
}

internal data class SwipeReviewUiState(
    val folder: SwipeFolder = SwipeFolder.CAMERA,
    val loading: Boolean = false,
    val permissionRequired: Boolean = false,
    val session: SwipeReviewSession = SwipeReviewSession(),
    val applying: Boolean = false,
    val applyProgress: String = "",
    val confirmApply: Boolean = false,
    val status: String = "",
    val lastBatch: List<TrashEntry> = emptyList(),
    /** 当前文件夹里已看过（保留或已移入回收站）而被排除的文件数。 */
    val seenCount: Int = 0
) {
    val busy: Boolean get() = loading || applying
}

internal data class SwipeReviewActions(
    val onBack: () -> Unit = {},
    val onFolder: (SwipeFolder) -> Unit = {},
    val onDecide: (SwipeDecision) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onApply: () -> Unit = {},
    val onConfirmApply: () -> Unit = {},
    val onDismissApply: () -> Unit = {},
    val onUndoBatch: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPermission: () -> Unit = {},
    val onTrash: () -> Unit = {},
    val onResetSeen: () -> Unit = {}
)

internal class SwipeReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(SwipeReviewUiState())
    val state: StateFlow<SwipeReviewUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var opened = false
    private val stopRequested = AtomicBoolean(false)
    private val memory = SwipeReviewMemory(File(application.filesDir, SwipeReviewMemory.FILE_NAME))
    /** 「已看过」记录的读写串行化在一个 IO 线程上，避免与读取文件夹并发。 */
    private val memoryDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun ensureOpened() { if (!opened) open(state.value.folder) }

    fun resumePermission() {
        val current = state.value
        if (current.permissionRequired && SharedStorageAccess.granted(getApplication())) open(current.folder)
    }

    fun open(folder: SwipeFolder) = load(folder, "")

    private fun load(folder: SwipeFolder, message: String) {
        if (state.value.applying) return
        opened = true
        loadJob?.cancel()
        val context = getApplication<Application>()
        if (!SharedStorageAccess.granted(context)) {
            mutableState.update { it.copy(folder = folder, loading = false, permissionRequired = true,
                session = SwipeReviewSession(), status = "需要${SharedStorageAccess.label}才能读取${folder.label}") }
            return
        }
        mutableState.update { it.copy(folder = folder, loading = true, permissionRequired = false,
            session = SwipeReviewSession(), confirmApply = false, status = message.ifBlank { "正在读取${folder.label}…" }) }
        loadJob = viewModelScope.launch {
            val (items, seen) = withContext(memoryDispatcher) {
                runCatching {
                    SwipeReviewSource.listUnseen(folderDirectory(folder), memory) { !isActive }
                }.getOrDefault(emptyList<SwipeItem>() to 0)
            }
            mutableState.update { it.copy(loading = false, session = SwipeReviewSession(items), seenCount = seen, status = message.ifBlank {
                val seenText = if (seen > 0) "，已跳过 $seen 个看过的文件" else ""
                if (items.isEmpty()) "${folder.label}中没有待整理的文件$seenText"
                else "共 ${items.size} 个文件${if (items.size >= SwipeReviewSource.MAX_ITEMS) "（仅最新 ${SwipeReviewSource.MAX_ITEMS} 个）" else ""}$seenText，从最新开始"
            }) }
        }
    }

    private fun folderDirectory(folder: SwipeFolder): File =
        File(Environment.getExternalStorageDirectory().canonicalFile, folder.relativePath)

    fun decide(decision: SwipeDecision) {
        if (state.value.busy) return
        val item = state.value.session.current ?: return
        mutableState.update { it.copy(session = it.session.decide(decision)) }
        // 「保留」立即记为已看过；「删除」只有真正移入回收站后才记录（见 apply）。
        if (decision == SwipeDecision.KEEP) viewModelScope.launch(memoryDispatcher) {
            memory.remember(listOf(item), SwipeDecision.KEEP)
        }
    }

    fun undo() {
        val current = state.value
        if (current.busy || !current.session.canUndo) return
        val undone = current.session.items.getOrNull(current.session.decisions.lastIndex)
        val undoneDecision = current.session.decisions.lastOrNull()
        mutableState.update { it.copy(session = it.session.undo()) }
        if (undone != null && undoneDecision == SwipeDecision.KEEP) viewModelScope.launch(memoryDispatcher) {
            memory.forget(listOf(undone.path))
        }
    }

    /** 重置当前文件夹的「已看过」：只清记录，不动文件，然后重新读取。 */
    fun resetSeen() {
        val current = state.value
        if (current.busy) return
        viewModelScope.launch {
            val removed = withContext(memoryDispatcher) {
                runCatching { memory.reset(folderDirectory(current.folder).absolutePath) }.getOrDefault(0)
            }
            load(current.folder, "已重置 $removed 个看过的文件，从最新开始")
        }
    }

    fun requestApply() {
        if (state.value.busy || state.value.session.deletions.isEmpty()) return
        mutableState.update { it.copy(confirmApply = true) }
    }

    fun dismissApply() = mutableState.update { it.copy(confirmApply = false) }

    fun stop() = stopRequested.set(true)

    fun apply() {
        val current = state.value
        val items = current.session.deletions
        if (current.busy || items.isEmpty()) return
        stopRequested.set(false)
        mutableState.update { it.copy(confirmApply = false, applying = true, applyProgress = "", status = "正在移入回收站…") }
        val context = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val trash = OrdinaryFileTrash.forContext(context)
                val budget = OrdinaryFileTrash.budget(context)
                SwipeReviewApplier.apply(items, { OrdinaryFileTrash.digest(it) },
                    { file, item, hash, validate -> trash.move(file, item.bytes, hash, budget, validate) },
                    stopRequested::get) { index, item ->
                    mutableState.update { it.copy(applyProgress = "${index + 1} / ${items.size} · ${item.name}") }
                }.also { applied ->
                    if (applied.moved.isNotEmpty()) runCatching {
                        MediaScannerConnection.scanFile(context, applied.moved.map { it.original }.toTypedArray(), null, null)
                    }
                    // 只记录确实移入回收站的项（按原路径核对）；保留/跳过的文件下次仍会出现。
                    val movedPaths = applied.movedPaths
                    withContext(memoryDispatcher) {
                        runCatching { memory.remember(items.filter { it.path in movedPaths }, SwipeDecision.DELETE) }
                    }
                }
            }
            val size = Formatter.formatFileSize(context, result.movedBytes)
            mutableState.update { it.copy(applying = false, applyProgress = "",
                session = it.session.withoutApplied(result.movedPaths), lastBatch = result.moved,
                status = buildString {
                    append("已移入回收站 ${result.moved.size} 项（$size），尚未释放空间，可在回收站恢复。")
                    if (result.skipped.isNotEmpty()) append(" ${result.skipped.size} 项保留：${result.skipped.first().second}")
                }) }
        }
    }

    /** 撤销最近一次移入：逐个从回收站恢复，恢复前仍核对回收记录与内容。 */
    fun undoBatch() {
        val current = state.value
        val batch = current.lastBatch
        if (current.busy || batch.isEmpty()) return
        mutableState.update { it.copy(applying = true, status = "正在从回收站恢复…") }
        val context = getApplication<Application>()
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val trash = OrdinaryFileTrash.forContext(context)
                batch.mapNotNull { entry -> runCatching { trash.restore(entry.id, expected = entry) }.getOrNull() }.also { files ->
                    if (files.isNotEmpty()) runCatching {
                        MediaScannerConnection.scanFile(context, files.map { it.path }.toTypedArray(), null, null)
                    }
                }
            }
            withContext(memoryDispatcher) { runCatching { memory.forget(batch.map { it.original }) } }
            mutableState.update { it.copy(applying = false, lastBatch = emptyList()) }
            load(current.folder, if (restored.size == batch.size) "已恢复 ${restored.size} 项"
                else "已恢复 ${restored.size} / ${batch.size} 项，其余仍在回收站")
        }
    }
}

@Composable
internal fun SwipeReviewScreen(state: SwipeReviewUiState, actions: SwipeReviewActions) {
    val context = LocalContext.current
    val session = state.session
    // 移入回收站后弹出「撤销」：与“撤销本次移入”同一恢复流程，逐项核对回收记录与内容。
    val undoSnackbar = remember { SnackbarHostState() }
    TrashUndoSnackbarEffect(undoSnackbar, state.lastBatch.takeIf { it.isNotEmpty() },
        TrashUndo.message(state.lastBatch.size, Formatter.formatFileSize(context, state.lastBatch.sumOf { it.bytes })),
        onUndo = actions.onUndoBatch)
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        snackbarHost = { SnackbarHost(undoSnackbar) },
        topBar = { DetailPageHeader("滑动整理", "左滑删除 · 右滑保留 · 随时撤销", actions.onBack) {
            TextButton(onClick = actions.onTrash, enabled = !state.busy) { Text("回收站") }
        } },
        bottomBar = {
            if (session.deletions.isNotEmpty() && !state.permissionRequired) Surface(color = BaiZeTokens.colors.surfaceRaised) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("待删除 ${session.deletions.size} 项", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(Formatter.formatFileSize(context, session.deleteBytes), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.applying) GlassActionButton("停止", actions.onStop, secondary = true, compact = true)
                    else GlassActionButton("移入回收站", actions.onApply, icon = Icons.Rounded.DeleteSweep,
                        enabled = !state.busy, compact = true)
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailGlassPanel {
                FileFilterChoices("文件夹", SwipeFolder.entries.map { it to it.label }, state.folder) {
                    if (!state.busy) actions.onFolder(it)
                }
                if (session.items.isNotEmpty()) Text(
                    "第 ${minOf(session.position + 1, session.items.size)} / ${session.items.size} 项 · 保留 ${session.keptCount} 项",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall)
                if (state.seenCount > 0 && !state.busy && !state.permissionRequired) TextButton(onClick = actions.onResetSeen,
                    modifier = Modifier.testTag("swipe-reset-seen")) { Text("重置「已看过」（${state.seenCount}）") }
                if (state.busy) {
                    Spacer(Modifier.height(8.dp))
                    BaiZeProgress()
                    if (state.applyProgress.isNotBlank()) BaiZePathText(state.applyProgress, Modifier.padding(top = 6.dp), live = true)
                }
                if (state.permissionRequired) {
                    Spacer(Modifier.height(10.dp))
                    GlassActionButton("开启${SharedStorageAccess.label}", actions.onPermission, Modifier.fillMaxWidth())
                }
            }
            if (state.lastBatch.isNotEmpty() && !state.busy) DetailGlassPanel {
                Text("已移入回收站 ${state.lastBatch.size} 项", style = MaterialTheme.typography.titleMedium)
                Text("文件仍在回收站，30 天内可恢复。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.onUndoBatch) { Text("撤销本次移入") }
            }
            val current = session.current
            when {
                state.permissionRequired || state.loading -> Unit
                current != null -> {
                    SwipeCardStack(current, session.next, enabled = !state.busy, onDecide = actions.onDecide)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassActionButton("删除", { actions.onDecide(SwipeDecision.DELETE) }, Modifier.weight(1f),
                            icon = Icons.Rounded.Delete, enabled = !state.busy, secondary = true)
                        GlassActionButton("撤销", actions.onUndo, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Undo,
                            enabled = !state.busy && session.canUndo, secondary = true)
                        GlassActionButton("保留", { actions.onDecide(SwipeDecision.KEEP) }, Modifier.weight(1f),
                            icon = Icons.Rounded.Check, enabled = !state.busy)
                    }
                }
                session.finished -> {
                    DetailEmptyState("这个文件夹已看完",
                        if (session.deletions.isEmpty()) "没有标记删除的文件。" else "确认底部“移入回收站”后才会移动文件；也可以撤销上一个决定。",
                        icon = Icons.Rounded.TaskAlt)
                    if (session.canUndo) TextButton(onClick = actions.onUndo, enabled = !state.busy,
                        modifier = Modifier.padding(horizontal = 16.dp)) { Text("撤销上一个决定") }
                }
                !state.busy && state.lastBatch.isEmpty() -> DetailEmptyState("没有可整理的文件", "切换文件夹，或稍后再试。")
            }
        }
    }
    if (state.confirmApply) BaiZeDialog(onDismissRequest = actions.onDismissApply,
        title = { Text("移入回收站？") },
        text = { Text("将把 ${session.deletions.size} 项（${Formatter.formatFileSize(context, session.deleteBytes)}）移入白泽回收站。" +
            "移动前会再次核对文件未变化；30 天内可在回收站恢复，不会直接永久删除。") },
        confirmButton = { val haptics = io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptics(); BaiZeDialogButton(onClick = { haptics.confirmDelete(); actions.onConfirmApply() }) { Text("移入回收站") } },
        dismissButton = { BaiZeDialogButton(onClick = actions.onDismissApply) { Text("再看看") } })
}

@Composable
private fun SwipeCardStack(item: SwipeItem, next: SwipeItem?, enabled: Boolean, onDecide: (SwipeDecision) -> Unit) {
    val scope = rememberCoroutineScope()
    val decide by rememberUpdatedState(onDecide)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(420.dp)) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val threshold = width * .28f
        if (next != null) SwipeCard(next, Modifier.fillMaxSize().graphicsLayer { scaleX = .94f; scaleY = .94f; translationY = 18f; alpha = .6f })
        key(item.path) {
            val offset = remember { Animatable(0f) }
            val progress = (offset.value / threshold).coerceIn(-1f, 1f)
            SwipeCard(item, Modifier.fillMaxSize()
                .testTag("swipe-card")
                .semantics { contentDescription = "滑动卡片：${item.name}，左滑删除，右滑保留" }
                .graphicsLayer { translationX = offset.value; rotationZ = offset.value / width * 10f }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val value = offset.value
                                if (abs(value) >= threshold) {
                                    offset.animateTo(sign(value) * width * 1.4f, tween(160))
                                    decide(if (value < 0) SwipeDecision.DELETE else SwipeDecision.KEEP)
                                } else offset.animateTo(0f, spring())
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + amount) }
                        })
                }, hint = progress)
        }
    }
}

@Composable
private fun SwipeCard(item: SwipeItem, modifier: Modifier, hint: Float = 0f) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val colors = BaiZeTokens.colors
    val preview by produceState<Bitmap?>(null, item.path, item.modifiedMs) {
        value = withContext(Dispatchers.IO) { swipeThumbnail(item) }
    }
    Surface(modifier, shape = RoundedCornerShape(26.dp), color = colors.surfaceRaised, shadowElevation = 3.dp) {
        Column {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(scheme.primary.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                val bitmap = preview
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(swipeIcon(item.kind), null, Modifier.size(64.dp), tint = scheme.primary.copy(alpha = .7f))
                if (abs(hint) > .05f) Box(Modifier.fillMaxSize().background(
                    (if (hint < 0) colors.danger else colors.success).copy(alpha = abs(hint) * .28f)),
                    contentAlignment = if (hint < 0) Alignment.TopEnd else Alignment.TopStart) {
                    Icon(if (hint < 0) Icons.Rounded.Delete else Icons.Rounded.Check, null,
                        Modifier.padding(20.dp).size(44.dp).graphicsLayer { alpha = abs(hint) },
                        tint = if (hint < 0) colors.danger else colors.success)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text("${Formatter.formatFileSize(context, item.bytes)} · ${
                    if (item.modifiedMs > 0) android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", item.modifiedMs) else "时间未知"}",
                    fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        }
    }
}

private fun swipeIcon(kind: String): ImageVector = when (kind) {
    "image" -> Icons.Rounded.Image; "video" -> Icons.Rounded.Movie; "apk" -> Icons.Rounded.InstallMobile
    "archive" -> Icons.Rounded.FolderZip; else -> Icons.Rounded.Description
}

/** 预览只读、降采样且在 IO 线程完成；失败时退回类型图标。 */
private fun swipeThumbnail(item: SwipeItem): Bitmap? = runCatching {
    val file = File(item.path)
    when (item.kind) {
        "image" -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1080) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        "video" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ThumbnailUtils.createVideoThumbnail(file, Size(720, 720), null) else null
        else -> null
    }
}.getOrNull()
