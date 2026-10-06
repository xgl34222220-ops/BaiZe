package io.github.xgl34222220.baize.ui.appearance

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.xgl34222220.baize.ThemeManager
import io.github.xgl34222220.baize.CheckedLegacyPreferences
import io.github.xgl34222220.baize.LegacyPreferencesAccess
import io.github.xgl34222220.baize.performance.DisplayPerformanceController
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The only appearance DataStore instance, shared by rendering and protection recovery. */
internal val Context.appearanceDataStore by preferencesDataStore(
    name = "appearance",
    produceMigrations = { context ->
        listOf(AppearanceCopyMigration(context))
    }
)

/** Copy only appearance fields. ThemeManager still reads the source, so NEVER clean it up. */
internal class AppearanceCopyMigration(private val context: Context,
    private val sourceName: String = ThemeManager.PREFS) : DataMigration<Preferences> {
    private fun source() = CheckedLegacyPreferences.read(context, sourceName)
    private val strings = setOf("ui_style", ThemeManager.KEY_MODE, ThemeManager.KEY_ACCENT,
        "theme_kolor_style", ThemeManager.KEY_MONET_STYLE, "refresh_rate_mode")
    private val booleans = setOf(ThemeManager.KEY_MONET, ThemeManager.KEY_AMOLED,
        ThemeManager.KEY_BLUR, ThemeManager.KEY_GLASS, ThemeManager.KEY_FLOATING_DOCK,
        "adaptive_smooth_mode")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        !LegacyPreferencesAccess.isBlocked(context) && source().keys.any { it in strings || it in booleans || it == "theme_seed_argb" }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val copied = currentData.toMutablePreferences()
        val present = currentData.asMap().keys.mapTo(mutableSetOf()) { it.name }
        source().forEach { (name, value) ->
            if (name !in present) when {
                name in strings && value is String -> copied[stringPreferencesKey(name)] = value
                name in booleans && value is Boolean -> copied[booleanPreferencesKey(name)] = value
                name == "theme_seed_argb" && value is Int -> copied[intPreferencesKey(name)] = value
            }
        }
        return copied.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

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
        val refreshRateMode = stringPreferencesKey("refresh_rate_mode")
        val adaptiveSmoothMode = booleanPreferencesKey("adaptive_smooth_mode")
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
                floatingDock = preferences[Keys.floatingDock] ?: true,
                refreshRateMode = RefreshRateMode.fromStorage(preferences[Keys.refreshRateMode]),
                adaptiveSmoothMode = preferences[Keys.adaptiveSmoothMode] ?: true
            )
        }

    suspend fun setUiStyle(value: UiStyle) =
        edit { it[Keys.uiStyle] = value.name }

    suspend fun setThemeMode(value: ThemeMode) {
        edit { it[Keys.themeMode] = value.storageValue }
        ThemeManager.setMode(context, value.storageValue)
    }

    suspend fun setSeedArgb(value: Int) {
        val accent = accentOptionFor(value)
        edit { it[Keys.seedArgb] = accent.argb }
        ThemeManager.setPalette(context, accent.id)
    }

    suspend fun setKolorStyle(value: KolorStyle) {
        edit { it[Keys.kolorStyle] = value.name }
        ThemeManager.setMonetStyle(
            context,
            when (value) {
                KolorStyle.SOFT -> "tonal_spot"
                KolorStyle.VIBRANT -> "vibrant"
                KolorStyle.NEUTRAL -> "neutral"
            }
        )
    }

    suspend fun setMonetEnabled(enabled: Boolean) {
        edit { it[Keys.monetEnabled] = enabled }
        ThemeManager.setMonet(context, enabled)
    }

    suspend fun setAmoledBlack(enabled: Boolean) {
        edit { it[Keys.amoledBlack] = enabled }
        ThemeManager.setAmoled(context, enabled)
    }

    suspend fun setBlurEnabled(enabled: Boolean) {
        edit { it[Keys.blurEnabled] = enabled }
        ThemeManager.setBlur(context, enabled)
    }

    suspend fun setGlassEnabled(enabled: Boolean) {
        edit { preferences ->
            preferences[Keys.glassEnabled] = enabled
            if (!enabled) preferences[Keys.blurEnabled] = false
        }
        ThemeManager.setGlass(context, enabled)
        if (!enabled) ThemeManager.setBlur(context, false)
    }

    suspend fun setFloatingDock(enabled: Boolean) {
        edit { it[Keys.floatingDock] = enabled }
        ThemeManager.setFloatingDock(context, enabled)
    }

    suspend fun setRefreshRateMode(value: RefreshRateMode) {
        edit { it[Keys.refreshRateMode] = value.name }
        DisplayPerformanceController.setRefreshRateMode(value)
    }

    suspend fun setAdaptiveSmoothMode(enabled: Boolean) {
        edit { it[Keys.adaptiveSmoothMode] = enabled }
        DisplayPerformanceController.setAdaptiveSmoothMode(enabled)
    }

    private suspend inline fun edit(crossinline block: (MutablePreferences) -> Unit) {
        context.appearanceDataStore.edit { preferences -> block(preferences) }
    }

    private fun legacyAccentToArgb(id: String?): Int =
        AccentOptions.firstOrNull { it.id == id }?.argb ?: AccentOptions.first().argb
}
