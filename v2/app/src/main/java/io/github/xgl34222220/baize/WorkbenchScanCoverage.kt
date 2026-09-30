package io.github.xgl34222220.baize

import org.json.JSONObject

internal data class WorkbenchScanCoverage(val summary: String, val incomplete: Boolean)

/** Coverage is not an estimate of cleanliness. Missing legacy counters stay unknown. */
internal fun workbenchScanCoverage(
    cache: JSONObject?, profile: JSONObject, cacheOk: Boolean, profileOk: Boolean, profileRequired: Boolean = true
): WorkbenchScanCoverage {
    val cacheIncomplete = cache != null && (!cacheOk || cache.optBoolean("cancelled") ||
        (cache.has("complete") && !cache.optBoolean("complete")) || cache.optInt("incompleteRoots") > 0)
    val profileIncomplete = profileRequired && (!profileOk || profile.optBoolean("partial") || profile.optBoolean("cancelled"))
    val descriptions = buildList {
        if (cache != null) {
            if (!cacheOk) add("应用缓存：未完成")
            else if (cache.has("totalRoots") && cache.has("scannedRoots")) {
                val total = cache.optInt("totalRoots").coerceAtLeast(0)
                val checked = cache.optInt("scannedRoots").coerceIn(0, total)
                val incomplete = cache.optInt("incompleteRoots").coerceAtLeast(0)
                add("缓存目录 $checked / $total 已检查" + if (incomplete > 0) " · $incomplete 处未完成" else "")
            } else add("应用缓存：已读取有效快照")
        }
        if (profileRequired) add(if (profileIncomplete) "规则扫描：部分范围未完成" else "规则扫描：已读取有效快照")
        if (cacheIncomplete || profileIncomplete) add("仅显示已检查范围，未完成的目录不代表没有垃圾")
    }
    return WorkbenchScanCoverage(descriptions.joinToString("\n"), cacheIncomplete || profileIncomplete)
}
