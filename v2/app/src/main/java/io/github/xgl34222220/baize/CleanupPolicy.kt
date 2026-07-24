package io.github.xgl34222220.baize

/**
 * User-facing cleanup policy presets.
 *
 * Presets never contain scheduler cadence fields and never enable direct high-risk deletion. They
 * only tune ordinary cleanup categories, retention thresholds, the per-file ceiling, default
 * workbench selection and whether a high-risk snapshot candidate may be manually quarantined.
 */
enum class CleanupPolicy(
    val id: Int,
    val key: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val autoRisk: String,
    val highRiskMode: String,
    val values: Map<String, Int>,
    val highlights: List<String>
) {
    CONSERVATIVE(
        id = 0,
        key = "conservative",
        title = "保守",
        subtitle = "优先避免误清理，适合重要数据较多的设备",
        badge = "最低干预",
        autoRisk = "low",
        highRiskMode = "audit",
        values = linkedMapOf(
            "clean_app_cache" to 1,
            "clean_external_cache" to 1,
            "clean_system_logs" to 1,
            "clean_oem_logs" to 0,
            "clean_empty_files" to 1,
            "clean_empty_dirs" to 1,
            "clean_root_shells" to 1,
            "clean_app_rules" to 1,
            "clean_hidden_junk" to 0,
            "clean_fragments" to 1,
            "clean_custom_rules" to 0,
            "clean_installer_temp" to 1,
            "clean_apk_packages" to 1,
            "deep_high_risk_enabled" to 0,
            "app_cache_days" to 3,
            "external_cache_days" to 3,
            "system_logs_days" to 14,
            "oem_logs_days" to 14,
            "empty_file_days" to 3,
            "hidden_junk_days" to 7,
            "fragment_days" to 14,
            "installer_temp_days" to 14,
            "apk_package_days" to 45,
            "root_shell_days" to 30,
            "max_file_mb" to 128,
            "quarantine_retention_days" to 14
        ),
        highlights = listOf(
            "扫描后只默认勾选低风险项目",
            "缓存至少保留 3 天，碎片保留 14 天",
            "高风险候选只审计，不提供隔离操作",
            "单文件保护上限 128 MB"
        )
    ),
    BALANCED(
        id = 1,
        key = "balanced",
        title = "均衡",
        subtitle = "兼顾清理效果与安全性，推荐大多数设备使用",
        badge = "推荐",
        autoRisk = "medium",
        highRiskMode = "manual_quarantine",
        values = linkedMapOf(
            "clean_app_cache" to 1,
            "clean_external_cache" to 1,
            "clean_system_logs" to 1,
            "clean_oem_logs" to 0,
            "clean_empty_files" to 1,
            "clean_empty_dirs" to 1,
            "clean_root_shells" to 1,
            "clean_app_rules" to 1,
            "clean_hidden_junk" to 1,
            "clean_fragments" to 1,
            "clean_custom_rules" to 0,
            "clean_installer_temp" to 1,
            "clean_apk_packages" to 1,
            "deep_high_risk_enabled" to 0,
            "app_cache_days" to 0,
            "external_cache_days" to 0,
            "system_logs_days" to 7,
            "oem_logs_days" to 7,
            "empty_file_days" to 0,
            "hidden_junk_days" to 0,
            "fragment_days" to 7,
            "installer_temp_days" to 7,
            "apk_package_days" to 30,
            "root_shell_days" to 14,
            "max_file_mb" to 256,
            "quarantine_retention_days" to 7
        ),
        highlights = listOf(
            "扫描后默认勾选低风险与中风险项目",
            "日志与碎片保留 7 天",
            "高风险候选可逐项手动移入隔离区",
            "单文件保护上限 256 MB"
        )
    ),
    AGGRESSIVE(
        id = 2,
        key = "aggressive",
        title = "积极",
        subtitle = "扩大普通垃圾覆盖范围，适合空间紧张的设备",
        badge = "更强清理",
        autoRisk = "medium",
        highRiskMode = "recommended_quarantine",
        values = linkedMapOf(
            "clean_app_cache" to 1,
            "clean_external_cache" to 1,
            "clean_system_logs" to 1,
            "clean_oem_logs" to 1,
            "clean_empty_files" to 1,
            "clean_empty_dirs" to 1,
            "clean_root_shells" to 1,
            "clean_app_rules" to 1,
            "clean_hidden_junk" to 1,
            "clean_fragments" to 1,
            "clean_custom_rules" to 0,
            "clean_installer_temp" to 1,
            "clean_apk_packages" to 1,
            "deep_high_risk_enabled" to 0,
            "app_cache_days" to 0,
            "external_cache_days" to 0,
            "system_logs_days" to 3,
            "oem_logs_days" to 3,
            "empty_file_days" to 0,
            "hidden_junk_days" to 0,
            "fragment_days" to 1,
            "installer_temp_days" to 3,
            "apk_package_days" to 14,
            "root_shell_days" to 7,
            "max_file_mb" to 1024,
            "quarantine_retention_days" to 3
        ),
        highlights = listOf(
            "扫描后默认勾选低风险与中风险项目",
            "启用 OEM 日志，碎片只保留 1 天",
            "高风险仍不直接删除，仅突出建议手动隔离",
            "单文件保护上限 1 GB"
        )
    );

    fun defaultSelected(risk: String): Boolean = when (autoRisk) {
        "low" -> risk == "low"
        else -> risk == "low" || risk == "medium"
    }

    val canQuarantineHighRisk: Boolean get() = highRiskMode != "audit"

    companion object {
        fun fromId(id: Int): CleanupPolicy = entries.firstOrNull { it.id == id } ?: BALANCED
        fun fromKey(key: String): CleanupPolicy = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}
