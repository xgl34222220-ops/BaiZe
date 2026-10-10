package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.ApkNames
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * QQ / TIM / 微信在共享存储里的真实落盘位置。
 *
 * Android 11+ 起 QQ、微信把收到的文件和聊天媒体放在 `Android/data/<包名>/` 下：
 * MediaStore 不索引这里，App 进程的 java.io.File 在 Android/data 下也只能读到空目录，
 * 只有 Root 进程（RootService 以 uid 0 运行）能看到。这里集中描述这些目录，
 * 由 Root 侧扫描器和清理校验共用，避免各处各写一份路径。
 *
 * `{wx_account}` 与模块的 app-profile 规则同语义：只展开为 MicroMsg 下真实存在的
 * 32 位小写十六进制账号目录；image2 / voice2 下的两位十六进制分桶由深度上限覆盖，
 * 不再做通配展开。
 */
internal object ChatAppPaths {
    enum class Area(val label: String, val receivedFiles: Boolean, val userSaved: Boolean, val mediaHint: String?) {
        RECEIVED("接收文件", true, false, null),
        SAVED("已保存", false, true, null),
        CHAT_IMAGE("聊天图片", false, false, "image"),
        CHAT_VIDEO("聊天视频", false, false, "video"),
        CHAT_VOICE("语音", false, false, "audio")
    }

    data class Location(val app: String, val area: Area, val relative: String) {
        val accountScoped: Boolean get() = WX_ACCOUNT in relative
        internal val pattern: Regex by lazy {
            val body = relative.split('/').joinToString("/") { segment ->
                if (segment == WX_ACCOUNT) "[0-9a-f]{32}" else Regex.escape(segment)
            }
            Regex("^$body/.+$", RegexOption.IGNORE_CASE)
        }
    }

    const val WX_ACCOUNT = "{wx_account}"
    private const val QQ = "com.tencent.mobileqq"
    private const val TIM = "com.tencent.tim"
    private const val WX = "com.tencent.mm"
    private val ACCOUNT = Regex("^[0-9a-f]{32}$")
    const val MAX_ACCOUNTS = 8

    val LOCATIONS: List<Location> = listOf(
        // QQ：新版在 Android/data，旧版在共享存储根下的 Tencent。
        Location("QQ", Area.RECEIVED, "Android/data/$QQ/Tencent/QQfile_recv"),
        Location("QQ", Area.RECEIVED, "Android/data/$QQ/files/QQfile_recv"),
        Location("QQ", Area.RECEIVED, "Android/data/$QQ/Tencent/QQfile_recv_new"),
        Location("QQ", Area.SAVED, "Android/data/$QQ/Tencent/QQ_Images"),
        Location("QQ", Area.CHAT_IMAGE, "Android/data/$QQ/Tencent/MobileQQ/chatpic"),
        Location("QQ", Area.CHAT_VIDEO, "Android/data/$QQ/Tencent/MobileQQ/shortvideo"),
        Location("QQ", Area.RECEIVED, "Tencent/QQfile_recv"),
        Location("QQ", Area.SAVED, "Tencent/QQ_Images"),
        Location("QQ", Area.RECEIVED, "Download/QQ"),
        // TIM
        Location("TIM", Area.RECEIVED, "Android/data/$TIM/Tencent/TIMfile_recv"),
        Location("TIM", Area.RECEIVED, "Android/data/$TIM/files/TIMfile_recv"),
        Location("TIM", Area.RECEIVED, "Tencent/TIMfile_recv"),
        // 微信：收到的文件 / 导出目录 / 账号目录下的聊天媒体。
        Location("微信", Area.RECEIVED, "Android/data/$WX/MicroMsg/Download"),
        Location("微信", Area.RECEIVED, "Download/WeiXin"),
        Location("微信", Area.RECEIVED, "Download/WeChat"),
        Location("微信", Area.RECEIVED, "tencent/MicroMsg/Download"),
        Location("微信", Area.SAVED, "Pictures/WeiXin"),
        Location("微信", Area.SAVED, "tencent/MicroMsg/WeiXin"),
        Location("微信", Area.RECEIVED, "Android/data/$WX/MicroMsg/$WX_ACCOUNT/attachment"),
        Location("微信", Area.CHAT_IMAGE, "Android/data/$WX/MicroMsg/$WX_ACCOUNT/image2"),
        Location("微信", Area.CHAT_VIDEO, "Android/data/$WX/MicroMsg/$WX_ACCOUNT/video"),
        Location("微信", Area.CHAT_VOICE, "Android/data/$WX/MicroMsg/$WX_ACCOUNT/voice2")
    )

