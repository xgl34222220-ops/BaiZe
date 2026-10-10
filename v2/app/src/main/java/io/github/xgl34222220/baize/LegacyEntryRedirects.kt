package io.github.xgl34222220.baize

/**
 * 旧页面 → 统一页面的映射表。只保留路由，不保留旧 UI：
 *
 * | 旧入口                         | 统一后的页面                              |
 * |-------------------------------|------------------------------------------|
 * | ProfileActivity(profile)      | ScanWorkbenchActivity(profile)           |
 * | CacheActivity                 | ScanWorkbenchActivity("cache")           |
 * | PersistentSmartScanActivity   | ResumableSmartScanActivity（manifest 别名）|
 * | SmartScanActivity             | ResumableSmartScanActivity（manifest 别名）|
 */
internal object LegacyEntryRedirects {
    const val CACHE_PROFILE = "cache"

    /** 旧 ProfileActivity 支持的分类；其它值（含空值）沿用旧行为：直接关闭，不打开新页面。 */
    val LEGACY_PROFILES = setOf("empty", "rules", "fragments", "deep", "corpses")

    fun profileTarget(profile: String?): String? = profile?.takeIf { it in LEGACY_PROFILES }

    /** manifest 中以 activity-alias 形式保留、指向续清页面的旧组件名。 */
    val SMART_SCAN_ALIASES = listOf(".SmartScanActivity", ".PersistentSmartScanActivity")
}
