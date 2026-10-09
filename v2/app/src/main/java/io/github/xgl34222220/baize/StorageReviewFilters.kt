package io.github.xgl34222220.baize

import org.json.JSONArray
import org.json.JSONObject

/** 截图录屏 / 录屏的来源判断；只看路径与类型，不读取内容。 */
internal enum class ScreenCaptureKind(val label: String) { SCREENSHOT("截图"), RECORDING("录屏") }

/**
 * 用户自定义筛选规则（参考 SD Maid SE SystemCleaner 自定义过滤器、CCleaner 自定义清理项）：
 * 路径通配 + 最短存放天数 + 最小大小。规则只负责“找出候选”，删除仍走内容核对与回收站。
 */
internal data class StorageCustomFilter(
    val id: String, val name: String, val pattern: String, val minAgeDays: Int = 0, val minBytes: Long = 0L
) {
    private val regex: Regex? by lazy { StorageReviewFilters.globRegex(pattern) }
    /** 只比较路径：扫描阶段用它缩小候选集，时间与大小在展示阶段判断。 */
    fun matchesPath(path: String): Boolean {
        val relative = StorageReviewFilters.relativeStoragePath(path) ?: return false
        return !StorageReviewFilters.forbidden(path) && regex?.matches(relative) == true
    }
    fun matches(record: StorageFileRecord, nowSeconds: Long): Boolean = matchesPath(record.path) &&
        record.bytes >= minBytes && StorageReviewFilters.olderThan(record.modifiedSeconds, nowSeconds, minAgeDays)
    val summary: String get() = buildList {
        add(pattern)
        if (minAgeDays > 0) add("超过 $minAgeDays 天")
        if (minBytes > 0) add("≥ ${minBytes / (1024 * 1024)} MB")
    }.joinToString(" · ")
}

internal data class StorageCustomFilterInput(val filter: StorageCustomFilter?, val error: String = "")

/** 截图录屏、旧下载、聊天媒体与自定义规则的纯筛选逻辑；不依赖 Android，可直接单元测试。 */
internal object StorageReviewFilters {
    const val DAY_SECONDS = 86_400L
    const val MAX_CUSTOM_FILTERS = 20
    /** 0 表示不限时间。 */
    val AGE_CHOICES = listOf(0, 7, 30, 90, 180, 365)

    fun defaultAgeDays(mode: StorageToolMode): Int = when (mode) {
        StorageToolMode.SCREENSHOTS -> 30
        StorageToolMode.OLD_DOWNLOADS, StorageToolMode.CHAT_MEDIA -> 90
        else -> 0
    }

    fun ageLabel(days: Int): String = if (days <= 0) "全部时间" else "超过 $days 天"

    private val volumePrefix = Regex("^(?:/storage/(?:emulated/[0-9]+|self/primary|[A-Za-z0-9-]+)|/sdcard|/mnt/media_rw/[A-Za-z0-9-]+)/(.+)$")
    private val databaseName = Regex("""(?i)(\.(db|sqlite3?|realm|ldb)(-wal|-shm|-journal)?|-(wal|shm|journal))$""")
    /** 微信 / QQ 仅允许用户导出或接收后另存的公共目录，其余 tencent 目录（含账号数据）一律不进入结果。 */
    private val tencentExports = listOf("/tencent/micromsg/weixin/", "/tencent/qq_images/", "/tencent/qqfile_recv/", "/tencent/qq_video/")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif")
    private val videoExt = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp")

    /** 卷根之后的相对路径，例如 `Download/a.zip`；不在共享存储内时返回 null。 */
    fun relativeStoragePath(path: String): String? = volumePrefix.matchEntire(path)?.groupValues?.get(1)

