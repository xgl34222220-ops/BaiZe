package io.github.xgl34222220.baize

/**
 * 根目录整理（参考 Rikka 存储空间隔离的“保持根目录整洁”目标与清浊的文件夹防护）：
 * 只看共享存储第一层，按已知规则库与已安装应用判断归属。没有挂载命名空间重定向，不改动应用行为。
 */
internal enum class RootEntryKind(val label: String) {
    STANDARD("系统标准目录"), PROTECTED("受保护"), PLACEHOLDER("已禁止重建"), WHITELISTED("白名单"),
    EMPTY("空文件夹"), ORPHAN("应用已卸载"), OWNED("应用在用"), UNKNOWN("来源未知"),
    /** 白泽自己的内部目录（回收站、隔离区）：不是整理候选，也不归入「受保护」，单独以内部目录展示。 */
    INTERNAL("白泽内部目录")
}

/** 扫描到的一个根目录条目；[files]/[bytes] 在达到上限时为已统计部分，[limited] 标记不完整。 */
internal data class RootEntry(
    val name: String, val directory: Boolean, val empty: Boolean, val files: Int = 0, val bytes: Long = 0L,
    val modifiedSeconds: Long = 0L, val limited: Boolean = false, val link: Boolean = false
)

internal data class RootOwnerRule(val label: String, val packages: List<String>, val sensitive: Boolean = false)

/** 列表行模型：只读字段，供 Compose 跳过未变化的行。 */
@androidx.compose.runtime.Immutable
internal data class RootEntryReview(
    val entry: RootEntry, val kind: RootEntryKind, val ownerLabel: String?, val ownerPackages: List<String>,
    val installedOwners: List<String>, val sensitive: Boolean
) {
    /** 可在预览后手动移除：空文件夹、已卸载应用残留、来源未知；目录统计不完整或是链接时不允许。 */
    val removable: Boolean get() = !entry.link && !entry.limited &&
        kind in setOf(RootEntryKind.EMPTY, RootEntryKind.ORPHAN, RootEntryKind.UNKNOWN)
    /** 只有非标准、非白名单、未被保护的文件夹可以设置“禁止重建”。 */
    val canBlock: Boolean get() = entry.directory && !entry.link && !entry.limited &&
        kind in setOf(RootEntryKind.EMPTY, RootEntryKind.ORPHAN, RootEntryKind.UNKNOWN, RootEntryKind.OWNED)
}

/** 应用侧与模块共享的规则文件内容：自动开关、白名单、禁止重建名单。 */
internal data class RootTidyRules(val auto: Boolean = false, val allow: Set<String> = emptySet(), val block: Set<String> = emptySet())

internal object RootDirectoryOrganizer {
    const val MAX_ENTRIES = 512
    const val MAX_FILES_PER_FOLDER = 5_000
    const val MAX_RULE_NAMES = 200

    /** Android 公共目录，始终受保护，不能删除、不能禁止重建、不能加入自动整理。 */
    val STANDARD = setOf("DCIM", "Pictures", "Download", "Documents", "Music", "Movies", "Android", "Alarms",
        "Notifications", "Ringtones", "Podcasts", "Audiobooks", "Recordings")
    private val standardLower = STANDARD.map { it.lowercase() }.toSet()

    /** 微信 / QQ 等用户依赖的数据目录：禁止重建需要额外确认，自动整理永不处理非空目录。 */
    private val sensitivePackages = setOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework")

