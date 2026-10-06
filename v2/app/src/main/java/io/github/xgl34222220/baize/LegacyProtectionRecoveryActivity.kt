package io.github.xgl34222220.baize

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Deliberately local: reviewing historical preferences never requires or writes a Root service. */
class LegacyProtectionRecoveryActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val model: LegacyProtectionRecoveryViewModel by viewModels {
        val context = applicationContext
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LegacyProtectionRecoveryViewModel(object : LegacyProtectionRecoveryAccess {
                    override fun read() = LegacyProtectionRecovery.read(context)
                    override fun resolve(expected: LegacyProtectionRecovery.RecoverySnapshot,
                        packages: Set<String>, paths: Set<String>) =
                        LegacyProtectionRecovery.resolve(context, expected, packages, paths)
                }) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val settings = appearance.settings.collectAsStateWithLifecycle().value
            val dark = settings.themeMode == ThemeMode.DARK ||
                settings.themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BaiZeTheme(settings) {
                CompositionLocalProvider(LocalAppearanceSettings provides settings) {
                    LegacyProtectionRecoveryScreen(model.state, ::finish, model::refresh,
                        model::togglePackage, model::togglePath, model::save)
                }
            }
        }
        model.initialize()
    }
}

internal interface LegacyProtectionRecoveryAccess {
    fun read(): LegacyProtectionRecovery.RecoverySnapshot
    fun resolve(expected: LegacyProtectionRecovery.RecoverySnapshot,
        packages: Set<String>, paths: Set<String>): LegacyProtectionRecovery.RecoverySnapshot
}

internal data class LegacyProtectionRecoveryUiState(
    val snapshot: LegacyProtectionRecovery.RecoverySnapshot? = null,
    val selectedPackages: Set<String> = emptySet(),
    val selectedPaths: Set<String> = emptySet(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val needsRefresh: Boolean = false,
    val confirmed: Boolean = false,
    val error: String = ""
) {
    val busy get() = loading || saving
    val canReview get() = !busy && !needsRefresh && error.isEmpty() && snapshot?.let {
        it.pending && it.pendingPackages.containsAll(selectedPackages) && it.pendingPaths.containsAll(selectedPaths)
    } == true
}

/** Keeps selections and an in-flight save across rotation. Failed/uncertain writes are never retried. */
internal class LegacyProtectionRecoveryViewModel(
    private val access: LegacyProtectionRecoveryAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    var state by mutableStateOf(LegacyProtectionRecoveryUiState())
        private set
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        refresh()
    }

    fun refresh() {
        if (state.busy) return
        state = state.copy(loading = true, error = "")
        viewModelScope.launch {
            try {
                val snapshot = withContext(io) { access.read() }
                val unchanged = snapshot.fingerprint == state.snapshot?.fingerprint
                state = state.copy(snapshot = snapshot,
                    selectedPackages = if (unchanged) state.selectedPackages else snapshot.pendingPackages,
                    selectedPaths = if (unchanged) state.selectedPaths else snapshot.pendingPaths,
                    loading = false, needsRefresh = false, confirmed = state.confirmed && !snapshot.pending)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state = state.copy(loading = false, needsRefresh = true, confirmed = false,
                    error = "旧版保护读取失败，暂不能确认。请刷新重试。${error.message.orEmpty()}")
            }
        }
    }

    fun togglePackage(value: String) {
        if (!state.canReview || value !in state.snapshot!!.pendingPackages) return
        state = state.copy(selectedPackages = state.selectedPackages.toggle(value))
    }

    fun togglePath(value: String) {
        if (!state.canReview || value !in state.snapshot!!.pendingPaths) return
        state = state.copy(selectedPaths = state.selectedPaths.toggle(value))
    }

    fun save() {
        if (!state.canReview) return
        val expected = requireNotNull(state.snapshot)
        val packages = state.selectedPackages.toSet()
        val paths = state.selectedPaths.toSet()
        // Set synchronously, before launching IO, so repeated taps cannot submit a second write.
        state = state.copy(saving = true, error = "")
        viewModelScope.launch {
            try {
                val resolved = withContext(io) { access.resolve(expected, packages, paths) }
                check(!resolved.pending) { "仍有旧版保护待确认，请刷新后重新核对。" }
                state = state.copy(snapshot = resolved, selectedPackages = emptySet(), selectedPaths = emptySet(),
                    saving = false, confirmed = true, needsRefresh = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state = state.copy(saving = false, needsRefresh = true, confirmed = false,
                    error = "保存未确认，请刷新核对后再继续。${error.message.orEmpty()}")
            }
        }
    }

    private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value
}
