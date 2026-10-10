package io.github.xgl34222220.baize

/**
 * 根目录整理（参考 Rikka 存储空间隔离的“保持根目录整洁”目标与清浊的文件夹防护）：
 * 只看共享存储第一层，按已知规则库与已安装应用判断归属。没有挂载命名空间重定向，不改动应用行为。
 */
internal enum class RootEntryKind(val label: String) {
    STANDARD("系统标准目录"), PROTECTED("受保护"), PLACEHOLDER("已禁止重建"), WHITELISTED("白名单"),
    EMPTY("空文件夹"), ORPHAN("应用已卸载"), OWNED("应用在用"), UNKNOWN("来源未知")
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
            !validName(entry.name) || entry.link || OrdinaryFileTrash.isPayloadPath("/" + entry.name) ||
                entry.name.equals(OrdinaryFileTrash.DIRECTORY, true) -> review(RootEntryKind.PROTECTED)
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
    fun ruleName(name: String): Boolean = validName(name) && '|' !in name && !standard(name) &&
        !name.equals(OrdinaryFileTrash.DIRECTORY, true)
}

/** 一次移除可撤销：回收站记录、被删除的空目录、被删除的零字节文件。 */
internal data class RootTidyUndo(val trashIds: List<String> = emptyList(), val directories: List<String> = emptyList(),
    val emptyFiles: List<String> = emptyList()) {
    val empty: Boolean get() = trashIds.isEmpty() && directories.isEmpty() && emptyFiles.isEmpty()
    val count: Int get() = trashIds.size + directories.size + emptyFiles.size
}

internal data class RootTidyUiState(
    val running: Boolean = false, val permissionRequired: Boolean = false, val status: String = "准备扫描根目录",
    val reviews: List<RootEntryReview> = emptyList(), val selected: Set<String> = emptySet(),
    val rules: RootTidyRules = RootTidyRules(), val undo: RootTidyUndo = RootTidyUndo(),
    val history: List<String> = emptyList(), val moduleSummary: String = "", val rootConnected: Boolean = false
) {
    val selectedBytes: Long get() = reviews.filter { it.entry.name in selected }.sumOf { it.entry.bytes }
    val removable: List<RootEntryReview> get() = reviews.filter { it.removable }
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
