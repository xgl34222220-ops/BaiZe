package io.github.xgl34222220.baize

/** Original rule values are kept so the manager can select the exact existing records. */
internal data class ApkPathProtectionMatch(val rule: String, val identity: String, val ancestor: Boolean)

internal data class ApkProtectionDetails(
    val paths: List<ApkPathProtectionMatch> = emptyList(),
    val packages: Set<String> = emptySet(),
    val internalTrash: Boolean = false,
    val unavailableReason: String? = null
) {
    val isProtected: Boolean get() = internalTrash || paths.isNotEmpty() || packages.isNotEmpty()
    val manageable: Boolean get() = !internalTrash && unavailableReason == null && isProtected
    val retainedLabel: String get() = when {
        internalTrash -> "已保留 · 回收站内部保护"
        unavailableReason != null -> "已保留 · 保护名单尚未核对"
        paths.isNotEmpty() && packages.isNotEmpty() -> "已保留 · 路径和应用保护，详情见保护原因"
        paths.isNotEmpty() -> "已保留 · 路径保护，详情见保护原因"
        packages.isNotEmpty() -> "已保留 · 应用保护，详情见保护原因"
        else -> "已保留 · 请重新核对保护原因"
    }
    val summary: String get() = when {
        internalTrash -> "白泽回收站内容，需在回收站恢复或永久删除"
        unavailableReason != null -> "保护名单尚未核对，文件保留"
        paths.isNotEmpty() -> (if (paths.first().ancestor) "受父目录保护：" else "受此路径保护：") + paths.first().rule +
            if (paths.size + packages.size > 1) "（共 ${paths.size + packages.size} 条匹配保护）" else ""
        packages.isNotEmpty() -> "受应用保护：${packages.first()}（Android/data 目录）"
        else -> "当前未匹配路径或应用保护，清理前仍会再次核对"
    }
    val explanation: String get() = when {
        internalTrash -> "这是白泽内部回收目录，不属于可取消的白名单。请到回收站选择恢复或永久删除；取消其它保护不会解除回收目录保护。"
        unavailableReason != null -> unavailableReason
        else -> buildList {
            paths.forEach { match ->
                add((if (match.ancestor) "父目录保护（包含此文件）：" else "此文件路径保护：") + match.rule +
                    if (match.identity != match.rule) "\n同一位置：${match.identity}" else "")
            }
            packages.forEach { add("应用保护：$it\n此文件位于该应用的 Android/data 目录；不是按安装包自身包名匹配。") }
            if (manageable) add("各条保护独立生效。移除一条后，父目录、子目录或应用的其它匹配规则仍可能保护此文件。")
        }.joinToString("\n\n")
    }
}
