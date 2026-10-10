package io.github.xgl34222220.baize

/**
 * 性能工具（实验）：配置模型与决策规则。全部开关默认关闭。
 *
 * 运行时由模块脚本 perf-tools.sh 执行；这里与脚本保持同一套规则（白名单匹配、后台时长、
 * 内存阈值 / 冷却、数据库黑名单与跳过原因），供界面校验与单元测试使用。
 */
enum class Dex2oatMode(val value: String, val label: String) {
    SPEED_PROFILE("speed-profile", "speed-profile（推荐）"),
    SPEED("speed", "speed"),
    EVERYTHING("everything", "everything");

    companion object {
        fun of(value: String?): Dex2oatMode = entries.firstOrNull { it.value == value } ?: SPEED_PROFILE
    }
}

data class PerfToolsConfig(
    val dex2oatAuto: Boolean = false,
    val dex2oatMode: Dex2oatMode = Dex2oatMode.SPEED_PROFILE,
    val dex2oatAllApps: Boolean = false,
    val dex2oatForce: Boolean = false,
    val dbOptimizeAuto: Boolean = false,
    val dbForceStop: Boolean = false,
    val freezeEnabled: Boolean = false,
    val freezeAfterMinutes: Int = DEFAULT_FREEZE_MINUTES,
    val memEnabled: Boolean = false,
    val memThresholdMb: Int = DEFAULT_MEM_THRESHOLD_MB,
    val memCooldownSeconds: Int = DEFAULT_MEM_COOLDOWN_SECONDS,
    val memKill: Boolean = false,
    val pollSeconds: Int = DEFAULT_POLL_SECONDS
) {
    /** 模块读取的 key=value 文本；数值一律先夹到允许范围。 */
    fun encode(): String = buildString {
        appendLine("# 白泽 性能工具（实验）配置：由 App 写入，模块 perf-tools.sh 读取")
        appendLine("dex2oat_auto=${dex2oatAuto.flag()}")
        appendLine("dex2oat_mode=${dex2oatMode.value}")
        appendLine("dex2oat_scope=${if (dex2oatAllApps) "all" else "user"}")
        appendLine("dex2oat_force=${dex2oatForce.flag()}")
        appendLine("dbopt_auto=${dbOptimizeAuto.flag()}")
        appendLine("dbopt_force_stop=${dbForceStop.flag()}")
        appendLine("freeze_enabled=${freezeEnabled.flag()}")
        appendLine("freeze_after_minutes=${freezeAfterMinutes.coerceIn(FREEZE_MINUTES)}")
        appendLine("mem_enabled=${memEnabled.flag()}")
        appendLine("mem_threshold_mb=${memThresholdMb.coerceIn(MEM_THRESHOLD_MB)}")
        appendLine("mem_cooldown_seconds=${memCooldownSeconds.coerceIn(MEM_COOLDOWN_SECONDS)}")
        appendLine("mem_kill=${memKill.flag()}")
        appendLine("poll_seconds=${pollSeconds.coerceIn(POLL_SECONDS)}")
    }

    val anyEnabled: Boolean get() = dex2oatAuto || dbOptimizeAuto || freezeEnabled || memEnabled

    companion object {
        const val DEFAULT_FREEZE_MINUTES = 15
        const val DEFAULT_MEM_THRESHOLD_MB = 1024
        const val DEFAULT_MEM_COOLDOWN_SECONDS = 300
        const val DEFAULT_POLL_SECONDS = 60
        val FREEZE_MINUTES = 1..240
        val MEM_THRESHOLD_MB = 100..16384
        val MEM_COOLDOWN_SECONDS = 30..3600
        /** 轮询下限 30 秒：保证常驻循环几乎不占 CPU。 */
        val POLL_SECONDS = 30..600

        fun parse(text: String?): PerfToolsConfig {
            val values = text.orEmpty().lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            fun flag(key: String) = values[key] == "1"
            fun int(key: String, default: Int, range: IntRange) =
                (values[key]?.toIntOrNull() ?: default).coerceIn(range)
            return PerfToolsConfig(
                dex2oatAuto = flag("dex2oat_auto"),
                dex2oatMode = Dex2oatMode.of(values["dex2oat_mode"]),
                dex2oatAllApps = values["dex2oat_scope"] == "all",
                dex2oatForce = flag("dex2oat_force"),
                dbOptimizeAuto = flag("dbopt_auto"),
                dbForceStop = flag("dbopt_force_stop"),
                freezeEnabled = flag("freeze_enabled"),
                freezeAfterMinutes = int("freeze_after_minutes", DEFAULT_FREEZE_MINUTES, FREEZE_MINUTES),
                memEnabled = flag("mem_enabled"),
                memThresholdMb = int("mem_threshold_mb", DEFAULT_MEM_THRESHOLD_MB, MEM_THRESHOLD_MB),
                memCooldownSeconds = int("mem_cooldown_seconds", DEFAULT_MEM_COOLDOWN_SECONDS, MEM_COOLDOWN_SECONDS),
                memKill = flag("mem_kill"),
                pollSeconds = int("poll_seconds", DEFAULT_POLL_SECONDS, POLL_SECONDS)
            )
        }

        private fun Boolean.flag() = if (this) "1" else "0"
    }
}

