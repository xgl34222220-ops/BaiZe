package io.github.xgl34222220.baize

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.WeakHashMap

/** One deliberate tap opens one destination. Resume, rather than a timer, unlocks navigation. */
internal object CleanerNavigation {
    private val pending = WeakHashMap<ComponentActivity, Boolean>()
    fun normalizedProfile(profile: String): String = profile.takeIf {
        it in setOf("safe", "cache", "deep", "rules", "empty", "fragments", "corpses")
    } ?: "safe"

    fun scan(source: ComponentActivity, profile: String = "safe"): Boolean = open(source,
        Intent(source, ScanWorkbenchActivity::class.java)
            .putExtra(ScanWorkbenchActivity.EXTRA_PROFILE, normalizedProfile(profile)))

    fun open(source: ComponentActivity, intent: Intent): Boolean {
        if (source.isFinishing || source.isDestroyed || pending[source] == true) return false
        if (!pending.containsKey(source)) {
            pending[source] = false
            source.lifecycle.addObserver(LifecycleEventObserver { owner, event ->
                val activity = owner as? ComponentActivity ?: return@LifecycleEventObserver
                if (event == Lifecycle.Event.ON_RESUME) pending[activity] = false
                if (event == Lifecycle.Event.ON_DESTROY) pending.remove(activity)
            })
        }
        pending[source] = true
        try {
            source.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (error: RuntimeException) {
            pending[source] = false
            throw error
        }
        return true
    }
}
