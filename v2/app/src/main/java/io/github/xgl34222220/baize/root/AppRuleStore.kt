package io.github.xgl34222220.baize.root

import android.content.Context
import java.io.File

/**
 * App-owned foreground rule store.
 *
 * Rules are bundled in the APK. The root module keeps its own copy only for background/scheduled
 * automation, so foreground scanning never depends on /data/adb/modules/baize_v2.
 */
internal object AppRuleStore {
    private val files = listOf(
        "app.rules",
        "external.rules",
        "hidden.rules",
        "review.rules",
        "custom.rules",
        "deep.rules",
        "risk-overrides.conf",
        "rules.meta.env"
    )

    fun ensure(context: Context): File {
        val root = File(RootPaths.STATE_DIR, "app-rules").apply { mkdirs() }
        files.forEach { name ->
            val target = File(root, name)
            val bytes = runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull() ?: return@forEach
            if (target.isFile && target.length() == bytes.size.toLong() &&
                runCatching { target.readBytes().contentEquals(bytes) }.getOrDefault(false)
            ) return@forEach
            val temp = File(root, ".$name.${System.nanoTime()}.tmp")
            runCatching {
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.setReadable(true, true)
                target.setWritable(true, true)
            }.onFailure { temp.delete() }
        }
        return root
    }
}