enum class MemoryPressure { NONE, LOW, CRITICAL }

object PerfToolsPolicy {
    const val APP_ID = "io.github.xgl34222220.baize"

    /** 进程压制 / 内存压制的内置白名单；输入法、桌面、默认短信与拨号由模块运行时从系统读取后追加。 */
    val DEFAULT_WHITELIST: List<String> = listOf(
        APP_ID,
        "com.tencent.mm", "com.tencent.mm:push",
        "com.tencent.mobileqq", "com.tencent.mobileqq:MSF",
        "com.tencent.tim",
        "com.topjohnwu.magisk", "me.weishu.kernelsu", "me.bmax.apatch"
    )
    val DYNAMIC_WHITELIST_LABELS = listOf("全部已启用输入法", "当前桌面", "默认短信应用", "默认拨号应用")

    /** 数据库优化的内置黑名单（系统应用本来就不在处理范围内）。 */
    val DEFAULT_DB_BLACKLIST: List<String> = listOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", APP_ID)

    const val MAX_LIST_ENTRIES = 500
    private val NAME = Regex("[A-Za-z0-9._:]{1,200}")

    /** 用户编辑的名单：去注释、去空白、只保留合法包名 / 进程名，去重，最多 500 条。 */
    fun normalizeList(raw: String?): List<String> = raw.orEmpty().lineSequence()
        .map { it.substringBefore('#').filterNot(Char::isWhitespace) }
        .filter { it.matches(NAME) && !it.startsWith('.') && !it.startsWith(':') }
        .distinct()
        .take(MAX_LIST_ENTRIES)
        .toList()

    /** 不含冒号的条目保护整个应用；含冒号的条目只保护该进程（如 com.tencent.mm:push）。 */
    fun isWhitelisted(processName: String, entries: Collection<String>): Boolean =
        processName in entries || processName.substringBefore(':') in entries

    /** 后台时长是否已到；时钟回拨时不冻结。 */
    fun freezeDue(backgroundSinceEpoch: Long, nowEpoch: Long, afterMinutes: Int): Boolean =
        nowEpoch >= backgroundSinceEpoch && nowEpoch - backgroundSinceEpoch >= afterMinutes * 60L

    fun memoryPressure(availableKb: Long, thresholdMb: Int, lastEpoch: Long, nowEpoch: Long, cooldownSeconds: Int): MemoryPressure {
        if (availableKb < 0 || availableKb >= thresholdMb * 1024L) return MemoryPressure.NONE
        if (lastEpoch > 0 && nowEpoch >= lastEpoch && nowEpoch - lastEpoch < cooldownSeconds) return MemoryPressure.NONE
        return if (availableKb < thresholdMb * 512L) MemoryPressure.CRITICAL else MemoryPressure.LOW
    }

    fun dbBlacklisted(packageName: String, userBlacklist: Collection<String>): Boolean =
        packageName in DEFAULT_DB_BLACKLIST || packageName in userBlacklist

    private val ENCRYPTED_NAME = Regex("^EnMicroMsg.*|.*[Ee]ncrypt.*|.*[Cc]ipher.*|.*[Ss]ecure.*|.*\\.enc(\\.db)?$|.*sqlcipher.*")

    /** 与模块 perf_db_skip_reason 一致；返回 null 表示可以优化。 */
    fun dbSkipReason(
        packageName: String,
        fileName: String,
        userPackages: Collection<String>,
        userBlacklist: Collection<String>,
        sizeBytes: Long,
        walBytes: Long,
        journalExists: Boolean,
        header: String
    ): String? = when {
        dbBlacklisted(packageName, userBlacklist) -> "blacklist"
        packageName !in userPackages -> "system-app"
        ENCRYPTED_NAME.matches(fileName) -> "encrypted-name"
        walBytes > 0 -> "wal-in-use"
        journalExists -> "hot-journal"
        sizeBytes < 4096 -> "too-small"
        sizeBytes > 256L * 1024 * 1024 -> "too-large"
        header != "SQLite format 3" -> "encrypted-or-not-sqlite"
        else -> null
    }

    fun dex2oatStateLabel(state: String): String = when (state) {
        "running" -> "正在编译"
        "completed" -> "已完成"
        "cancelled" -> "已停止"
        "interrupted" -> "因亮屏或拔电中断"
        else -> "尚未运行"
    }
}