    /** 已知根目录规则库（小写名称 → 归属）；只做归属判断，不据此自动删除非空目录。 */
    val KNOWN: Map<String, RootOwnerRule> = buildMap {
        fun rule(label: String, vararg packages: String, sensitive: Boolean = false, names: List<String>) =
            names.forEach { put(it.lowercase(), RootOwnerRule(label, packages.toList(), sensitive)) }
        rule("微信 / QQ", "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", sensitive = true, names = listOf("tencent"))
        rule("QQ 浏览器", "com.tencent.mtt", names = listOf("QQBrowser"))
        rule("腾讯 X5 内核", "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.mtt", names = listOf(".tbs", "tbs"))
        rule("百度系应用", "com.baidu.searchbox", "com.baidu.BaiduMap", "com.baidu.netdisk", "com.baidu.input", "com.baidu.tieba", names = listOf("baidu", "BaiduMapSDKNew", "BaiduNetdisk"))
        rule("新浪微博", "com.sina.weibo", names = listOf("sina", "weibo"))
        rule("UC 浏览器", "com.UCMobile", names = listOf("UCDownloads", "UCMobile"))
        rule("支付宝", "com.eg.android.AlipayGphone", names = listOf("alipay"))
        rule("淘宝", "com.taobao.taobao", names = listOf("taobao"))
        rule("阿里系 SDK", "com.eg.android.AlipayGphone", "com.taobao.taobao", "com.tmall.wireless", "com.taobao.idlefish", names = listOf(".UTSystemConfig", "Alibaba"))
        rule("网易云音乐", "com.netease.cloudmusic", names = listOf("netease", "Netease"))
        rule("高德地图", "com.autonavi.minimap", names = listOf("amap", "autonavi"))
        rule("酷狗音乐", "com.kugou.android", names = listOf("kugou", "kgmusic"))
        rule("酷我音乐", "cn.kuwo.player", names = listOf("kuwo", "KuwoMusic"))
        rule("讯飞输入法", "com.iflytek.inputmethod", names = listOf("iflytek"))
        rule("搜狗输入法", "com.sohu.inputmethod.sogou", names = listOf("sogou"))
        rule("抖音", "com.ss.android.ugc.aweme", names = listOf("bytedance", "aweme"))
        rule("哔哩哔哩", "tv.danmaku.bili", names = listOf("bilibili"))
        rule("京东", "com.jingdong.app.mall", names = listOf("jingdong", "jd"))
        rule("美团", "com.sankuai.meituan", names = listOf("meituan"))
        rule("喜马拉雅", "com.ximalaya.ting.android", names = listOf("ximalaya"))
        rule("WPS Office", "cn.wps.moffice_eng", "cn.wps.moffice", names = listOf("wps", "WPS"))
        rule("Telegram", "org.telegram.messenger", "org.telegram.messenger.web", names = listOf("Telegram"))
        rule("WhatsApp", "com.whatsapp", names = listOf("WhatsApp"))
    }

    /** 白泽自身在存储根目录可能用到的内部目录 → 展示名。它们不参与整理，也不算「受保护」。 */
    val INTERNAL_DIRS: Map<String, String> = linkedMapOf(
        OrdinaryFileTrash.DIRECTORY.lowercase() to "白泽回收站（内部）",
        ".baize-quarantine" to "白泽隔离区（内部）")

    fun internalLabel(name: String): String? = INTERNAL_DIRS[name.lowercase()]
    fun isInternal(name: String): Boolean = internalLabel(name) != null

    /** 名称合法：单层、非空、无控制字符、不是 . 或 ..，长度有上限。 */
    fun validName(name: String): Boolean = name.isNotEmpty() && name.length <= 128 && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && name.none { it.code < 32 || it.code == 127 }

    fun standard(name: String): Boolean = name.lowercase() in standardLower

    /** 规则库、包名样式目录、应用包名末段（≥4 个字符，避免误判）三种方式确定归属。 */
    fun owner(name: String, installed: Set<String>): RootOwnerRule? {
        KNOWN[name.lowercase()]?.let { return it }
        val bare = name.trimStart('.')
        if (bare.isNotEmpty() && bare != name) KNOWN[bare.lowercase()]?.let { return it }
        if (Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(bare)) return RootOwnerRule(bare, listOf(bare))
        if (bare.length >= 4) {
            val matches = installed.filter { it.substringAfterLast('.').equals(bare, true) }
            if (matches.isNotEmpty()) return RootOwnerRule(matches.first(), matches.sorted())
        }
        return null
    }

    fun classify(entry: RootEntry, installed: Set<String>, rules: RootTidyRules): RootEntryReview {
        val owner = if (validName(entry.name)) owner(entry.name, installed) else null
        val packages = owner?.packages.orEmpty()
        val live = packages.filter { it in installed }
        val sensitive = owner?.sensitive == true || packages.any { it in sensitivePackages }
        fun review(kind: RootEntryKind) = RootEntryReview(entry, kind, owner?.label, packages, live, sensitive)
        return when {
            isInternal(entry.name) && !entry.link -> RootEntryReview(entry, RootEntryKind.INTERNAL, internalLabel(entry.name), emptyList(), emptyList(), false)
            !validName(entry.name) || entry.link || OrdinaryFileTrash.isPayloadPath("/" + entry.name) -> review(RootEntryKind.PROTECTED)
            standard(entry.name) -> review(RootEntryKind.STANDARD)
            !entry.directory && entry.bytes == 0L && rules.block.any { it.equals(entry.name, true) } -> review(RootEntryKind.PLACEHOLDER)
            rules.allow.any { it.equals(entry.name, true) } -> review(RootEntryKind.WHITELISTED)
            entry.directory && entry.empty -> review(RootEntryKind.EMPTY)
            owner != null && live.isNotEmpty() -> review(RootEntryKind.OWNED)
            owner != null -> review(RootEntryKind.ORPHAN)
            else -> review(RootEntryKind.UNKNOWN)
        }
    }

