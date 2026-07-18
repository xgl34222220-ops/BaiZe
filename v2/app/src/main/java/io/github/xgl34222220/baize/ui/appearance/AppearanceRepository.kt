package io.github.xgl34222220.baize.ui.appearance

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        val amoledBlack = booleanPreferencesKey(ThemeManager.KEY_AMOLED)
        val blurEnabled = booleanPreferencesKey(ThemeManager.KEY_BLUR)
        val glassEnabled = booleanPreferencesKey(ThemeManager.KEY_GLASS)
    }

    val settings: Flow<AppearanceSettings> = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
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
                amoledBlack = preferences[Keys.amoledBlack] ?: false,
                blurEnabled = preferences[Keys.blurEnabled] ?: true,
                glassEnabled = preferences[Keys.glassEnabled] ?: true
            )
        }

    suspend fun setUiStyle(value: UiStyle) {
        context.appearanceDataStore.edit { it[Keys.uiStyle] = value.name }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        context.appearanceDataStore.edit { it[Keys.themeMode] = value.storageValue }
    }

    suspend fun setSeedArgb(value: Int) {
        context.appearanceDataStore.edit { it[Keys.seedArgb] = value }
    }

    suspend fun setKolorStyle(value: KolorStyle) {
        context.appearanceDataStore.edit { it[Keys.kolorStyle] = value.name }
    }

    suspend fun setAmoledBlack(enabled: Boolean) {
        context.appearanceDataStore.edit { it[Keys.amoledBlack] = enabled }
    }

    suspend fun setBlurEnabled(enabled: Boolean) {
        context.appearanceDataStore.edit { it[Keys.blurEnabled] = enabled }
    }

    suspend fun setGlassEnabled(enabled: Boolean) {
        context.appearanceDataStore.edit { it[Keys.glassEnabled] = enabled }
    }

    private fun legacyAccentToArgb(id: String?): Int = when (id) {
        "red" -> 0xFFC70018.toInt()
        "pink" -> 0xFFC50056.toInt()
        "purple" -> 0xFFAF00C7.toInt()
        "deep_purple" -> 0xFF7900F5.toInt()
        "indigo" -> 0xFF1559F4.toInt()
        "blue" -> 0xFF0A79B8.toInt()
        "light_blue" -> 0xFF0080A0.toInt()
        else -> 0xFF3975F4.toInt()
    }
}
