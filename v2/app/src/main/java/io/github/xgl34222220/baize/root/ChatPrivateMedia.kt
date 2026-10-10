package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * 微信 / QQ / TIM 在应用私有目录（`/data/data/<包名>`）里的聊天媒体。
 *
 * 只认白名单里的媒体与缓存目录（图片、视频、语音、文件、朋友圈与表情缓存、cache）；
 * 文字聊天记录所在的数据库、索引、配置、`shared_prefs/` 与 `databases/` 永不处理。
 * 仅由用户在“聊天媒体”页手动触发，不在开机或启动 App 时扫描。
 */
internal object ChatPrivatePaths {
    enum class Kind(val label: String) {
        IMAGE("图片"), VIDEO("视频"), VOICE("语音"), FILE("文件"), SNS("朋友圈缓存"), EMOJI("表情缓存"), CACHE("缓存")
    }

    /** [relative] 相对于包数据目录；`{wx_account}` 只展开为 32 位十六进制账号目录，`{nt_account}` 只展开为 QQ NT 账号目录。 */
    data class Folder(val app: String, val packageName: String, val kind: Kind, val relative: String) {
        internal val pattern: Regex by lazy {
            Regex("^" + relative.split('/').joinToString("/") { segment ->
                when (segment) { WX_ACCOUNT -> WX_ACCOUNT_REGEX; NT_ACCOUNT -> NT_ACCOUNT_REGEX; else -> Regex.escape(segment) }
            } + "$")
        }
    }

    const val WECHAT = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"
    const val TIM = "com.tencent.tim"
    val PACKAGES = listOf(WECHAT, QQ, TIM)
    const val WX_ACCOUNT = "{wx_account}"
    const val NT_ACCOUNT = "{nt_account}"
    private const val WX_ACCOUNT_REGEX = "[0-9a-f]{32}"
    private const val NT_ACCOUNT_REGEX = "nt_qq(?:_[0-9A-Za-z]{4,64})?"
    const val MAX_ACCOUNTS = 8

    val FOLDERS: List<Folder> = buildList {
        val wx = "MicroMsg/$WX_ACCOUNT"
        add(Folder("微信", WECHAT, Kind.IMAGE, "$wx/image2"))
        add(Folder("微信", WECHAT, Kind.VIDEO, "$wx/video"))
        add(Folder("微信", WECHAT, Kind.VOICE, "$wx/voice2"))
        add(Folder("微信", WECHAT, Kind.FILE, "$wx/attachment"))
        add(Folder("微信", WECHAT, Kind.FILE, "$wx/Download"))
        add(Folder("微信", WECHAT, Kind.SNS, "$wx/sns"))
        add(Folder("微信", WECHAT, Kind.EMOJI, "$wx/emoji"))
        add(Folder("微信", WECHAT, Kind.FILE, "MicroMsg/Download"))
        add(Folder("微信", WECHAT, Kind.CACHE, "cache"))
        for ((app, pkg) in listOf("QQ" to QQ, "TIM" to TIM)) {
            val nt = "files/$NT_ACCOUNT/nt_data"
            add(Folder(app, pkg, Kind.IMAGE, "$nt/Pic"))
            add(Folder(app, pkg, Kind.VIDEO, "$nt/Video"))
            add(Folder(app, pkg, Kind.VOICE, "$nt/Ptt"))
            add(Folder(app, pkg, Kind.FILE, "$nt/File"))
            add(Folder(app, pkg, Kind.EMOJI, "$nt/Emoji"))
            add(Folder(app, pkg, Kind.CACHE, "cache"))
        }
    }

    private val protectedSegments = setOf("shared_prefs", "databases", "database", "db", "config", "configs", "index", "lib", "app_lib", "code_cache")
    private val databaseName = Regex("""(?i)(\.(db|sqlite3?|realm|ldb|xlog|cfg|conf|ini|key|lock|journal)(-wal|-shm|-journal)?|-(wal|shm|journal))$""")

    /** 数据库、索引、配置与账号凭据文件：始终保留（名字里含 index / config / EnMicroMsg 一律算）。 */
    fun isProtectedName(name: String): Boolean {
        val lower = name.lowercase()
        return name.isEmpty() || databaseName.containsMatchIn(name) || lower.startsWith("enmicromsg") ||
            lower.contains("index") || lower.contains("config") || lower == "account.bin" || lower.endsWith(".bin") ||
            lower.startsWith("mmkv") || lower.contains("crash")
    }