    /** 敏感目录（微信 / QQ 等）需要用户额外勾选“我了解风险”才允许禁止重建。 */
    fun blockAllowed(review: RootEntryReview, acknowledgedSensitive: Boolean): String? = when {
        !review.canBlock -> "${review.kind.label}不能设置禁止重建"
        review.sensitive && !acknowledgedSensitive -> "这是微信 / QQ 等常用应用的数据目录，需要确认后才能禁止重建"
        else -> null
    }

    fun encodeRules(rules: RootTidyRules): String = buildString {
        append("# 白泽根目录整理规则（由 App 写入）\n")
        append("auto=").append(if (rules.auto) 1 else 0).append('\n')
        rules.allow.filter(::ruleName).sortedBy { it.lowercase() }.take(MAX_RULE_NAMES).forEach { append("allow|").append(it).append('\n') }
        rules.block.filter(::ruleName).sortedBy { it.lowercase() }.take(MAX_RULE_NAMES).forEach { append("block|").append(it).append('\n') }
    }

    fun decodeRules(raw: String?): RootTidyRules {
        var auto = false
        val allow = LinkedHashSet<String>(); val block = LinkedHashSet<String>()
        raw.orEmpty().lineSequence().map { it.trimEnd('\r') }.forEach { line ->
            when {
                line == "auto=1" -> auto = true
                line.startsWith("allow|") -> line.removePrefix("allow|").takeIf(::ruleName)?.let { if (allow.size < MAX_RULE_NAMES) allow += it }
                line.startsWith("block|") -> line.removePrefix("block|").takeIf(::ruleName)?.let { if (block.size < MAX_RULE_NAMES) block += it }
            }
        }
        return RootTidyRules(auto, allow, block)
    }

    /** 规则名不能是标准目录、回收站或含分隔符；`|` 会破坏行格式。 */
    fun ruleName(name: String): Boolean = validName(name) && '|' !in name && !standard(name) && !isInternal(name)

    /** 移除过的根目录文件夹：名称 → 累计移除次数与最近一次移除时间（秒）。行格式 `name|times|epoch`。 */
    fun encodeRemoved(removed: Map<String, RemovedFolder>): String = removed.values
        .filter { ruleName(it.name) && it.times > 0 }.sortedByDescending { it.lastRemovedSeconds }.take(MAX_RULE_NAMES)
        .joinToString("\n") { "${it.name}|${it.times}|${it.lastRemovedSeconds}" }

    fun decodeRemoved(raw: String?): Map<String, RemovedFolder> {
        val result = LinkedHashMap<String, RemovedFolder>()
        raw.orEmpty().lineSequence().map { it.trimEnd('\r') }.forEach { line ->
            val parts = line.split('|')
            if (parts.size != 3 || !ruleName(parts[0])) return@forEach
            val times = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            val at = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@forEach
            if (result.size < MAX_RULE_NAMES) result[parts[0].lowercase()] = RemovedFolder(parts[0], times, at)
        }
        return result
    }

    /** 记录本次确实已从根目录消失的名称；键为小写名称。 */
    fun recordRemoved(removed: Map<String, RemovedFolder>, names: Collection<String>, nowSeconds: Long): Map<String, RemovedFolder> {
        val next = LinkedHashMap(removed)
        names.filter(::ruleName).forEach { name ->
            val key = name.lowercase(); val old = next[key]
            next[key] = RemovedFolder(name, (old?.times ?: 0) + 1, nowSeconds)
        }
        return next
    }

    /** 撤销本次移除：对应名称的次数减一，减到 0 时删除记录。 */
    fun forgetRemoved(removed: Map<String, RemovedFolder>, names: Collection<String>): Map<String, RemovedFolder> {
        val next = LinkedHashMap(removed)
        names.forEach { name ->
            val key = name.lowercase(); val old = next[key] ?: return@forEach
            if (old.times <= 1) next.remove(key) else next[key] = old.copy(times = old.times - 1)
        }
        return next
    }

    /** 本次选中的名称里，之前已移除过（又被重新创建）的个数。 */
    fun previouslyRemoved(removed: Map<String, RemovedFolder>, names: Collection<String>): List<String> =
        names.filter { removed.containsKey(it.lowercase()) }

    fun removalMessage(count: Int, repeated: Int): String = "已移除 $count 项，文件在回收站保留 30 天" +
        if (repeated > 0) "。其中 $repeated 个之前删过又被应用重建了，可以设为禁止重建" else ""

