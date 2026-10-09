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

    /** 统一后只保留的入口：首页一键扫描 + 4 个次级入口（另有底部导航的清理、设置）。 */
    val PRIMARY_ENTRIES = listOf("一键扫描", "存储分析", "自动任务", "规则与白名单", "历史与回收站")

    /**
     * 原首页工具格 / 清理页手动工具 → 统一后的位置。页面本身保留（深链接、快捷方式、旧版测试仍可打开），
     * 但不再作为独立入口出现。
     */
    val HOME_TOOL_DESTINATIONS: Map<String, String> = linkedMapOf(
        "即时缓存" to "一键扫描 · 应用缓存分类",
        "扫描工作台" to "一键扫描",
        "卸载残留" to "一键扫描 · 需要你复核",
        "安装包" to "一键扫描 · 需要你复核",
        "重复文件" to "一键扫描 · 需要你复核 / 存储分析 · 重复文件视图",
        "截图录屏" to "一键扫描 · 需要你复核 / 存储分析 · 截图录屏视图",
        "旧下载" to "一键扫描 · 需要你复核 / 存储分析 · 旧下载视图",
        "聊天媒体" to "一键扫描 · 需要你复核 / 存储分析 · 聊天媒体视图",
        "根目录整理" to "一键扫描 · 需要你复核 / 存储分析 · 根目录视图",
        "大文件" to "存储分析 · 大文件视图",
        "自定义规则" to "存储分析 · 自定义规则视图",
        "照片瘦身" to "存储分析 · 空间构成",
        "滑动整理" to "存储分析 · 空间构成",
        "回收站" to "历史与回收站",
        "存储维护" to "自动任务 · 系统维护",
        "根目录自动整理" to "自动任务 · 系统维护"
    )
}
