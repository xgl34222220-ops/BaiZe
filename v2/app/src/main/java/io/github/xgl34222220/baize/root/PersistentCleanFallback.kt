package io.github.xgl34222220.baize.root

import org.json.JSONObject

/** A stored snapshot may resume an explicitly absent native snapshot, never a failed deletion. */
internal object PersistentCleanFallback {
    fun execute(native: () -> JSONObject, persisted: () -> String): String {
        val result = try {
            native()
        } catch (error: Exception) {
            // An exception may occur after one or more files were removed. Re-running the stored
            // plan here would silently submit another deletion with an unconfirmed first result.
            return JSONObject()
                .put("success", false)
                .put("error", "persistent_clean_unconfirmed")
                .put("resultUnconfirmed", true)
                .put("message", "本轮清理中断，可能已处理部分项目；不会自动重试，请重新扫描")
                .put("cause", (error.message ?: error.javaClass.simpleName).take(512))
                .toString()
        }
        return if (result.optString("error") == "snapshot_expired") persisted() else result.toString()
    }
}