    private val volumePrefix = Regex("^(?:/data/media/[0-9]+|/storage/emulated/[0-9]+|/storage/self/primary|/sdcard|/mnt/media_rw/[A-Za-z0-9-]+|/storage/[A-Za-z0-9-]+)/(.+)$")

    fun relativeStoragePath(path: String): String? = volumePrefix.matchEntire(path)?.groupValues?.get(1)

    /** 路径所属的聊天软件目录；`..`、`.` 与隐藏目录一律不认。 */
    fun locationFor(path: String): Location? {
        val relative = relativeStoragePath(path) ?: return null
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments.dropLast(1).any { it.startsWith('.') }) return null
        return LOCATIONS.firstOrNull { it.pattern.matches(relative) }
    }

    /** 聊天软件“接收文件”目录里的安装包（含 `.apk.1` 改名副本）。清理校验只认这里。 */
    fun isReceivedApk(path: String): Boolean =
        ApkNames.isApk(path) && locationFor(path)?.area?.receivedFiles == true

    /** 在一个用户存储根下展开位置；只返回真实存在、非链接的目录。 */
    fun resolve(userRoot: File, location: Location): List<File> {
        val parts = location.relative.split('/')
        var current = listOf(userRoot)
        for (part in parts) {
            current = if (part == WX_ACCOUNT) current.flatMap { parent ->
                (runCatching { parent.listFiles() }.getOrNull() ?: emptyArray())
                    .filter { ACCOUNT.matches(it.name) && it.isDirectory && !isLink(it) }
                    .sortedBy { it.name }
                    .take(MAX_ACCOUNTS)
            } else current.mapNotNull { parent ->
                File(parent, part).takeIf { it.isDirectory && !isLink(it) }
                    ?: caseInsensitiveChild(parent, part)
            }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    /** 旧版目录名大小写不一（tencent / Tencent）；只接受唯一的大小写变体。 */
    private fun caseInsensitiveChild(parent: File, name: String): File? =
        (runCatching { parent.listFiles() }.getOrNull() ?: return null)
            .filter { it.name.equals(name, ignoreCase = true) && it.isDirectory && !isLink(it) }
            .singleOrNull()

    internal fun isLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
}

/**
 * Root 侧只读扫描：列出 QQ / TIM / 微信目录里的文件，供“聊天媒体”“安装包”“存储分析”展示。
 *
 * 只读，不删除；保持文件数、深度与时间上限，不在开机时运行（只由前台请求触发）。
 */
internal class ChatStorageScanner(
    private val userRoots: List<File>,
    private val cancelled: () -> Boolean = { false },
    private val maxFiles: Int = MAX_FILES,
    private val maxDepth: Int = MAX_DEPTH,
    private val budgetMs: Long = BUDGET_MS,
    private val apksOnly: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val publicPath: (String) -> String = ApkRootPathMapper::publicPath
) {
    data class Entry(
        val path: String,
        val publicPath: String,
        val name: String,
        val bytes: Long,
        val modifiedSeconds: Long,
        val app: String,
        val area: ChatAppPaths.Area,
        val kind: String
    )

    data class Result(
        val entries: List<Entry>,
        val truncated: Boolean,
        val unreadableDirectories: Int,
        val resolvedDirectories: List<String>,
        val elapsedMs: Long
    )

    fun scan(): Result {
        val started = clock()
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        val resolved = ArrayList<String>()
        var unreadable = 0
        var truncated = false
        fun overBudget() = cancelled() || clock() - started >= budgetMs

        outer@ for (userRoot in userRoots) {
            if (!userRoot.isDirectory) continue
            for (location in ChatAppPaths.LOCATIONS) {
                if (apksOnly && !location.area.receivedFiles) continue
                for (directory in ChatAppPaths.resolve(userRoot, location)) {
                    val directoryKey = canonical(directory)
                    if (!seen.add("dir:$directoryKey")) continue
                    resolved += directory.path
                    val stack = ArrayDeque<Pair<File, Int>>()
                    stack.add(directory to 0)
                    while (stack.isNotEmpty()) {
                        if (overBudget()) { truncated = true; break@outer }
                        val (current, depth) = stack.removeLast()
                        val children = runCatching { current.listFiles() }.getOrNull()
                        if (children == null) { unreadable++; continue }
                        for (child in children) {
                            val name = child.name
                            if (name.startsWith('.') || ChatAppPaths.isLink(child)) continue
                            if (child.isDirectory) {
                                if (depth + 1 < maxDepth) stack.add(child to depth + 1) else truncated = true
                                continue
                            }
                            if (!child.isFile || skippedName(name)) continue
                            val bytes = child.length()
                            if (bytes <= 0L) continue
                            val kind = kind(name, location.area)
                            if (apksOnly && kind != "apk") continue
                            val key = canonical(child)
                            if (!seen.add(key)) continue
                            if (entries.size >= maxFiles) { truncated = true; break@outer }
                            entries += Entry(child.path, publicPath(child.path), name, bytes,
                                (child.lastModified() / 1000L).coerceAtLeast(0L), location.app, location.area, kind)
                        }
                    }
                }
            }
        }
        return Result(entries, truncated, unreadable, resolved, (clock() - started).coerceAtLeast(0L))
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    companion object {
        const val MAX_FILES = 20_000
        const val MAX_DEPTH = 6
        const val BUDGET_MS = 20_000L
        val AGE_BUCKETS = listOf(0, 7, 30, 90, 180, 365)
        private const val DAY_SECONDS = 86_400L

        private val databaseName = Regex("""(?i)(\.(db|sqlite3?|realm|ldb|cfg|bin|lock|tmp|temp|part)(-wal|-shm|-journal)?|-(wal|shm|journal))$""")

        fun skippedName(name: String): Boolean = name.equals(".nomedia", true) ||
            name.startsWith("WxFileIndex", true) || databaseName.containsMatchIn(name)

        fun kind(name: String, area: ChatAppPaths.Area): String {
            if (ApkNames.isApk(name)) return "apk"
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp" -> "image"
                "mp4", "mkv", "mov", "webm", "m4v", "3gp", "avi" -> "video"
                "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "amr", "silk", "slk" -> "audio"
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "archive"
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf", "epub" -> "document"
                // 微信 image2 / video / voice2 的文件常无扩展名，由所在目录决定类型。
                else -> area.mediaHint ?: "other"
            }
        }

        /** 每个时间档位的文件数：0 = 全部时间，其余为“超过 N 天”。 */
        fun ageBuckets(entries: List<Entry>, nowSeconds: Long): Map<Int, Int> = AGE_BUCKETS.associateWith { days ->
            entries.count { days <= 0 || (it.modifiedSeconds > 0 && nowSeconds - it.modifiedSeconds >= days * DAY_SECONDS) }
        }

        fun json(result: Result, nowSeconds: Long): String {
            val items = JSONArray()
            result.entries.forEach { entry ->
                items.put(JSONObject()
                    .put("path", entry.publicPath)
                    .put("rootPath", entry.path)
                    .put("name", entry.name)
                    .put("bytes", entry.bytes)
                    .put("modified", entry.modifiedSeconds)
                    .put("app", entry.app)
                    .put("area", entry.area.label)
                    .put("userSaved", entry.area.userSaved)
                    .put("kind", entry.kind))
            }
            val ages = JSONObject()
            ageBuckets(result.entries, nowSeconds).forEach { (days, count) -> ages.put(days.toString(), count) }
            return JSONObject()
                .put("success", true)
                .put("items", items)
                .put("files", result.entries.size)
                .put("bytes", result.entries.sumOf { it.bytes })
                .put("apks", result.entries.count { it.kind == "apk" })
                .put("ageBuckets", ages)
                .put("truncated", result.truncated)
                .put("unreadableDirectories", result.unreadableDirectories)
                .put("directories", JSONArray(result.resolvedDirectories.map(ApkRootPathMapper::publicPath)))
                .put("elapsedMs", result.elapsedMs)
                .toString()
        }
    }
}
