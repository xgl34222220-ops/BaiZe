package io.github.xgl34222220.baize.root

import org.json.JSONObject
import java.io.File
import java.util.Properties

/** App-only upgrades cannot make an older independent scheduler safe. */
internal object ModuleCleanupSafety {
    fun rejection(moduleDir: File = File(RootPaths.MODULE_DIR)): String? {
        val properties = runCatching { Properties().apply {
            File(moduleDir, "module.prop").reader().use { load(it) }
        } }.getOrNull()
        if (properties?.getProperty("id") == "baize_v2" &&
            properties.getProperty("version")?.removePrefix("v") == "2.0.0" &&
            properties.getProperty("versionCode") == "30016") return null
        return JSONObject().put("success", false).put("error", "module_safety_update_required")
            .put("message", "此清理任务需要配套 30016 模块。请更新模块并重启后再启用；仍可停止或关闭已有任务。")
            .toString()
    }

    fun enablesAutomaticWork(updates: Map<String, String>): Boolean = updates.any { (key, value) ->
        value == "1" && (key == "clean_apk_packages" || key == "daily_schedule_enabled" ||
            (key.startsWith("schedule_") && key.endsWith("_enabled")))
    }
}
