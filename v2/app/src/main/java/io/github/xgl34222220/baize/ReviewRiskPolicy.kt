package io.github.xgl34222220.baize

/** Manual choice is separate from default selection and hard deletion boundaries. */
internal object ReviewRiskPolicy {
    fun selectable(risk: String, blockedReason: String): Boolean =
        risk != "critical" && blockedReason.isBlank()

    fun defaultSelected(risk: String, blockedReason: String, includeMedium: Boolean, category: String = ""): Boolean =
        !perItemOnly(category) && selectable(risk, blockedReason) && (risk == "low" || (risk == "medium" && includeMedium))

    /** 聊天收到的安装包：不默认勾选、不进全选/分组批量选择，只能逐项勾选，处理时移入回收站。 */
    fun perItemOnly(category: String): Boolean =
        io.github.xgl34222220.baize.root.NativeProfileEngine.recoverableOnly(category)

    fun appPackage(path: String): String {
        val match = owner.find(path) ?: return ""
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private val owner = Regex("^/data/(?:user|user_de)/[0-9]+/([^/]+)(?:/|$)|^/data/data/([^/]+)(?:/|$)|/Android/(?:data|media|obb)/([^/]+)(?:/|$)")
}