    /**
     * 这些路径永远不进入新增工具的结果：聊天软件数据库与账号目录、应用私有/OBB、任何数据库文件、
     * 隐藏目录与白泽回收站。删除前的保护规则与内容核对仍会再次执行。
     */
    fun forbidden(path: String): Boolean {
        if (path.isBlank() || path.contains('\u0000') || OrdinaryFileTrash.isPayloadPath(path)) return true
        val relative = relativeStoragePath(path) ?: return true
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return true
        if (segments.dropLast(1).any { it.startsWith('.') }) return true
        val lower = "/" + relative.lowercase()
        if (lower.startsWith("/android/")) return true
        if ("/tencent/" in lower && tencentExports.none { it in lower }) return true
        if ("com.tencent." in lower) return true
        return databaseName.containsMatchIn(segments.last())
    }

    fun olderThan(modifiedSeconds: Long, nowSeconds: Long, days: Int): Boolean =
        days <= 0 || nowSeconds <= 0 || (modifiedSeconds > 0 && nowSeconds - modifiedSeconds >= days * DAY_SECONDS)

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun screenCaptureKind(path: String, name: String, mime: String): ScreenCaptureKind? {
        val lower = path.lowercase()
        val file = name.lowercase()
        val image = mime.lowercase().startsWith("image/") || ext(file) in imageExt
        val video = mime.lowercase().startsWith("video/") || ext(file) in videoExt
        val shotDirectory = Regex("/(screenshots?|截屏|截图|屏幕截图)/").containsMatchIn(lower)
        val recordDirectory = Regex("/(screenrecorder|screen ?recordings?|screenrecords?|screen_records?|录屏|屏幕录制)/").containsMatchIn(lower)
        val shotName = file.startsWith("screenshot") || file.startsWith("截屏") || file.startsWith("截图")
        val recordName = file.startsWith("screenrecord") || file.startsWith("screen_record") || file.startsWith("screen-recording") ||
            file.startsWith("record_screen") || file.startsWith("录屏") || file.startsWith("屏幕录制")
        return when {
            image && (shotDirectory || shotName) -> ScreenCaptureKind.SCREENSHOT
            video && (recordDirectory || recordName || shotDirectory) -> ScreenCaptureKind.RECORDING
            else -> null
        }
    }

    /** 共享存储根目录下的 Download（含子目录）；Android/data 内的下载目录不算。 */
    fun inDownloads(path: String): Boolean = relativeStoragePath(path)?.let {
        it.startsWith("Download/", ignoreCase = true) || it.startsWith("Downloads/", ignoreCase = true)
    } ?: false

    /** 聊天软件“已保存/已接收”的公共目录；数据库与账号目录已由 [forbidden] 排除。 */
    fun chatMediaSource(path: String): String? {
        val lower = "/" + (relativeStoragePath(path) ?: return null).lowercase()
        return when {
            Regex("^/(pictures|dcim|movies|download|documents)/(weixin|wechat)/").containsMatchIn(lower) ||
                lower.startsWith("/tencent/micromsg/weixin/") -> "微信"
            Regex("^/(pictures|movies|download|documents)/(qq|qqfile_recv)/").containsMatchIn(lower) ||
                lower.startsWith("/tencent/qq_images/") || lower.startsWith("/tencent/qqfile_recv/") ||
                lower.startsWith("/tencent/qq_video/") -> "QQ"
            Regex("^/(pictures|movies|download)/telegram/").containsMatchIn(lower) || lower.startsWith("/telegram/") -> "Telegram"
            lower.startsWith("/whatsapp/media/") -> "WhatsApp"
            else -> null
        }
    }

    /** 扫描阶段的候选集：先按来源缩小，再做文件身份核对，避免对 12 万项全部核对。 */
    fun candidate(mode: StorageToolMode, record: StorageFileRecord, filters: List<StorageCustomFilter>): Boolean = when (mode) {
        StorageToolMode.SCREENSHOTS -> !forbidden(record.path) && screenCaptureKind(record.path, record.name, record.mime) != null
        StorageToolMode.OLD_DOWNLOADS -> !forbidden(record.path) && inDownloads(record.path)
        StorageToolMode.CHAT_MEDIA -> !forbidden(record.path) && chatMediaSource(record.path) != null &&
            storageCategory(record) in setOf("image", "video", "audio", "document", "archive")
        StorageToolMode.CUSTOM -> filters.any { it.matchesPath(record.path) }
        else -> true
    }

