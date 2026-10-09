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
        "app-profiles.rules",
        "external.rules",
        "hidden.rules",
        "review.rules",
        "custom.rules",
        "deep.rules",
        "risk-overrides.conf",
        "rules.meta.env",
        "custom-preview-files.rules"
    )

    fun ensure(context: Context): File {
        val imported = runCatching { io.github.xgl34222220.baize.IndependentRuleBundles.forContext(context).active() }.getOrNull()
        val custom = File(context.filesDir, "custom-preview-enabled.rules").takeIf { it.isFile && it.length() <= 64 * 1024 }?.readBytes()
        return RuleBundleStore.install(File(RootPaths.STATE_DIR, "app-rules"), files) { name ->
            if (name == "custom-preview-files.rules") custom ?: ByteArray(0)
            else imported?.files?.get(name) ?: context.assets.open(name).use { it.readBytes() }
        }
    }
}