    /** 已移除过、现在又出现在根目录、且仍可整理（未加入白名单 / 未禁止重建）的条目。 */
    fun recreated(reviews: List<RootEntryReview>, removed: Map<String, RemovedFolder>): List<RecreatedFolder> = reviews.mapNotNull { review ->
        val record = removed[review.entry.name.lowercase()] ?: return@mapNotNull null
        if (review.kind !in RECREATABLE_KINDS) return@mapNotNull null
        // 修改时间早于记录的移除时间：文件夹其实没有被删掉（移除未生效），而不是被应用重建。
        val stale = review.entry.modifiedSeconds in 1 until record.lastRemovedSeconds
        val owner = review.ownerLabel?.let { label ->
            val pkg = review.installedOwners.firstOrNull()
            if (pkg != null && pkg != label) "$label（$pkg）" else label
        }
        RecreatedFolder(review, record.times + 1, owner, stale)
    }

    private val RECREATABLE_KINDS = setOf(RootEntryKind.EMPTY, RootEntryKind.ORPHAN, RootEntryKind.UNKNOWN, RootEntryKind.OWNED)
}

internal data class RemovedFolder(val name: String, val times: Int, val lastRemovedSeconds: Long)

/** 「被重新创建」分组的一行：[appearance] 为第几次出现（首次出现 + 每次被移除后再出现）。 */
@androidx.compose.runtime.Immutable
internal data class RecreatedFolder(val review: RootEntryReview, val appearance: Int, val owner: String?, val removalIneffective: Boolean) {
    val summary: String get() = if (removalIneffective) "第 $appearance 次出现 · 上次移除未生效（文件夹修改时间早于移除时间）"
        else "第 $appearance 次出现 · " + (owner?.let { "可能由 $it 创建" } ?: "无法识别是哪个应用创建的")
}

/** 一次移除可撤销：回收站记录、被删除的空目录、被删除的零字节文件。 */
internal data class RootTidyUndo(val trashIds: List<String> = emptyList(), val directories: List<String> = emptyList(),
    val emptyFiles: List<String> = emptyList(), val names: List<String> = emptyList(), val repeated: Int = 0) {
    val empty: Boolean get() = trashIds.isEmpty() && directories.isEmpty() && emptyFiles.isEmpty()
    /** 恢复动作数（文件 + 目录 + 空文件），只用于内部；面向用户的数字用 [entries]。 */
    val count: Int get() = trashIds.size + directories.size + emptyFiles.size
    /** 本次移除的根目录条目数（与列表中勾选的项数一致，不把子目录算进去）。 */
    val entries: Int get() = if (names.isNotEmpty()) names.size else count
}

internal data class RootTidyUiState(
    val running: Boolean = false, val permissionRequired: Boolean = false, val status: String = "准备扫描根目录",
    val reviews: List<RootEntryReview> = emptyList(), val selected: Set<String> = emptySet(),
    val rules: RootTidyRules = RootTidyRules(), val undo: RootTidyUndo = RootTidyUndo(),
    val history: List<String> = emptyList(), val moduleSummary: String = "", val rootConnected: Boolean = false,
    val removedFolders: Map<String, RemovedFolder> = emptyMap()
) {
    /** 白泽内部目录（只展示，不参与整理）。 */
    val internalDirs: List<RootEntryReview> get() = reviews.filter { it.kind == RootEntryKind.INTERNAL }
    val recreated: List<RecreatedFolder> get() = RootDirectoryOrganizer.recreated(reviews, removedFolders)
    val selectedBytes: Long get() = reviews.filter { it.entry.name in selected }.sumOf { it.entry.bytes }
    val removable: List<RootEntryReview> get() = reviews.filter { it.removable }
    /** 「被重新创建」分组里的名称；它们不再在原分类里重复出现。 */
    val recreatedNames: Set<String> get() = recreated.map { it.review.entry.name }.toSet()
    /** 全选只选空文件夹与已卸载应用的残留；来源未知的需要逐项勾选。 */
    val recommended: Set<String> get() = reviews.filter { it.removable && it.kind != RootEntryKind.UNKNOWN }.map { it.entry.name }.toSet()
    fun toggle(name: String): RootTidyUiState = when {
        running -> this
        name in selected -> copy(selected = selected - name)
        reviews.any { it.entry.name == name && it.removable } -> copy(selected = selected + name)
        else -> this
    }
    fun toggleAll(): RootTidyUiState = if (running) this
        else if (recommended.isNotEmpty() && selected.containsAll(recommended)) copy(selected = selected - recommended) else copy(selected = selected + recommended)
}

internal val ROOT_KIND_ORDER = listOf(RootEntryKind.ORPHAN, RootEntryKind.EMPTY, RootEntryKind.UNKNOWN, RootEntryKind.OWNED,
    RootEntryKind.PLACEHOLDER, RootEntryKind.WHITELISTED, RootEntryKind.STANDARD, RootEntryKind.PROTECTED)