    /** 展示阶段：在候选集上叠加时间条件；自定义模式使用所选规则自己的时间与大小条件。 */
    fun visible(mode: StorageToolMode, record: StorageFileRecord, nowSeconds: Long, ageDays: Int,
                filters: List<StorageCustomFilter>, activeFilterId: String?): Boolean = when (mode) {
        StorageToolMode.CUSTOM -> {
            val active = filters.firstOrNull { it.id == activeFilterId }
            if (active != null) active.matches(record, nowSeconds) else filters.any { it.matches(record, nowSeconds) }
        }
        StorageToolMode.SCREENSHOTS, StorageToolMode.OLD_DOWNLOADS, StorageToolMode.CHAT_MEDIA ->
            candidate(mode, record, filters) && olderThan(record.modifiedSeconds, nowSeconds, ageDays)
        else -> true
    }

    fun sourceLabel(mode: StorageToolMode, record: StorageFileRecord): String? = when (mode) {
        StorageToolMode.SCREENSHOTS -> screenCaptureKind(record.path, record.name, record.mime)?.label
        StorageToolMode.CHAT_MEDIA -> chatMediaSource(record.path)
        else -> null
    }

    /**
     * 通配语法：相对于存储卷根目录，`*` 匹配一层内任意字符，`**` 跨目录，`?` 匹配一个字符，不区分大小写。
     * 第一层必须是具体目录名，防止一条规则覆盖整个存储。
     */
    fun globRegex(pattern: String): Regex? {
        val normalized = normalizePattern(pattern) ?: return null
        val out = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            val char = normalized[index]
            when {
                normalized.startsWith("**/", index) -> { out.append("(?:.*/)?"); index += 3; continue }
                normalized.startsWith("**", index) -> { out.append(".*"); index += 2; continue }
                char == '*' -> out.append("[^/]*")
                char == '?' -> out.append("[^/]")
                else -> out.append(Regex.escape(char.toString()))
            }
            index++
        }
        return Regex(out.toString(), RegexOption.IGNORE_CASE)
    }

    /** 去掉卷前缀与首尾斜杠；不合法时返回 null。 */
    fun normalizePattern(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("/+"), "/")
        val relative = relativeStoragePath(trimmed) ?: trimmed.trimStart('/')
        return relative.trimEnd('/').takeIf { it.isNotEmpty() }
    }

    fun validate(id: String, name: String, pattern: String, ageDays: Int, minMegabytes: Long): StorageCustomFilterInput {
        if ((name + pattern).any { it.code < 32 || it.code == 127 }) return StorageCustomFilterInput(null, "不能包含换行或控制字符")
        val normalized = normalizePattern(pattern) ?: return StorageCustomFilterInput(null, "请输入路径规则，例如 Download/**/*.zip")
        if (normalized.length > 200) return StorageCustomFilterInput(null, "路径规则过长")
        if ('\\' in normalized || Regex("[\\[\\]{}]").containsMatchIn(normalized)) return StorageCustomFilterInput(null, "只支持 * ** ? 三种通配符")
        val segments = normalized.split('/')
        if (segments.any { it == "." || it == ".." }) return StorageCustomFilterInput(null, "不能使用 . 或 ..")
        val first = segments.first()
        if (first.any { it == '*' || it == '?' }) return StorageCustomFilterInput(null, "第一层需填写具体目录，例如 Download 或 Pictures")
        if (first.startsWith('.') || first.equals("Android", true)) return StorageCustomFilterInput(null, "Android 与隐藏目录受保护，不能设为规则范围")
        if (segments.dropLast(1).any { it.startsWith('.') }) return StorageCustomFilterInput(null, "隐藏目录受保护，不能设为规则范围")
        if (ageDays !in 0..3650) return StorageCustomFilterInput(null, "天数需在 0–3650 之间")
        if (minMegabytes !in 0..1_048_576) return StorageCustomFilterInput(null, "大小需在 0–1048576 MB 之间")
        val label = name.trim().take(24).ifBlank { normalized.take(24) }
        return StorageCustomFilterInput(StorageCustomFilter(id, label, normalized, ageDays, minMegabytes * 1024 * 1024))
    }

    fun encode(filters: List<StorageCustomFilter>): String = JSONArray().apply {
        filters.take(MAX_CUSTOM_FILTERS).forEach { put(JSONObject().put("id", it.id).put("name", it.name)
            .put("pattern", it.pattern).put("age", it.minAgeDays).put("bytes", it.minBytes)) }
    }.toString()

    /** 坏记录逐条丢弃；保存的规则同样重新校验，旧版本写入的越界规则不会生效。 */
    fun decode(raw: String?): List<StorageCustomFilter> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9-]{1,40}")) } ?: return@mapNotNull null
            val bytes = item.optLong("bytes", 0L).coerceAtLeast(0L)
            validate(id, item.optString("name"), item.optString("pattern"), item.optInt("age", 0), bytes / (1024 * 1024)).filter
        }.distinctBy { it.id }.take(MAX_CUSTOM_FILTERS)
    }.getOrDefault(emptyList())
}