    /** 相对于包数据目录的路径是否落在受保护位置（任一目录段为 shared_prefs / databases / config / index 等）。 */
    fun isProtectedRelative(relative: String): Boolean {
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return true
        return segments.any { it.lowercase() in protectedSegments } || isProtectedName(segments.last())
    }

    /** 当前用户的包数据目录：`/data/user/<user>/<包名>`（用户 0 规范化后为 `/data/data/<包名>`）。 */
    fun packageDir(dataRoot: File, packageName: String): File = File(dataRoot, packageName)

    fun dataRoot(user: Int): File = File("/data/user/$user").let { runCatching { it.canonicalFile }.getOrDefault(it) }

    /**
     * 规范路径对应的白名单目录；不在白名单里（或含链接、越界）返回 null。
     * [path] 必须恰好是一个媒体目录本身，而不是它的子目录或上级。
     */
    fun folderFor(dataRoot: File, path: String): Folder? {
        val base = canonical(dataRoot).trimEnd('/') + "/"
        if (!path.startsWith(base)) return null
        val rest = path.removePrefix(base)
        val packageName = rest.substringBefore('/')
        if (packageName !in PACKAGES) return null
        val relative = rest.substringAfter('/', "")
        if (relative.isEmpty() || relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return FOLDERS.firstOrNull { it.packageName == packageName && it.pattern.matches(relative) }
    }

    /** 展开一个白名单目录：只返回真实存在、非链接的目录。 */
    fun resolve(dataRoot: File, folder: Folder): List<File> {
        var current = listOf(packageDir(dataRoot, folder.packageName))
        if (!current.single().isDirectory || isLink(current.single())) return emptyList()
        for (part in folder.relative.split('/')) {
            current = when (part) {
                WX_ACCOUNT, NT_ACCOUNT -> {
                    val regex = Regex(if (part == WX_ACCOUNT) WX_ACCOUNT_REGEX else NT_ACCOUNT_REGEX)
                    current.flatMap { parent ->
                        (runCatching { parent.listFiles() }.getOrNull() ?: emptyArray())
                            .filter { regex.matches(it.name) && it.isDirectory && !isLink(it) }
                            .sortedBy { it.name }.take(MAX_ACCOUNTS)
                    }
                }
                else -> current.mapNotNull { parent -> File(parent, part).takeIf { it.isDirectory && !isLink(it) } }
            }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
}

/** 时间档位：超过 N 天未修改。 */
internal object ChatAgeBuckets {
    val DAYS = listOf(7, 30, 90, 180)
    const val DAY_SECONDS = 86_400L

    /** 修改时间早于 [nowSeconds] − N 天的文件计入“N 天+”档；修改时间未知（≤0）不计入任何档。 */
    fun matches(modifiedSeconds: Long, nowSeconds: Long, days: Int): Boolean =
        days <= 0 || (modifiedSeconds > 0 && nowSeconds - modifiedSeconds >= days * DAY_SECONDS)
}

/**
 * Root 侧只读统计：按白名单目录汇总文件数、大小与时间档位。只给 App 返回目录级结果，
 * 不逐个回传文件；有时间与节点上限，可取消。
 */
internal class ChatPrivateScanner(
    private val dataRoot: File,
    private val cancelled: () -> Boolean = { false },
    private val budgetMs: Long = BUDGET_MS,
    private val maxNodes: Int = MAX_NODES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Bucket(var files: Long = 0L, var bytes: Long = 0L)
    data class FolderStats(
        val folder: ChatPrivatePaths.Folder,
        val path: String,
        val account: String,
        val device: Long,
        val inode: Long,
        val total: Bucket = Bucket(),
        val ages: Map<Int, Bucket> = ChatAgeBuckets.DAYS.associateWith { Bucket() },
        var protectedFiles: Long = 0L
    )
    data class Result(val folders: List<FolderStats>, val truncated: Boolean, val nowSeconds: Long, val elapsedMs: Long)

    fun scan(): Result {
        val started = clock()
        val now = started / 1000L
        var nodes = 0
        var truncated = false
        val out = ArrayList<FolderStats>()
        val seen = HashSet<String>()
        outer@ for (folder in ChatPrivatePaths.FOLDERS) {
            for (directory in ChatPrivatePaths.resolve(dataRoot, folder)) {
                val path = ChatPrivatePaths.canonical(directory)
                if (!seen.add(path) || ChatPrivatePaths.folderFor(dataRoot, path) != folder) continue
                val identity = ChatFileIdentity.read(directory) ?: continue
                val stats = FolderStats(folder, path, accountOf(path), identity.device, identity.inode)
                out += stats
                val stack = ArrayDeque<Pair<File, Int>>()
                stack.add(directory to 0)
                while (stack.isNotEmpty()) {
                    if (cancelled() || clock() - started >= budgetMs || nodes >= maxNodes) { truncated = true; break@outer }
                    val (current, depth) = stack.removeLast()
                    val children = runCatching { current.listFiles() }.getOrNull() ?: continue
                    for (child in children) {
                        nodes++
                        if (ChatPrivatePaths.isLink(child)) continue
                        if (child.isDirectory) {
                            if (child.name.lowercase() in PROTECTED_DIRS) continue
                            if (depth + 1 < MAX_DEPTH) stack.add(child to depth + 1) else truncated = true
                            continue
                        }
                        if (!child.isFile) continue
                        if (ChatPrivatePaths.isProtectedName(child.name)) { stats.protectedFiles++; continue }
                        val bytes = child.length().coerceAtLeast(0L)
                        val modified = child.lastModified() / 1000L
                        stats.total.files++; stats.total.bytes += bytes
                        for ((days, bucket) in stats.ages) if (ChatAgeBuckets.matches(modified, now, days)) { bucket.files++; bucket.bytes += bytes }
                    }
                }
            }
        }
        return Result(out.filter { it.total.files > 0 }, truncated, now, (clock() - started).coerceAtLeast(0L))
    }

    companion object {
        const val BUDGET_MS = 45_000L
        const val MAX_NODES = 2_000_000
        const val MAX_DEPTH = 8
        internal val PROTECTED_DIRS = setOf("shared_prefs", "databases", "database", "config", "configs", "index")

        fun accountOf(path: String): String =
            Regex("/([0-9a-f]{32})/").find(path)?.groupValues?.get(1)?.take(6)
                ?: Regex("/(nt_qq(?:_[0-9A-Za-z]+)?)/").find(path)?.groupValues?.get(1)?.takeLast(6).orEmpty()

        fun json(result: Result, capacity: QuarantineRepository.Capacity, sameFilesystem: Map<String, Boolean>): String {
            val folders = JSONArray()
            result.folders.sortedByDescending { it.total.bytes }.forEach { stats ->
                val ages = JSONObject()
                stats.ages.forEach { (days, bucket) -> ages.put(days.toString(), JSONObject().put("files", bucket.files).put("bytes", bucket.bytes)) }
                folders.put(JSONObject()
                    .put("path", stats.path)
                    .put("app", stats.folder.app)
                    .put("package", stats.folder.packageName)
                    .put("kind", stats.folder.kind.label)
                    .put("folder", stats.folder.relative.substringAfterLast('/'))
                    .put("account", stats.account)
                    .put("device", stats.device)
                    .put("inode", stats.inode)
                    .put("files", stats.total.files)
                    .put("bytes", stats.total.bytes)
                    .put("protectedFiles", stats.protectedFiles)
                    .put("recoverable", sameFilesystem[stats.folder.packageName] == true)
                    .put("ages", ages))
            }
            return JSONObject().put("success", true).put("folders", folders).put("truncated", result.truncated)
                .put("now", result.nowSeconds).put("elapsedMs", result.elapsedMs)
                .put("quarantineEntriesLeft", capacity.entriesLeft).put("quarantineBytesLeft", capacity.bytesLeft)
                .toString()
        }
    }
}

/**
 * 按目录与时间档位处理私有聊天媒体：能进隔离区（同分区原子移动、上限内）的先移入可恢复；
 * 超出上限或无法同分区移动的，只有用户明确确认“永久删除”后才删除，否则保留。
 *
 * 每个目录先核对白名单与目录身份（设备号 / inode 与扫描时一致），每个文件移动或删除前再核对：
 * 非链接、普通文件、不是数据库 / 索引 / 配置、修改时间仍早于截止时间。
 */
internal class ChatPrivateCleaner(
    private val dataRoot: File,
    private val repository: QuarantineRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = BUDGET_MS,
    private val identity: (File) -> ChatFileIdentity? = ChatFileIdentity::read
) {
    data class FolderRequest(val path: String, val device: Long, val inode: Long)
    data class Request(val folders: List<FolderRequest>, val scannedAt: Long, val olderThanDays: Int, val allowPermanent: Boolean)

    fun clean(request: Request, cancelled: () -> Boolean = { false }): String {
        val started = clock()
        val now = started / 1000L
        require(request.olderThanDays in ALLOWED_DAYS) { "invalid_age" }
        require(request.folders.size in 1..MAX_FOLDERS) { "invalid_folders" }
        require(request.scannedAt in (now - MAX_SCAN_AGE_SECONDS)..(now + 60)) { "scan_expired" }
        val cutoff = request.scannedAt - request.olderThanDays * ChatAgeBuckets.DAY_SECONDS
        var quarantined = 0L; var quarantinedBytes = 0L
        var deleted = 0L; var deletedBytes = 0L
        var kept = 0L; var keptBytes = 0L
        var truncated = false
        val reasons = LinkedHashMap<String, Int>()
        val folderErrors = JSONArray()
        fun keep(reason: String, bytes: Long) { kept++; keptBytes += bytes; reasons[reason] = (reasons[reason] ?: 0) + 1 }
        fun overBudget() = cancelled() || clock() - started >= budgetMs

        outer@ for (folderRequest in request.folders) {
            val directory = File(folderRequest.path)
            val folder = ChatPrivatePaths.folderFor(dataRoot, folderRequest.path)
            val current = identity(directory)
            val folderError = when {
                folder == null -> "不在微信 / QQ / TIM 的媒体目录白名单内"
                ChatPrivatePaths.canonical(directory) != folderRequest.path || ChatPrivatePaths.isLink(directory) -> "目录含链接或已变化"
                !directory.isDirectory || current == null -> "目录已不存在"
                current.device != folderRequest.device || current.inode != folderRequest.inode -> "目录在扫描后已变化，请重新扫描"
                else -> null
            }
            if (folderError != null) { folderErrors.put(JSONObject().put("path", folderRequest.path).put("reason", folderError)); continue }
            val sameFilesystem = repository.sameFilesystemAsPrivateRoot(directory)
            val batch = ArrayList<Pair<File, Long>>()
            fun flush() {
                if (batch.isEmpty()) return
                val capacity = repository.capacity()
                val fit = if (sameFilesystem) batch.take(capacity.fitting(batch.map { it.second })) else emptyList()
                if (fit.isNotEmpty()) {
                    val items = fit.map { QuarantineRepository.RecoverableItem(it.first.path, it.first.path) }
                    val results = repository.quarantineRecoverableBatch(items, CATEGORY, "聊天媒体（应用私有）", cancelled,
                        recheck = { item -> recheck(File(item.rootPath), cutoff) }, allowPrivateRoot = true)
                    results.forEachIndexed { index, result ->
                        val bytes = fit.getOrNull(index)?.second ?: 0L
                        if (result.optString("action") == "quarantined") { quarantined++; quarantinedBytes += result.optLong("bytes", bytes) }
                        else keep(result.optString("reason", "未处理"), bytes)
                    }
                }
                for ((file, bytes) in batch.drop(fit.size)) {
                    if (!request.allowPermanent) { keep(if (sameFilesystem) "回收站已满，未确认永久删除，已保留" else "无法同分区移入回收站，未确认永久删除，已保留", bytes); continue }
                    val reason = recheck(file, cutoff)
                    if (reason != null) { keep(reason, bytes); continue }
                    if (runCatching { file.delete() }.getOrDefault(false) && !file.exists()) { deleted++; deletedBytes += bytes }
                    else keep("删除失败，已保留", bytes)
                }
                batch.clear()
            }

            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(directory to 0)
            while (stack.isNotEmpty()) {
                if (overBudget()) { truncated = true; flush(); break@outer }
                val (dir, depth) = stack.removeLast()
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (child in children) {
                    if (ChatPrivatePaths.isLink(child)) continue
                    if (child.isDirectory) {
                        if (child.name.lowercase() !in ChatPrivateScanner.PROTECTED_DIRS && depth + 1 < ChatPrivateScanner.MAX_DEPTH) stack.add(child to depth + 1)
                        continue
                    }
                    if (!child.isFile || ChatPrivatePaths.isProtectedName(child.name)) continue
                    val modified = child.lastModified() / 1000L
                    if (modified <= 0L || modified > cutoff) continue
                    batch += child to child.length().coerceAtLeast(0L)
                    if (batch.size >= BATCH) flush()
                    if (overBudget()) { truncated = true; flush(); break@outer }
                }
            }
            flush()
        }
        val reasonJson = JSONArray()
        reasons.entries.sortedByDescending { it.value }.take(8).forEach { reasonJson.put(JSONObject().put("reason", it.key).put("count", it.value)) }
        return JSONObject().put("success", true)
            .put("quarantined", quarantined).put("quarantinedBytes", quarantinedBytes)
            .put("deleted", deleted).put("deletedBytes", deletedBytes)
            .put("kept", kept).put("keptBytes", keptBytes)
            .put("truncated", truncated).put("reasons", reasonJson).put("folderErrors", folderErrors)
            .toString()
    }

    /** 移动或删除前的最后核对；返回 null 表示可以处理。 */
    internal fun recheck(file: File, cutoffSeconds: Long): String? {
        val path = ChatPrivatePaths.canonical(file)
        if (path != file.path || ChatPrivatePaths.isLink(file)) return "路径含链接或已变化"
        val base = ChatPrivatePaths.canonical(dataRoot).trimEnd('/') + "/"
        if (!path.startsWith(base)) return "不在当前用户的应用数据内"
        val relative = path.removePrefix(base).substringAfter('/', "")
        if (ChatPrivatePaths.isProtectedRelative(relative)) return "数据库、索引或配置文件，始终保留"
        val inFolder = ChatPrivatePaths.FOLDERS.any { folder ->
            path.removePrefix(base).substringBefore('/') == folder.packageName &&
                generateSequence(File(relative)) { it.parentFile }.drop(1).any { folder.pattern.matches(it.path) }
        }
        if (!inFolder) return "不在媒体目录白名单内"
        if (!file.isFile) return "目标已不存在或不是普通文件"
        val modified = file.lastModified() / 1000L
        if (modified <= 0L || modified > cutoffSeconds) return "文件在扫描后有更新，已保留"
        return null
    }

    companion object {
        const val CATEGORY = "chat_private_media"
        val ALLOWED_DAYS = setOf(0) + ChatAgeBuckets.DAYS
        const val MAX_FOLDERS = 64
        const val BATCH = 200
        const val BUDGET_MS = 25_000L
        const val MAX_SCAN_AGE_SECONDS = 6 * 3_600L

        fun parse(raw: JSONObject): Request {
            val folders = raw.getJSONArray("folders")
            return Request((0 until folders.length()).map { index ->
                val item = folders.getJSONObject(index)
                FolderRequest(item.getString("path"), item.getLong("device"), item.getLong("inode"))
            }, raw.getLong("scannedAt"), raw.getInt("olderThanDays"), raw.optBoolean("allowPermanent", false))
        }
    }
}

/** 结束聊天软件进程（`am force-stop`），只接受三个固定包名。 */
internal object ChatAppStopper {
    fun command(user: Int, packageName: String): List<String> {
        require(packageName in ChatPrivatePaths.PACKAGES) { "invalid_package" }
        require(user in 0..9_999) { "invalid_user" }
        return listOf("/system/bin/am", "force-stop", "--user", user.toString(), packageName)
    }

    fun stop(user: Int, packageName: String): String {
        val process = ProcessBuilder(command(user, packageName)).redirectErrorStream(true).start()
        val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val code = if (finished) process.exitValue() else -1
        return JSONObject().put("success", code == 0).put("package", packageName).put("exitCode", code).toString()
    }
}
