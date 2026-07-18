package io.github.xgl34222220.baize

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.xgl34222220.baize.databinding.ActivityThemeSettingsBinding
import io.github.xgl34222220.baize.ui.ThemePopupMenu
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import kotlinx.coroutines.launch

class ThemeSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityThemeSettingsBinding
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private var appearance = AppearanceSettings()
    private var bindingValues = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (ThemeManager.isAmoledActive(this)) {
            binding.root.setBackgroundColor(Color.BLACK)
            window.statusBarColor = Color.BLACK
            window.navigationBarColor = Color.BLACK
        }

        binding.backButton.setOnClickListener { finish() }
        binding.uiStyleRow.setOnClickListener { showUiStyleMenu() }
        binding.modeRow.setOnClickListener { showModeMenu() }
        binding.styleRow.setOnClickListener { showMonetStyleMenu() }
        binding.standardRow.setOnClickListener { showColorStandardMenu() }
        binding.accentRow.setOnClickListener { showAccentMenu() }

        binding.monetSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingValues) return@setOnCheckedChangeListener
            ThemeManager.setMonet(this, checked)
            recreate()
        }
        binding.amoledSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingValues) return@setOnCheckedChangeListener
            appearanceViewModel.setAmoledBlack(checked)
            recreate()
        }
        binding.blurSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) appearanceViewModel.setBlurEnabled(checked)
        }
        binding.floatingSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setFloatingDock(this, checked)
        }
        binding.glassSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) appearanceViewModel.setGlassEnabled(checked)
        }
        binding.predictiveSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setPredictiveBack(this, checked)
        }
        binding.edgeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setFollowEdge(this, checked)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appearanceViewModel.settings.collect { settings ->
                    appearance = settings
                    renderValues()
                }
            }
        }
        renderValues()
    }

    private fun renderValues() {
        bindingValues = true
        val style = ThemeManager.currentMonetStyle(this)
        val standard = ThemeManager.currentColorStandard(this)
        val accent = ThemeManager.currentPalette(this)
        val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val monetEnabled = ThemeManager.isMonetEnabled(this)

        binding.uiStyleValue.text = appearance.uiStyle.label
        binding.modeValue.text = appearance.themeMode.label
        binding.styleValue.text = appearance.kolorStyle.label
        binding.styleDots.setColors(style.preview)
        binding.standardValue.text = standard.label
        binding.accentValue.text = accent.label
        binding.accentDots.setColors(accent.preview)

        binding.monetSwitch.isEnabled = monetSupported
        binding.monetSwitch.setCheckedSilently(monetEnabled)
        binding.amoledSwitch.setCheckedSilently(appearance.amoledBlack)
        binding.blurSwitch.setCheckedSilently(appearance.blurEnabled)
        binding.floatingSwitch.setCheckedSilently(ThemeManager.isFloatingDockEnabled(this))
        binding.glassSwitch.setCheckedSilently(appearance.glassEnabled)
        binding.predictiveSwitch.setCheckedSilently(ThemeManager.isPredictiveBackEnabled(this))
        binding.edgeSwitch.setCheckedSilently(ThemeManager.isFollowEdgeEnabled(this))

        binding.styleRow.isEnabled = monetSupported && monetEnabled
        binding.standardRow.isEnabled = monetSupported && monetEnabled
        binding.styleRow.alpha = if (binding.styleRow.isEnabled) 1f else 0.48f
        binding.standardRow.alpha = if (binding.standardRow.isEnabled) 1f else 0.48f
        bindingValues = false
    }

    private fun showUiStyleMenu() {
        val options = UiStyle.entries.map { ThemePopupMenu.Option(it.name, it.label) }
        ThemePopupMenu.show(this, binding.uiStyleRow, options, appearance.uiStyle.name) { option ->
            appearanceViewModel.setUiStyle(UiStyle.fromStorage(option.id))
        }
    }

    private fun showModeMenu() {
        val options = ThemeMode.entries.map { ThemePopupMenu.Option(it.storageValue, it.label) }
        ThemePopupMenu.show(this, binding.modeRow, options, appearance.themeMode.storageValue) { option ->
            appearanceViewModel.setThemeMode(ThemeMode.fromStorage(option.id))
            recreate()
        }
    }

    private fun showMonetStyleMenu() {
        if (!binding.styleRow.isEnabled) return
        val options = listOf(
            ThemePopupMenu.Option(KolorStyle.SOFT.name, KolorStyle.SOFT.label, ThemeManager.monetStyles.first { it.id == "tonal_spot" }.preview),
            ThemePopupMenu.Option(KolorStyle.VIBRANT.name, KolorStyle.VIBRANT.label, ThemeManager.monetStyles.first { it.id == "vibrant" }.preview),
            ThemePopupMenu.Option(KolorStyle.NEUTRAL.name, KolorStyle.NEUTRAL.label, ThemeManager.monetStyles.first { it.id == "neutral" }.preview)
        )
        ThemePopupMenu.show(this, binding.styleRow, options, appearance.kolorStyle.name) { option ->
            val selected = KolorStyle.entries.firstOrNull { it.name == option.id } ?: KolorStyle.VIBRANT
            appearanceViewModel.setKolorStyle(selected)
        }
    }

    private fun showColorStandardMenu() {
        if (!binding.standardRow.isEnabled) return
        val options = ThemeManager.colorStandards.map { ThemePopupMenu.Option(it.id, it.label) }
        ThemePopupMenu.show(this, binding.standardRow, options, ThemeManager.currentColorStandard(this).id) { option ->
            ThemeManager.setColorStandard(this, option.id)
            recreate()
        }
    }

    private fun showAccentMenu() {
        val options = ThemeManager.palettes.map {
            ThemePopupMenu.Option(it.id, it.label, it.preview)
        }
        ThemePopupMenu.show(this, binding.accentRow, options, ThemeManager.currentId(this)) { option ->
            val palette = ThemeManager.palettes.firstOrNull { it.id == option.id } ?: ThemeManager.palettes.first()
            appearanceViewModel.setSeedPalette(palette.id, palette.preview.first())
            recreate()
        }
    }
}
