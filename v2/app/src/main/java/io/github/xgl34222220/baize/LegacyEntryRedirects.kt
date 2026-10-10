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
 * | 文件归类「自动归类设置」         | MiuixDashboardActivity(EXTRA_OPEN_AUTOMATION) → 清理 · 自动清理 |
 */
internal object LegacyEntryRedirects {
    const val CACHE_PROFILE = "cache"

    /** 旧 ProfileActivity 支持的分类；其它值（含空值）沿用旧行为：直接关闭，不打开新页面。 */
    val LEGACY_PROFILES = setOf("empty", "rules", "fragments", "deep", "corpses")

    fun profileTarget(profile: String?): String? = profile?.takeIf { it in LEGACY_PROFILES }

    /** manifest 中以 activity-alias 形式保留、指向续清页面的旧组件名。 */
    val SMART_SCAN_ALIASES = listOf(".SmartScanActivity", ".PersistentSmartScanActivity")

    /**
     * 去重后每个 Tab 的固定入口（每个功能只出现一次）。页内视图切换（存储分析视图下拉）、
     * 扫描结果「需要你复核」与操作后的撤销/加入保护链接属于上下文，不算入口。
     */
    val PRIMARY_ENTRIES: Map<String, List<String>> = linkedMapOf(
        "首页" to listOf("一键扫描", "存储分析（点存储概览）", "继续上次清理（仅有未完成计划时）", "自动清理模块"),
        "清理" to listOf("扩大扫描范围", "大文件", "重复文件", "截图与录屏", "旧下载", "聊天媒体", "安装包", "卸载残留",
            "根目录整理", "文件归类", "照片瘦身", "滑动整理", "免 Root 缓存清理", "自动清理", "执行条件与高级", "运行状况"),
        "记录" to listOf("任务记录", "回收站（页内切换隔离区）", "受保护内容", "清理审计"),
        "设置" to listOf("白泽状态（连接与诊断）", "外观与主题", "规则与保护", "运行日志", "卸载残留提醒")
    )

    /**
     * 旧入口 → 现在的位置。页面本身全部保留（manifest 注册、别名、快捷方式、旧 Intent 仍可打开），
     * 只是不再在多个地方重复出现。
     */
    val HOME_TOOL_DESTINATIONS: Map<String, String> = linkedMapOf(
        "首页 · 存储分析格" to "首页 · 存储概览（点击打开存储分析）",
        "首页 · 存储空间卡" to "首页 · 存储概览（点击打开存储分析）",
        "首页 · 自动任务格" to "首页 · 自动清理模块",
        "首页 · 规则与白名单格" to "设置 · 规则与保护 · 保护名单",
        "首页 · 历史与回收站格" to "记录 · 回收站",
        "首页 · 微信专清 / QQ 专清" to "清理 · 聊天媒体",
        "首页 · 推荐清理 / 更多清理" to "清理 · 专项清理",
        "首页 · 清理记录" to "记录",
        "清理 · 一键扫描" to "首页 · 一键扫描",
        "清理 · 规则与保护（空项目 / 规则垃圾 / 残留碎片）" to "首页 · 一键扫描（同一次扫描的分类）",
        "清理 · 规则与保护（清理策略）" to "设置 · 规则与保护 · 清理策略",
        "清理 · 规则与保护（隔离区）" to "记录 · 回收站 · 隔离区",
        "清理 · 附加项目 · 过期安装包" to "清理 · 自动清理 · 任务计划 · 安装包",
        "设置 · 自动任务设置" to "清理 · 自动清理 · 执行条件与高级",
        "设置 · 自动任务记录" to "清理 · 自动清理 · 运行状况",
        "设置 · 保护名单" to "设置 · 规则与保护 · 保护名单",
        "设置 · 规则版本与试跑" to "设置 · 规则与保护 · 规则版本与试跑",
        "设置 · 清理审计 / 运行日志 · 清理明细" to "记录 · 清理审计",
        "运行日志 · 诊断与恢复" to "设置 · 白泽状态（连接与诊断）",
        "文件归类 · 自动归类设置" to "清理 · 自动清理（文件自动归类 / 执行条件与高级）",
        "存储维护 / 根目录自动整理" to "清理 · 自动清理 · 执行条件与高级 · 系统维护",
        "即时缓存（InstantCacheActivity）" to "清理 · 免 Root 缓存清理（InstantCacheActivity 保留注册，无界面入口）",
        "旧版任务恢复（ResumableSmartScanActivity）" to "首页 · 继续上次清理 / 设置 · 连接与诊断"
    )
}
