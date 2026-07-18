package io.github.xgl34222220.baize

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import io.github.xgl34222220.baize.databinding.ActivityThemeSettingsBinding
import io.github.xgl34222220.baize.ui.ThemePopupMenu

class ThemeSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityThemeSettingsBinding
    private var bindingValues = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
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
            ThemeManager.setAmoled(this, checked)
            recreate()
        }
        binding.blurSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setBlur(this, checked)
        }
        binding.floatingSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setFloatingDock(this, checked)
        }
        binding.glassSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setGlass(this, checked)
        }
        binding.predictiveSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setPredictiveBack(this, checked)
        }
        binding.edgeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!bindingValues) ThemeManager.setFollowEdge(this, checked)
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

        binding.modeValue.text = ThemeManager.modeLabel(this)
        binding.styleValue.text = style.label
        binding.styleDots.setColors(style.preview)
        binding.standardValue.text = standard.label
        binding.accentValue.text = accent.label
        binding.accentDots.setColors(accent.preview)

        binding.monetSwitch.isEnabled = monetSupported
        binding.monetSwitch.setCheckedSilently(monetEnabled)
        binding.amoledSwitch.setCheckedSilently(ThemeManager.isAmoledEnabled(this))
        binding.blurSwitch.setCheckedSilently(ThemeManager.isBlurEnabled(this))
        binding.floatingSwitch.setCheckedSilently(ThemeManager.isFloatingDockEnabled(this))
        binding.glassSwitch.setCheckedSilently(ThemeManager.isGlassEnabled(this))
        binding.predictiveSwitch.setCheckedSilently(ThemeManager.isPredictiveBackEnabled(this))
        binding.edgeSwitch.setCheckedSilently(ThemeManager.isFollowEdgeEnabled(this))

        binding.styleRow.isEnabled = monetSupported && monetEnabled
        binding.standardRow.isEnabled = monetSupported && monetEnabled
        binding.styleRow.alpha = if (binding.styleRow.isEnabled) 1f else 0.48f
        binding.standardRow.alpha = if (binding.standardRow.isEnabled) 1f else 0.48f
        bindingValues = false
    }

    private fun showModeMenu() {
        val options = listOf(
            ThemePopupMenu.Option(ThemeManager.MODE_SYSTEM, "跟随系统"),
            ThemePopupMenu.Option(ThemeManager.MODE_LIGHT, "浅色"),
            ThemePopupMenu.Option(ThemeManager.MODE_DARK, "深色")
        )
        ThemePopupMenu.show(this, binding.modeRow, options, ThemeManager.currentMode(this)) { option ->
            ThemeManager.setMode(this, option.id)
            recreate()
        }
    }

    private fun showMonetStyleMenu() {
        if (!binding.styleRow.isEnabled) return
        val options = ThemeManager.monetStyles.map {
            ThemePopupMenu.Option(it.id, it.label, it.preview)
        }
        ThemePopupMenu.show(this, binding.styleRow, options, ThemeManager.currentMonetStyle(this).id) { option ->
            ThemeManager.setMonetStyle(this, option.id)
            recreate()
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
            ThemeManager.setPalette(this, option.id)
            recreate()
        }
    }
}
