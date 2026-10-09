package io.github.xgl34222220.baize

import androidx.compose.runtime.Immutable

/** 一类微信目录的只读占用（来自模块 `app-profile-rules.sh wechat-usage`）。 */
@Immutable
data class WechatUsageEntry(val key: String, val label: String, val bytes: Long, val tier: String)

/** 微信存储构成：只读统计结果，不包含任何删除能力。 */
@Immutable
data class WechatUsage(
    val entries: List<WechatUsageEntry> = emptyList(),
    val totalBytes: Long = 0L,
    val accounts: Int = 0,
    val error: String? = null
) {
    val installed: Boolean get() = error == null && totalBytes > 0L

    companion object {
        private val tiers = setOf("conservative", "standard", "enhanced", "media", "protected", "other")

        fun failed(message: String): WechatUsage = WechatUsage(error = message)

        /** 解析“键|名称|字节|档位”行；坏行被忽略，`total` 与 `accounts` 单独读取。 */
        fun parse(output: String): WechatUsage {
            val entries = ArrayList<WechatUsageEntry>()
            var total = 0L
            var accounts = 0
            for (raw in output.lineSequence()) {
                val fields = raw.trim().split('|')
                when {
                    fields.size == 2 && fields[0] == "accounts" -> accounts = fields[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
                    fields.size == 4 && fields[0] == "total" -> total = fields[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    fields.size == 4 && fields[3] in tiers && fields[0].matches(Regex("[a-z_]{1,32}")) -> {
                        val bytes = fields[2].toLongOrNull() ?: continue
                        entries += WechatUsageEntry(fields[0], fields[1].take(40), bytes.coerceAtLeast(0L), fields[3])
                    }
                }
            }
            if (entries.isEmpty() && total == 0L) return failed("未找到微信数据目录")
            return WechatUsage(entries.filter { it.bytes > 0L }.sortedByDescending { it.bytes }, total, accounts)
        }
    }
}

/** 每类目录在哪个档位开始被白泽清理。 */
fun wechatUsageTierHint(tier: String): String = when (tier) {
    "conservative" -> "保守档起清理"
    "standard" -> "标准档起清理"
    "enhanced" -> "增强档清理"
    "media" -> "需增强档并开启聊天媒体"
    else -> "不清理"
}

fun formatUsageBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", value / (1024.0 * 1024 * 1024))
        value >= 1024.0 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
        value >= 1024.0 -> String.format(java.util.Locale.ROOT, "%.0f KB", value / 1024.0)
        else -> "${bytes.coerceAtLeast(0L)} B"
    }
}
