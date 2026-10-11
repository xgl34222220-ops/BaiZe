package io.github.xgl34222220.baize

/**
 * 「命中保护规则」的统一说明：大文件 / 重复文件 / 聊天媒体 / 存储分析的结果行和详情共用。
 *
 * 普通文件进入回收站前只会被三类规则拦下：
 *  - 你自己的保护名单（路径保护、应用保护，含旧版迁移记录）——可「临时允许本次」，也可到保护名单移除；
 *  - 白泽回收站内部目录——系统级保护，无法解除；
 *  - 保护名单尚未核对（Root 未连接 / 读取失败）——不是规则命中，重连后重新核对即可。
 */
internal enum class ProtectionRuleKind { USER_PATH, USER_APP, INTERNAL_TRASH, UNVERIFIED }

internal data class ProtectionRuleLine(val kind: ProtectionRuleKind, val pattern: String, val description: String)

internal data class ProtectionRuleExplanation(val lines: List<ProtectionRuleLine>, val unavailableReason: String? = null) {
    /** 只有用户自己的规则可以本次放行；回收站内部和未核对状态永远不放行。 */
    val overridable: Boolean get() = lines.isNotEmpty() && lines.all { it.kind == ProtectionRuleKind.USER_PATH || it.kind == ProtectionRuleKind.USER_APP }
    val removable: Boolean get() = overridable
    val systemLocked: Boolean get() = lines.any { it.kind == ProtectionRuleKind.INTERNAL_TRASH }

    /** 结果行一句话：命中保护规则：<规则名/路径模式>。 */
    val rowReason: String get() = when {
        unavailableReason != null -> "已保留 · 保护名单尚未核对，重连 Root 后重新扫描"
        lines.isEmpty() -> "已保留 · 保护原因待重新核对"
        else -> "已保留 · 命中保护规则：" + lines.first().short() + if (lines.size > 1) "（共 ${lines.size} 条）" else ""
    }

    val lockedText: String? get() = if (systemLocked) SYSTEM_LOCK_PREFIX + "这是白泽回收站内部目录，里面是已删除文件的恢复副本。" +
        "请到「回收站」恢复或永久删除；任何保护名单设置都不会解除它。" else null

    companion object {
        const val SYSTEM_LOCK_PREFIX = "系统级保护，无法解除："

        fun of(details: ApkProtectionDetails?): ProtectionRuleExplanation {
            if (details == null) return ProtectionRuleExplanation(emptyList())
            if (details.internalTrash) return ProtectionRuleExplanation(listOf(ProtectionRuleLine(ProtectionRuleKind.INTERNAL_TRASH,
                OrdinaryFileTrash.DIRECTORY, "白泽回收站内部目录（系统级保护）")))
            details.unavailableReason?.let { return ProtectionRuleExplanation(emptyList(), it) }
            val lines = details.paths.map { match ->
                ProtectionRuleLine(ProtectionRuleKind.USER_PATH, match.rule,
                    if (match.ancestor) "你的路径保护（父目录，包含此文件）" else "你的路径保护（此文件）")
            } + details.packages.sorted().map { pkg ->
                ProtectionRuleLine(ProtectionRuleKind.USER_APP, pkg, "你的应用保护（文件位于该应用的 Android/data 目录）")
            }
            return ProtectionRuleExplanation(lines)
        }

        private fun ProtectionRuleLine.short(): String = when (kind) {
            ProtectionRuleKind.USER_PATH -> "路径 $pattern"
            ProtectionRuleKind.USER_APP -> "应用 $pattern"
            ProtectionRuleKind.INTERNAL_TRASH -> "回收站内部目录（系统级，无法解除）"
            ProtectionRuleKind.UNVERIFIED -> "尚未核对"
        }
    }
}

/**
 * 系统级保护：不进入保护名单、不能在设置里关闭。这里只做说明，实际约束分别在
 * [UserMediaGuard]、聊天媒体 Root 流程、[OrdinaryFileTrash] 与各扫描器里执行。
 */
internal data class SystemProtection(val title: String, val scope: String, val why: String)

internal object SystemProtections {
    val all: List<SystemProtection> = listOf(
        SystemProtection("相机与相册原件", "DCIM、Pictures、Movies 及其子目录",
            "你自己拍摄或保存的照片视频无法从别处找回：不进入默认勾选和「全选」，存储分析分类视图只读，其它视图需逐项勾选并再次确认。"),
        SystemProtection("录屏", "MIUI/ScreenRecorder、ScreenRecords、录屏 等系统录屏目录",
            "按相机原件同等对待，只能逐项勾选，确认后进入回收站。"),
        SystemProtection("聊天数据库与配置", "微信 / QQ / TIM 的数据库、账号与配置文件",
            "聊天记录本体无法恢复：任何清理只处理媒体缓存文件夹，从不读取或删除数据库与配置。"),
        SystemProtection("应用私有数据", "/data/data、/data/user 下除聊天媒体以外的内容",
            "只有聊天媒体（Root）在你逐个选择文件夹并确认后处理，其它应用私有数据只读统计。"),
        SystemProtection("白泽回收站", OrdinaryFileTrash.DIRECTORY + " 与 Android/data 下的恢复目录",
            "这里是已删除文件的恢复副本，只能在「回收站」恢复或永久删除。")
    )
}

/** 「移入回收站」结束后的一句话结果；所有项目都被保护时不再显示含糊的「未确认」。 */
internal object StorageTrashSummary {
    fun status(selected: Int, removed: Int, removedSize: String, protectedCount: Int, unverifiedProtection: Int,
        otherRetained: Int, unconfirmed: Int, stopped: Boolean): String = buildString {
        if (stopped) append("已停止 · ")
        when {
            removed > 0 -> append("已移入回收站 $removed 个文件，占用 $removedSize，尚未释放空间")
            selected > 0 && protectedCount == selected -> append("没有可移动的文件：$protectedCount 项受保护，点文件查看命中的规则")
            selected > 0 && unverifiedProtection == selected -> append("没有可移动的文件：保护名单尚未核对，请重连 Root 后重试")
            unconfirmed > 0 && unconfirmed == selected -> append("移入回收站未确认，请重新扫描核对")
            else -> append("没有文件被移动")
        }
        if (removed > 0 || protectedCount != selected) {
            if (protectedCount > 0) append(" · $protectedCount 项受保护")
        }
        if (unverifiedProtection > 0 && unverifiedProtection != selected) append(" · $unverifiedProtection 项保护未核对")
        if (otherRetained > 0) append(" · $otherRetained 项保留")
        if (unconfirmed > 0 && unconfirmed != selected) append(" · $unconfirmed 项结果未确认")
    }

    /** MediaStore 索引查询常在 100 ms 内完成，「0.0 秒」容易被当成没有执行。 */
    fun elapsedLabel(elapsedMs: Long): String = when {
        elapsedMs <= 0L -> ""
        elapsedMs < 100L -> "用时不到 0.1 秒（读取系统文件索引）"
        else -> "用时 ${"%.1f".format(java.util.Locale.ROOT, elapsedMs / 1000.0)} 秒"
    }
}
