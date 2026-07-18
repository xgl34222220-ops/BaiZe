package io.github.xgl34222220.baize.ui.appearance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.baize.ThemeManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppearanceRepository(application.applicationContext)

    val settings: StateFlow<AppearanceSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppearanceSettings()
    )

    fun setUiStyle(value: UiStyle) {
        viewModelScope.launch { repository.setUiStyle(value) }
    }

    fun setThemeMode(value: ThemeMode) {
        ThemeManager.setMode(getApplication(), value.storageValue)
        viewModelScope.launch { repository.setThemeMode(value) }
    }

    fun setSeedPalette(paletteId: String, argb: Int) {
        ThemeManager.setPalette(getApplication(), paletteId)
        viewModelScope.launch { repository.setSeedArgb(argb) }
    }

    fun setKolorStyle(value: KolorStyle) {
        val legacyId = when (value) {
            KolorStyle.SOFT -> "tonal_spot"
            KolorStyle.VIBRANT -> "vibrant"
            KolorStyle.NEUTRAL -> "neutral"
        }
        ThemeManager.setMonetStyle(getApplication(), legacyId)
        viewModelScope.launch { repository.setKolorStyle(value) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        ThemeManager.setAmoled(getApplication(), enabled)
        viewModelScope.launch { repository.setAmoledBlack(enabled) }
    }

    fun setBlurEnabled(enabled: Boolean) {
        ThemeManager.setBlur(getApplication(), enabled)
        viewModelScope.launch { repository.setBlurEnabled(enabled) }
    }

    fun setGlassEnabled(enabled: Boolean) {
        ThemeManager.setGlass(getApplication(), enabled)
        viewModelScope.launch { repository.setGlassEnabled(enabled) }
    }
}
