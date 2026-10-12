package io.github.xgl34222220.baize.root

import org.json.JSONObject
import io.github.xgl34222220.baize.BuildConfig
import java.io.File
import java.util.Properties

/** App-only upgrades cannot make an older independent scheduler safe. */
internal object ModuleCleanupSafety {
    fun rejection(moduleDir: File = File(RootPaths.MODULE_DIR)): String? {
        val properties = runCatching { Properties().apply {
            File(moduleDir, "module.prop").reader().use { load(it) }
        } }.getOrNull()
        // The paired module is identified by id + versionCode only. The display version
        // follows the release (v3.3.3, …); pinning it to the old "2.0.0" schema string made
        // every save on a matching 3.x module fail with module_safety_update_required.
        val moduleCode = properties?.getProperty("versionCode")?.trim()
        if (properties?.getProperty("id")?.trim() == "baize_v2" &&
            moduleCode == BuildConfig.VERSION_CODE.toString()) return null
        val found = when {
            properties == null -> "未读取到模块信息"
            properties.getProperty("id")?.trim() != "baize_v2" -> "当前模块不是白泽"
            else -> "当前模块版本号 ${moduleCode ?: "未知"}"
        }
        return JSONObject().put("success", false).put("error", "module_safety_update_required")
            .put("message", "此清理任务需要配套 ${BuildConfig.VERSION_CODE} 模块（$found）。请更新模块并重启后再启用；仍可停止或关闭已有任务。")
            .toString()
    }

    fun enablesAutomaticWork(updates: Map<String, String>): Boolean = updates.any { (key, value) ->
        value == "1" && (key == "clean_apk_packages" || key == "daily_schedule_enabled" ||
            key.startsWith("maint_clean_") ||
            (key.startsWith("schedule_") && key.endsWith("_enabled")))
    }
}
