package io.github.xgl34222220.baize

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import org.json.JSONObject

internal class StorageToolsViewModel(application: Application) : AndroidViewModel(application) {
    private data class StorageScanBundle(val index: StorageIndexResult, val duplicates: StorageDuplicateResult, val buckets: List<StorageAnalysisBucket>, val growth: StorageGrowthResult, val directoryUsage: DirectoryUsage?,
        val chat: ChatStorageRecords.Scan? = null)
    private val mutableState = MutableStateFlow(StorageToolsUiState())
    val state = mutableState.asStateFlow()
    private val digestCache = StorageDigestCache()
    private var initialized = false
    private var control = StorageScanControl()
    private var directoryToken = ""
    private val context get() = getApplication<Application>()
    @Volatile private var remote: IProfileRootService? = null
    /** 本次确认中由 Root 处理的聊天文件（无系统索引，不参与内容核对）。 */
    private var rootReview: Set<String> = emptySet()
    private var bound = false
    private var closed = false
    private var contentReview: Map<String, IndexedContentProof> = emptyMap()
    private val mutablePrivate = MutableStateFlow(ChatPrivateState())
    /** 聊天媒体页 · 应用私有数据（Root）：只在用户点按时扫描，不随页面或开机自动运行。 */
    val privateState = mutablePrivate.asStateFlow()
    @Volatile private var privateCancelled = false
    private val connection = object : RootService.Connection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            if (binder == null) { onBindingFailed(name, RootService.BindingFailure.NULL_BINDING); return }
            remote = RootServiceClients.profile(binder, context.cacheDir)
            bound = true
            refreshProtection()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            if (closed) return
            remote = null; bound = false; refreshProtection()
        }
        override fun onBindingFailed(name: ComponentName?, reason: RootService.BindingFailure) {
            if (closed) return
            remote = null; bound = false
            mutableState.update { it.copy(protectionMessage = RootConnectionMessages.bindingFailure(reason)) }
        }
        override fun onNullBinding(name: ComponentName?) = onBindingFailed(name, RootService.BindingFailure.NULL_BINDING)
    }

    fun initialize(mode: StorageToolMode) {
        if (initialized) return
        initialized = true
        mutableState.value = StorageToolsUiState(mode = mode, minimumBytes = if (mode == StorageToolMode.LARGE) 100 * MIB else 0,
            minimumAgeDays = StorageReviewFilters.defaultAgeDays(mode),
            customFilters = if (mode == StorageToolMode.CUSTOM) customFilterPreferences().let {
                StorageReviewFilters.decode(it.getString("filters", null)) } else emptyList())
        val prefs = context.getSharedPreferences("duplicate-preferences", 0)
        mutableState.update { it.copy(keeperPreference = runCatching { DuplicateKeeperPreference.valueOf(prefs.getString("keeper", "NEWEST")!!) }.getOrDefault(DuplicateKeeperPreference.NEWEST), keeperDirectory = prefs.getString("directory", "").orEmpty()) }
        connect()
        refreshProtection()
        scan()
    }
    fun connect() {
        if (closed || remote != null || bound) return
        bound = true
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java)
            .addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }.onFailure {
            bound = false; refreshProtection()
        }
    }
    private fun protection() = ApkProtectionStore.refresh(context, ApkProtectionStore.source(context, remote))
    private fun refreshProtection() {
        val source = remote
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { ApkProtectionStore.refresh(context, ApkProtectionStore.source(context, source)) }
            if (closed || remote !== source) return@launch
            mutableState.update { it.copy(protectionMessage = (state as? ApkProtectionState.Unknown)?.reason.orEmpty(),
                localModeAvailable = state is ApkProtectionState.Unknown && !ApkProtectionStore.rootWasUsed(context)) }
        }
    }
    fun enableLocalMode() {
        if (state.value.running) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ApkProtectionStore.enableLocalOnly(context) }
            refreshProtection()
        }
    }
    fun diagnose(record: StorageFileRecord) {
        if (state.value.diagnosticBusy) return
        mutableState.update { it.copy(diagnostic = "", diagnosticUri = record.uri, diagnosticBusy = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching {
                ApkFileReadDiagnostics.collect(context, record.uri, record.path, remote, record.identity, indexedFile = true)
            }.getOrElse { JSONObject().put("error", it.javaClass.simpleName).put("message", it.message.orEmpty()).toString(2) } }
            mutableState.update { it.copy(diagnosticBusy = false, diagnostic = report) }
        }
    }
    fun resumePermission() { if (state.value.permissionRequired && StorageMediaRepository.hasAccess(context)) scan() }
    fun stop() { if (state.value.reviewRequested) dismissDeleteReview()
        else { control.cancel()
            val source = remote; val token = directoryToken
            if (source != null && token.isNotBlank()) viewModelScope.launch(Dispatchers.IO) {
                runCatching { RootServiceClients.profileExchange(source, context.cacheDir, "cancelDirectoryUsage", org.json.JSONArray().put(token)) }
            }
            mutableState.update { it.copy(status = "正在停止…") } } }
    fun toggle(key: String) { if (!state.value.running) mutableState.update { it.toggleSelection(key) } }
    fun toggleAll() { if (!state.value.running) mutableState.update { it.toggleAllSelection() } }
    fun filter(query: String = state.value.query, category: String? = state.value.category,
               sort: StorageSort = state.value.sort, minimumBytes: Long = state.value.minimumBytes,
               minimumAgeDays: Int = state.value.minimumAgeDays) {
        if (state.value.running) return
        mutableState.update { it.copy(query = query, category = category, directory = if (category != null) null else it.directory, sort = sort, minimumBytes = minimumBytes,
            minimumAgeDays = minimumAgeDays.coerceAtLeast(0), selected = emptySet()) }
    }

    private fun customFilterPreferences() = context.getSharedPreferences("storage-custom-filters", 0)

    /** 返回空字符串表示已保存；否则为给用户看的校验错误。保存后重新扫描以更新候选集。 */
    fun saveCustomFilter(name: String, pattern: String, ageDays: Int, minMegabytes: Long): String {
        if (state.value.running) return "正在扫描，请先停止"
        val current = state.value.customFilters
        if (current.size >= StorageReviewFilters.MAX_CUSTOM_FILTERS) return "最多保存 ${StorageReviewFilters.MAX_CUSTOM_FILTERS} 条规则"
        val input = StorageReviewFilters.validate(java.util.UUID.randomUUID().toString(), name, pattern, ageDays, minMegabytes)
        val filter = input.filter ?: return input.error
        if (current.any { it.pattern.equals(filter.pattern, true) && it.minAgeDays == filter.minAgeDays && it.minBytes == filter.minBytes })
            return "已有相同的规则"
        val next = current + filter
        if (!customFilterPreferences().edit().putString("filters", StorageReviewFilters.encode(next)).commit()) return "规则保存失败，请重试"
        mutableState.update { it.copy(customFilters = next, activeFilterId = filter.id, selected = emptySet()) }
        scan()
        return ""
    }
    fun deleteCustomFilter(id: String) {
        if (state.value.running) return
        val next = state.value.customFilters.filterNot { it.id == id }
        if (!customFilterPreferences().edit().putString("filters", StorageReviewFilters.encode(next)).commit()) return
        mutableState.update { it.copy(customFilters = next, activeFilterId = it.activeFilterId.takeIf { active -> active != id },
            selected = emptySet(), records = if (next.isEmpty()) emptyList() else it.records, status = "规则已删除，文件未改动") }
        if (next.isNotEmpty()) scan()
    }
    fun selectCustomFilter(id: String?) {
        if (!state.value.running) mutableState.update { it.copy(activeFilterId = id, selected = emptySet()) }
    }

    /** 只恢复本页刚移入回收站的那一批；恢复不覆盖同名新文件，失败项留在回收站。 */
    fun undoLastTrash() {
        val snapshot = state.value
        if (snapshot.running || snapshot.lastTrashed.isEmpty()) return
        val ids = snapshot.lastTrashed.toSet()
        mutableState.update { it.copy(running = true, status = "正在从回收站恢复…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                TrashUndo.restoreBatch(OrdinaryFileTrash.forContext(context), ids).also { undo ->
                    if (undo.restored.isNotEmpty()) runCatching {
                        android.media.MediaScannerConnection.scanFile(context, undo.restored.map { it.path }.toTypedArray(), null, null)
                    }
                }
            }
            mutableState.update { it.copy(running = false, lastTrashed = emptyList(), lastTrashedBytes = 0L,
                status = TrashUndo.resultMessage(result)) }
            if (result.restoredCount > 0) startScan(keepStatus = true)
        }
    }

    fun setKeeperPreference(preference: DuplicateKeeperPreference, directory: String) {
        if (state.value.running) return
        val path = directory.trim().trimEnd('/')
        if (preference == DuplicateKeeperPreference.DIRECTORY && !StorageMediaRepository.safeSharedFile("$path/file")) return
        context.getSharedPreferences("duplicate-preferences", 0).edit().putString("keeper", preference.name).putString("directory", path).apply()
        mutableState.update { it.copy(keeperPreference = preference, keeperDirectory = path, selected = emptySet(), status = "保留偏好已更新；请重新勾选多余副本") }
    }
    fun keepCopy(uri: String) { mutableState.update { it.keepCopy(uri) } }

    fun directory(path: String?) {
        if (!state.value.running) mutableState.update { it.copy(directory = path, category = null, selected = emptySet(), query = "") }
    }
    fun scan() = startScan(keepStatus = false)
    private fun startScan(keepStatus: Boolean) {
        if (state.value.running) return
        if (state.value.mode == StorageToolMode.CUSTOM && state.value.customFilters.isEmpty()) {
            mutableState.update { it.copy(status = "先新建一条规则，再预览匹配的文件", records = emptyList()) }
            return
        }
        if (!StorageMediaRepository.hasAccess(context)) {
            mutableState.update { it.copy(permissionRequired = true, status = "开启文件访问后，开始分析共享存储") }
            return
        }
        control = StorageScanControl()
        val taskControl = control
        val mode = state.value.mode
        val filters = state.value.customFilters
        val previousStatus = state.value.status
        contentReview = emptyMap()
        mutableState.update { it.copy(running = true, reviewRequested = false, permissionRequired = false, failed = false, status = "正在读取系统文件索引…",
            lastTrashed = if (keepStatus) it.lastTrashed else emptyList(), nowSeconds = System.currentTimeMillis() / 1000,
            selected = emptySet(), records = emptyList(), duplicateGroups = emptyList(), buckets = emptyList(), coverage = "", progress = null,
            outcomes = emptyMap(), directoryUsage = null, diagnostic = "", diagnosticUri = "") }
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val result = withContext(Dispatchers.IO) {
                    var lastProgress = 0L
                    var lastPhase = ""
                    val report: (StorageScanProgress) -> Unit = { progress ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 120 || progress.phase != lastPhase || progress.completed == progress.total) {
                            lastProgress = now; lastPhase = progress.phase
                            mutableState.update { it.copy(progress = progress, status = progress.phase) }
                        }
                    }
                    val rawIndex = StorageMediaRepository.scanIndex(context, if (mode == StorageToolMode.LARGE) 10 * MIB else 1, taskControl, report)
                    // 新增工具先按来源缩小候选集，再做存在性与身份核对，避免对全部索引逐项核对。
                    val scoped = if (mode.review) rawIndex.copy(records = rawIndex.records.filter {
                        taskControl.check(); StorageReviewFilters.candidate(mode, it, filters) }) else rawIndex
                    val index = StorageMediaRepository.reviewPresence(context, scoped, remote, taskControl)
                    val duplicates = if (mode == StorageToolMode.DUPLICATES)
                        StorageMediaRepository.findDuplicates(context, index.records, taskControl, report, digestCache) { groups ->
                            mutableState.update { it.copy(records = index.records, duplicateGroups = groups, coverage = "扫描进行中，仅展示已完成内容校验的重复组；操作前仍需重新核对。") }
                        } else StorageDuplicateResult(emptyList(), 0)
                    val growth = if (mode == StorageToolMode.ANALYSIS) runCatching {
                        StorageGrowthStore(java.io.File(context.filesDir, "storage-growth.json")).record(index)
                    }.getOrElse { StorageGrowthResult(emptyList(), "增长基线写入失败，本轮不显示比较") } else StorageGrowthResult(emptyList(), "")
                    val usage = if (mode == StorageToolMode.ANALYSIS) {
                        report(StorageScanProgress("正在遍历目录…", 0, 0, ""))
                        val source = remote
                        directoryToken = java.util.UUID.randomUUID().toString()
                        val rootUsage = source?.let { runCatching {
                            taskControl.check()
                            DirectoryUsage.parse(RootServiceClients.profileExchange(it, context.cacheDir,
                                "scanDirectoryUsage", org.json.JSONArray().put(directoryToken)))
                        }.getOrNull() }
                        taskControl.check()
                        val localRoots = (listOf(android.os.Environment.getExternalStorageDirectory()) +
                            context.getExternalFilesDirs(null).filterNotNull().mapNotNull { file ->
                                file.path.substringBefore("/Android/", "").takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                            }).distinctBy { it.canonicalPath }.associate { it.canonicalFile to it.canonicalPath }
                        val local = DirectoryUsageScanner.scan(if (rootUsage != null)
                            localRoots.filterValues { it !in rootUsage.roots } else localRoots,
                            check = taskControl::check, progress = { count, name -> report(StorageScanProgress("正在遍历目录…", count, 0, name)) })
                        directoryToken = ""
                        if (rootUsage == null) local else DirectoryUsage(rootUsage.roots + local.roots,
                            rootUsage.directories + local.directories, rootUsage.inaccessible + local.inaccessible,
                            rootUsage.linksSkipped + local.linksSkipped, rootUsage.limited || local.limited, "Root + 本地")
                    } else null
                    // 在扫描线程预建目录层级索引，钻取与环形图不在主线程遍历全部目录。
                    usage?.tree
                    // QQ / 微信在 Android 11+ 把收到的文件与聊天媒体放在 Android/data，系统索引看不到。
                    // 聊天媒体视图与分类视图（安装包）补充 Root 扫描结果；这些行勾选后由 Root 移入回收站。
                    val chat = if (mode == StorageToolMode.CHAT_MEDIA || mode == StorageToolMode.ANALYSIS) {
                        report(StorageScanProgress("正在读取 QQ / 微信目录…", 0, 0, ""))
                        taskControl.check()
                        ChatStorageRecords.fetch(remote, context.cacheDir, apksOnly = mode == StorageToolMode.ANALYSIS)
                    } else null
                    taskControl.check()
                    val merged = if (chat == null || chat.records.isEmpty()) index
                        else index.copy(records = ChatStorageRecords.merge(index.records, chat.records))
                    StorageScanBundle(merged, duplicates, storageBuckets(merged.records), growth, usage, chat)
                }
                taskControl.check()
                val index = result.index
                val duplicates = result.duplicates
                mutableState.update { current -> current.copy(running = false, records = index.records,
                    duplicateGroups = duplicates.groups, buckets = result.buckets, directoryUsage = result.directoryUsage, growth = result.growth.changes, growthDescription = result.growth.description, progress = null,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    status = when (mode) {
                        StorageToolMode.LARGE -> "大文件扫描完成"
                        StorageToolMode.DUPLICATES -> "发现 ${duplicates.groups.size} 组内容相同的文件"
                        StorageToolMode.ANALYSIS -> "存储分析完成"
                        StorageToolMode.SCREENSHOTS, StorageToolMode.OLD_DOWNLOADS, StorageToolMode.CHAT_MEDIA, StorageToolMode.CUSTOM, StorageToolMode.ROOT ->
                            (if (keepStatus) "$previousStatus · " else "") + "找到 ${index.records.size} 个候选文件，可按时间筛选后勾选"
                    }, nowSeconds = System.currentTimeMillis() / 1000, coverage = buildString {
                        append("分类和文件操作覆盖 ${index.records.size} 个已索引文件。回收站占用另计，移入回收站不等于设备释放空间。")
                        result.directoryUsage?.let { usage ->
                            append(" 目录统计使用${usage.backend}遍历，包含未索引文件，仅供查看；${if (usage.backend.startsWith("Root")) "包含当前用户的应用私有目录" else "不包含无权读取的应用私有目录"}。")
                            if (usage.inaccessible > 0) append(" ${usage.inaccessible} 处无法读取，统计为已读部分。")
                            if (usage.limited) append(" 达到遍历时间、深度或数量上限，目录统计不完整。")
                            if (usage.linksSkipped > 0) append(" 已跳过 ${usage.linksSkipped} 个链接。")
                            append(" 数值为文件逻辑大小，目录包含子目录，不代表可释放容量。")
                        }
                        if (index.truncated) append(" 本次达到 12 万项上限，结果不完整。")
                        result.chat?.let { chat ->
                            val rootRows = index.records.count(ChatStorageRecords::isRootRecord)
                            when {
                                rootRows > 0 -> append(" 另有 $rootRows 个 QQ / 微信应用目录文件由 Root 读取（标“Root”），勾选确认后由 Root 核对身份并移入回收站；" +
                                    "Cache_ 缓存副本可全选，其余逐项勾选。")
                                chat.error == "root_unavailable" -> append(" Root 服务未连接，QQ / 微信的 Android/data 目录未读取。")
                                chat.error.isNotBlank() -> append(" QQ / 微信目录读取失败（${chat.error}）。")
                            }
                            if (chat.truncated) append(" QQ / 微信目录达到读取上限，结果不完整。")
                        }
                        if (index.confirmedMissing > 0) append(" 已排除 ${index.confirmedMissing} 条不存在文件的旧索引，未计入占用或释放空间。")
                        val unknown = index.records.count { it.verifiedBytes == 0L && !ChatStorageRecords.isRootRecord(it) }
                        if (unknown > 0) append(" $unknown 项尚未核对文件身份，不能勾选，不计入可处理容量；点文件查看诊断。")
                        if (index.presenceIncomplete) append(" 文件存在性核对未全部完成，可连接服务后重新扫描。")
                        if (duplicates.reusedHashes > 0) append(" 按当前文件身份复用 ${duplicates.reusedHashes} 次内容摘要；删除授权仍逐次重新核对。")
                        if (duplicates.skipped > 0) append(" ${duplicates.skipped} 次文件校验因不可读或文件变化而跳过。")
                    }) }
                recordHomeSummary(mutableState.value, partial = index.truncated || index.presenceIncomplete)
            } catch (error: Exception) {
                directoryToken = ""
                val stopped = taskControl.cancelled || error is CancellationException || error is android.os.OperationCanceledException
                mutableState.update { it.copy(running = false, progress = null, failed = !stopped,
                    elapsedMs = SystemClock.elapsedRealtime() - started,
                    status = if (stopped) "扫描已停止" else "扫描未完成：${error.message ?: "文件读取失败"}",
                    coverage = if (stopped) "扫描已停止，仅保留已完成内容校验的部分结果。重新扫描会再次读取文件内容；清理仍需重新确认。" else "请检查文件访问权限后重试。") }
            }
        }
    }

    fun prepareDeleteReview() {
        val snapshot = state.value
        if (snapshot.running || snapshot.selected.isEmpty()) return
        val chosen = snapshot.allRecords.filter { it.uri in snapshot.selected }
        control = StorageScanControl()
        val task = control
        contentReview = emptyMap()
        // 相机与相册原件只能逐项勾选；确认前再单独点明，避免被当作普通文件一并删除。
        val userMedia = chosen.count { UserMediaGuard.isUserMedia(it.path) && snapshot.mode != StorageToolMode.SCREENSHOTS }
        val userMediaNote = if (userMedia > 0) "其中 $userMedia 个是相机与相册原件（DCIM / Pictures / Movies），均为你逐项勾选，请确认不再需要。" else ""
        mutableState.update { it.copy(running = true, reviewRequested = true,
            reviewMessage = "只读取已选文件内容，可随时取消。", status = "正在核对所选文件内容…") }
        viewModelScope.launch {
            try {
                // Root 读取的聊天文件没有系统索引，内容核对由 Root 在移动前按文件身份完成。
                val rootChosen = chosen.filter(ChatStorageRecords::isRootRecord)
                val indexedChosen = chosen.filterNot(ChatStorageRecords::isRootRecord)
                rootReview = rootChosen.map { it.uri }.toSet()
                val rootNote = if (rootChosen.isEmpty()) "" else "其中 ${rootChosen.size} 个是 QQ / 微信应用目录文件（Root），确认后由 Root 再次核对文件身份并移入回收站（隔离区，可恢复）。"
                val batch = if (indexedChosen.isEmpty()) IndexedReviewBatch(emptyMap(), emptyMap()) else withContext(Dispatchers.IO) { IndexedContentReview.prepare(indexedChosen.map { it.asIndexedCandidate() },
                    ApkDeletionGuard.forContext(context), { closed || task.cancelled }) { done, total ->
                    mutableState.update { if (control === task && it.running && it.reviewRequested)
                        it.copy(reviewMessage = "正在核对 $done / $total 个文件，仅核对所选内容…") else it }
                } }
                if (closed || task.cancelled || control !== task) return@launch
                contentReview = batch.proofs
                mutableState.update { it.copy(running = false, selected = batch.proofs.keys + rootReview,
                    outcomes = (it.outcomes - batch.proofs.keys) + batch.rejected.mapValues { entry -> StorageDeleteOutcome(ApkIndexedDeleteResult.UNVERIFIED, entry.value) },
                    reviewMessage = userMediaNote + rootNote + "已核对 ${batch.proofs.size + rootReview.size} 个文件。" +
                        if (batch.rejected.isEmpty()) "确认后移入回收站，共享文件进入同卷隐藏目录，仍可能被其他有文件权限的应用访问。保留 30 天，不立即释放空间；卸载或清空白泽数据会丢失恢复记录，请先处理回收站。内容再变化会保留。" else "${batch.rejected.size} 个无法核对，已保留并取消勾选，原因见列表。",
                    status = "所选内容已核对，等待确认") }
            } catch (_: CancellationException) {
                if (control === task) dismissDeleteReview()
            }
        }
    }
    fun scanPrivate(note: String = "") {
        if (mutablePrivate.value.busy) return
        val source = remote
        if (source == null) {
            connect()
            mutablePrivate.update { it.copy(scanned = true, error = true, message = "Root 服务未连接，无法读取应用私有数据，请连接后重试") }
            return
        }
        val days = mutablePrivate.value.olderThanDays
        mutablePrivate.update { it.copy(scanning = true, error = false, message = "正在由 Root 统计微信 / QQ / TIM 的媒体文件夹…") }
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { ChatPrivateMedia.parse(RootServiceClients.profileExchange(source, context.cacheDir, "scanChatPrivate")) }
                    .getOrElse { ChatPrivateState(scanned = true, error = true, message = "应用私有数据读取失败：${it.javaClass.simpleName}") }
            }
            if (closed) return@launch
            val found = if (next.error) next.message else if (next.folders.isEmpty()) "未找到微信 / QQ / TIM 应用私有目录里的媒体文件"
                else "共 ${next.folders.size} 个媒体文件夹 · ${next.folders.sumOf { it.files }} 个文件 · " +
                    Formatter.formatFileSize(context, next.folders.sumOf { it.bytes }) + if (next.truncated) "（统计已达时间上限，结果不完整）" else ""
            mutablePrivate.value = next.copy(olderThanDays = days, message = listOf(note, found).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }
    fun togglePrivateFolder(path: String) { if (!mutablePrivate.value.busy) mutablePrivate.update { it.toggle(path) } }
    fun togglePrivateApp(app: String) { if (!mutablePrivate.value.busy) mutablePrivate.update { it.toggleApp(app) } }
    fun setPrivateAge(days: Int) { if (!mutablePrivate.value.busy && days in ChatPrivateMedia.AGE_OPTIONS) mutablePrivate.update { it.copy(olderThanDays = days) } }
    fun requestPrivateClean() { if (!mutablePrivate.value.busy && mutablePrivate.value.selectedFolders.isNotEmpty()) mutablePrivate.update { it.copy(confirmRequested = true) } }
    fun dismissPrivateClean() { mutablePrivate.update { it.copy(confirmRequested = false) } }
    fun stopPrivate() { privateCancelled = true }

    /** 用户确认后：可选先结束应用进程，再分轮由 Root 处理（每轮有时间预算），直到完成或停止。 */
    fun confirmPrivateClean(forceStop: Boolean, allowPermanent: Boolean) {
        val snapshot = mutablePrivate.value
        val source = remote
        if (snapshot.busy || !snapshot.confirmRequested || snapshot.selectedFolders.isEmpty()) return
        if (source == null) { mutablePrivate.update { it.copy(confirmRequested = false, error = true, message = "Root 服务未连接，文件未处理") }; return }
        privateCancelled = false
        mutablePrivate.update { it.copy(cleaning = true, confirmRequested = false, error = false, message = "正在处理所选文件夹…") }
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val totals = ChatPrivateMedia.CleanTotals()
                runCatching {
                    if (forceStop) snapshot.selectedFolders.map { it.packageName }.distinct().forEach { pkg ->
                        runCatching { RootServiceClients.profileExchange(source, context.cacheDir, "forceStopChatApp", org.json.JSONArray().put(pkg)) }
                    }
                    val request = ChatPrivateMedia.cleanRequest(snapshot, allowPermanent)
                    var lastDone = -1L
                    for (round in 0 until MAX_PRIVATE_ROUNDS) {
                        if (closed || privateCancelled) break
                        val more = ChatPrivateMedia.accumulate(totals,
                            RootServiceClients.profileExchange(source, context.cacheDir, "cleanChatPrivate", org.json.JSONArray().put(request)))
                        val done = totals.quarantined + totals.deleted
                        mutablePrivate.update { it.copy(message = "已处理 $done 个文件…") }
                        if (!more || done == lastDone) break
                        lastDone = done
                    }
                }.onFailure { totals.folderErrors += "Root 处理中断：${it.javaClass.simpleName}" }
                ChatPrivateMedia.summary(totals) { Formatter.formatFileSize(context, it) } + if (privateCancelled) "（已停止）" else ""
            }
            if (closed) return@launch
            mutablePrivate.update { it.copy(cleaning = false, selected = emptySet()) }
            scanPrivate(summary)
        }
    }

    fun dismissDeleteReview() {
        if (!state.value.reviewRequested) return
        control.cancel(); contentReview = emptyMap(); rootReview = emptySet()
        mutableState.update { it.copy(running = false, reviewRequested = false, reviewMessage = "",
            status = "已取消清理确认，文件未删除") }
    }

    fun deleteSelected() {
        val snapshot = state.value
        if (snapshot.running || snapshot.selected.isEmpty()) return
        if (!snapshot.reviewRequested || (contentReview.isEmpty() && rootReview.isEmpty()) ||
            contentReview.keys + rootReview != snapshot.selected) return
        val reviewed = contentReview
        val rootUris = rootReview
        contentReview = emptyMap(); rootReview = emptySet()
        val selectedRecords = snapshot.allRecords.filter { it.uri in snapshot.selected }
        if (selectedRecords.isEmpty()) return
        control = StorageScanControl()
        val taskControl = control
        mutableState.update { it.copy(running = true, reviewRequested = false, failed = false, status = "正在移入回收站…") }
        viewModelScope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                val results = linkedMapOf<String, StorageDeleteOutcome>()
                val keeperProofs = mutableMapOf<String, Pair<ApkFileIdentity, String>>()
                val rootRecords = selectedRecords.filter { it.uri in rootUris && ChatStorageRecords.isRootRecord(it) }
                if (rootRecords.isNotEmpty()) {
                    results += ChatStorageRecords.trash(remote, context.cacheDir, rootRecords, { taskControl.cancelled }) { done ->
                        mutableState.update { it.copy(progress = StorageScanProgress("正在由 Root 移入回收站", done, selectedRecords.size, "")) }
                    }
                }
                for ((index, record) in selectedRecords.withIndex()) {
                    if (record.uri in rootUris) continue
                    if (taskControl.cancelled) break
                    try {
                        val group = snapshot.duplicateGroups.firstOrNull { record in it.records }
                        val safe = snapshot.mode != StorageToolMode.DUPLICATES ||
                            (group != null && StorageMediaRepository.duplicateStillSafe(context, record, group, snapshot.selected, taskControl, keeperProofs))
                        taskControl.check()
                        results[record.uri] = if (safe) OrdinaryFileTrash.moveReviewed(context, record, reviewed[record.uri], ::protection, { taskControl.cancelled }) else StorageDeleteOutcome(ApkIndexedDeleteResult.CHANGED,
                            "重复组没有经内容核对的保留副本，请重新扫描")
                    } catch (_: CancellationException) { break
                    } catch (error: Exception) { results[record.uri] = StorageDeleteOutcome(ApkIndexedDeleteResult.FAILED,
                        "操作未完成：${error.javaClass.simpleName}") }
                    mutableState.update { it.copy(progress = StorageScanProgress("正在移入回收站", index + 1, selectedRecords.size, record.name)) }
                }
                selectedRecords.forEach { results.putIfAbsent(it.uri, StorageDeleteOutcome(ApkIndexedDeleteResult.CANCELLED)) }
                results
            }
            val removed = outcomes.filterValues { it.deleted || it.trashed }.keys
            val trashedIds = outcomes.values.filter { it.trashed && it.trashId.isNotBlank() }.map { it.trashId }
            val unconfirmed = outcomes.count { it.value.result == ApkIndexedDeleteResult.FAILED }
            val retained = selectedRecords.size - removed.size - unconfirmed
            val bytes = selectedRecords.filter { it.uri in removed }.sumOf { it.bytes }
            mutableState.update { current ->
                val remaining = current.records.filterNot { it.uri in removed }.map { record ->
                    if (outcomes[record.uri]?.result in setOf(ApkIndexedDeleteResult.CHANGED, ApkIndexedDeleteResult.UNVERIFIED))
                        record.copy(identity = null) else record
                }
                current.copy(running = false, progress = null, records = remaining, buckets = storageBuckets(remaining), directoryUsage = null,
                    selected = emptySet(), outcomes = current.outcomes + outcomes, lastTrashed = trashedIds,
                    lastTrashedBytes = selectedRecords.filter { outcomes[it.uri]?.trashed == true }.sumOf { it.bytes },
                    duplicateGroups = remainingDuplicateGroups(current.duplicateGroups, remaining),
                    status = "${if (taskControl.cancelled) "已停止 · " else ""}" +
                        (if (removed.isEmpty()) "未确认移入回收站" else "已移入回收站 ${removed.size} 个文件，占用 ${Formatter.formatFileSize(context, bytes)}，尚未释放空间") +
                        (if (retained > 0) " · $retained 项保留" else "") +
                        (if (unconfirmed > 0) " · $unconfirmed 项结果未确认" else ""),
                    coverage = if (removed.size < selectedRecords.size) "逐项结果已显示在文件下方；点文件可查看完整路径与读取诊断。结果未确认时请重新扫描核对。" else current.coverage)
            }
            if (removed.isNotEmpty()) recordHomeSummary(mutableState.value, partial = false)
        }
    }
    /**
     * 首页「上次扫描」摘要：只在本页扫描完成或处理后写入，容量口径与本页默认视图一致
     * （大文件 ≥100 MB、聊天媒体默认时间条件、重复文件只计多余副本），只计已核对身份的文件。
     */
    private fun recordHomeSummary(snapshot: StorageToolsUiState, partial: Boolean) {
        val (key, bytes, count) = when (snapshot.mode) {
            StorageToolMode.LARGE -> snapshot.records.filter { it.bytes >= 100 * MIB && it.verifiedBytes > 0 }
                .let { Triple(HomeScanSummaryStore.LARGE, it.sumOf { record -> record.verifiedBytes }, it.size.toLong()) }
            StorageToolMode.DUPLICATES -> Triple(HomeScanSummaryStore.DUPLICATES,
                snapshot.duplicateGroups.sumOf { it.reclaimableBytes },
                snapshot.duplicateGroups.sumOf { group -> (group.records.count { it.verifiedBytes > 0 } - 1).coerceAtLeast(0).toLong() })
            StorageToolMode.CHAT_MEDIA -> snapshot.records.filter {
                it.verifiedBytes > 0 && StorageReviewFilters.visible(StorageToolMode.CHAT_MEDIA, it, snapshot.nowSeconds,
                    StorageReviewFilters.defaultAgeDays(StorageToolMode.CHAT_MEDIA), emptyList(), null)
            }.let { Triple(HomeScanSummaryStore.CHAT, it.sumOf { record -> record.verifiedBytes }, it.size.toLong()) }
            else -> return
        }
        runCatching { HomeScanSummaryStore.record(context, key, bytes, count, partial) }
    }

    override fun onCleared() {
        closed = true; control.cancel()
        if (bound) runCatching { RootService.unbind(connection) }
        remote = null; bound = false
        super.onCleared()
    }
    companion object { const val MIB = 1024L * 1024L; const val MAX_PRIVATE_ROUNDS = 400 }
}