/**
 * 一键扫描结果里的“需要你复核”分类：安全项目在上方默认勾选，这里的内容默认不勾选，
 * 点开后在对应视图逐项确认，删除仍走内容核对与回收站。原来的独立工具入口都收拢到这里。
 */
internal enum class ReviewSource(val title: String, val hint: String, val mode: StorageToolMode?) {
    CORPSES("卸载残留", "已卸载应用留下的数据", null),
    APK("安装包", "下载后未清理的安装包", null),
    SCREENSHOTS("旧截图与录屏", "30 天前的截图与录屏", StorageToolMode.SCREENSHOTS),
    OLD_DOWNLOADS("旧下载", "90 天未动的下载文件", StorageToolMode.OLD_DOWNLOADS),
    CHAT_MEDIA("聊天媒体", "微信 / QQ 已保存的图片视频，不含聊天记录", StorageToolMode.CHAT_MEDIA),
    DUPLICATES("重复文件", "完整内容比对，每组保留一份", StorageToolMode.DUPLICATES),
    ROOT("根目录整理", "空文件夹与已卸载应用的文件夹", StorageToolMode.ROOT)
}

internal data class ReviewEstimate(val files: Int, val bytes: Long)

internal object ReviewSourceEstimates {
    /** 只按路径与时间估算，不读取内容、不核对文件身份；实际可处理量以打开后的核对为准。 */
    fun estimate(records: List<StorageFileRecord>, nowSeconds: Long): Map<ReviewSource, ReviewEstimate> {
        val result = LinkedHashMap<ReviewSource, ReviewEstimate>()
        for (source in listOf(ReviewSource.SCREENSHOTS, ReviewSource.OLD_DOWNLOADS, ReviewSource.CHAT_MEDIA)) {
            val mode = source.mode ?: continue
            val age = StorageReviewFilters.defaultAgeDays(mode)
            val matched = records.filter { StorageReviewFilters.visible(mode, it, nowSeconds, age, emptyList(), null) }
            result[source] = ReviewEstimate(matched.size, matched.sumOf { it.bytes.coerceAtLeast(0) })
        }
        val apks = records.filter { storageCategory(it) == "apk" && !StorageReviewFilters.forbidden(it.path) }
        result[ReviewSource.APK] = ReviewEstimate(apks.size, apks.sumOf { it.bytes.coerceAtLeast(0) })
        return result
    }

}
