package io.github.xgl34222220.baize.ui.appearance

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.xgl34222220.baize.ThemeManager
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore(
    name = "appearance",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, ThemeManager.PREFS))
    }
)

class AppearanceRepository(private val context: Context) {
    private object Keys {
        val uiStyle = stringPreferencesKey("ui_style")
        val themeMode = stringPreferencesKey(ThemeManager.KEY_MODE)
        val seedArgb = intPreferencesKey("theme_seed_argb")
        val legacyAccent = stringPreferencesKey(ThemeManager.KEY_ACCENT)
        val kolorStyle = stringPreferencesKey("theme_kolor_style")
        val legacyMonetStyle = stringPreferencesKey(ThemeManager.KEY_MONET_STYLE)
        val monetEnabled = booleanPreferencesKey(ThemeManager.KEY_MONET)
        val amoledBlack = booleanPreferencesKey(ThemeManager.KEY_AMOLED)
        val blurEnabled = booleanPreferencesKey(ThemeManager.KEY_BLUR)
        val glassEnabled = booleanPreferencesKey(ThemeManager.KEY_GLASS)
        val floatingDock = booleanPreferencesKey(ThemeManager.KEY_FLOATING_DOCK)
    }

    val settings: Flow<AppearanceSettings> = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppearanceSettings(
                uiStyle = UiStyle.fromStorage(preferences[Keys.uiStyle]),
                themeMode = ThemeMode.fromStorage(preferences[Keys.themeMode]),
                seedArgb = preferences[Keys.seedArgb]
                    ?: legacyAccentToArgb(preferences[Keys.legacyAccent]),
                kolorStyle = KolorStyle.fromStorage(
                    preferences[Keys.kolorStyle] ?: preferences[Keys.legacyMonetStyle]
                ),
                monetEnabled = preferences[Keys.monetEnabled] ?: false,
                amoledBlack = preferences[Keys.amoledBlack] ?: false,
                blurEnabled = preferences[Keys.blurEnabled] ?: true,
                glassEnabled = preferences[Keys.glassEnabled] ?: true,
                floatingDock = preferences[Keys.floatingDock] ?: true
            )
        }

    suspend fun setUiStyle(value: UiStyle) = edit { it[Keys.uiStyle] = value.name }

    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.themeMode] = value.storageValue }

    suspend fun setSeedArgb(value: Int) = edit { it[Keys.seedArgb] = value }

    suspend fun setKolorStyle(value: KolorStyle) = edit { it[Keys.kolorStyle] = value.name }

    suspend fun setMonetEnabled(enabled: Boolean) = edit { it[Keys.monetEnabled] = enabled }

    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.amoledBlack] = enabled }

    suspend fun setBlurEnabled(enabled: Boolean) = edit { it[Keys.blurEnabled] = enabled }

    suspend fun setGlassEnabled(enabled: Boolean) = edit { it[Keys.glassEnabled] = enabled }

    suspend fun setFloatingDock(enabled: Boolean) = edit { it[Keys.floatingDock] = enabled }

    private suspend inline fun edit(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.appearanceDataStore.edit { preferences -> block(preferences) }
    }

    private fun legacyAccentToArgb(id: String?): Int =
        AccentOptions.firstOrNull { it.id == id }?.argb ?: AccentOptions.first().argb
}