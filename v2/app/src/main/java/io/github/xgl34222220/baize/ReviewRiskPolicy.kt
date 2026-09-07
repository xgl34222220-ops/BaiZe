package io.github.xgl34222220.baize

/** Manual choice is separate from default selection and hard deletion boundaries. */
internal object ReviewRiskPolicy {
    fun selectable(risk: String, blockedReason: String): Boolean =
        risk != "critical" && blockedReason.isBlank()

    fun defaultSelected(risk: String, blockedReason: String, includeMedium: Boolean): Boolean =
        selectable(risk, blockedReason) && (risk == "low" || (risk == "medium" && includeMedium))

    fun appPackage(path: String): String {
        val match = owner.find(path) ?: return ""
        return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private val owner = Regex("^/data/(?:user|user_de)/[0-9]+/([^/]+)(?:/|$)|^/data/data/([^/]+)(?:/|$)|/Android/(?:data|media|obb)/([^/]+)(?:/|$)")
}
