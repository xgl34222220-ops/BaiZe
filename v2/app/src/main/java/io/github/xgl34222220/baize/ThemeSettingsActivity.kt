package io.github.xgl34222220.baize

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import io.github.xgl34222220.baize.databinding.ActivityThemeSettingsBinding

class ThemeSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityThemeSettingsBinding
    private var bindingValues = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.modeRow.setOnClickListener { showModeDialog() }
        binding.paletteRow.setOnClickListener { showPaletteDialog() }

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
        binding.glassSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingValues) return@setOnCheckedChangeListener
            ThemeManager.setGlass(this, checked)
            recreate()
        }

        renderValues()
    }

    private fun renderValues() {
        bindingValues = true
        val palette = ThemeManager.currentPalette(this)
        binding.modeValue.text = "${ThemeManager.modeLabel(this)}  ›"
        binding.paletteValue.text = "${palette.label}  ›"
        binding.monetSwitch.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        binding.monetSwitch.isChecked = ThemeManager.isMonetEnabled(this)
        binding.amoledSwitch.isChecked = ThemeManager.isAmoledEnabled(this)
        binding.glassSwitch.isChecked = ThemeManager.isGlassEnabled(this)
        binding.paletteRow.alpha = if (binding.monetSwitch.isChecked) 0.72f else 1f
        binding.paletteHint.text = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            "当前 Android 版本不支持 Monet，使用固定配色"
        } else if (binding.monetSwitch.isChecked) {
            "Monet 开启时作为无法取色时的回退方案"
        } else {
            "当前正在使用固定配色"
        }
        bindingValues = false
    }

    private fun showModeDialog() {
        val values = arrayOf(ThemeManager.MODE_SYSTEM, ThemeManager.MODE_LIGHT, ThemeManager.MODE_DARK)
        val labels = arrayOf("跟随系统", "浅色", "深色")
        val checked = values.indexOf(ThemeManager.currentMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("主题模式")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setMode(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPaletteDialog() {
        val palettes = ThemeManager.palettes
        val labels = palettes.map { "${it.label}\n${it.description}" }.toTypedArray()
        val checked = palettes.indexOfFirst { it.id == ThemeManager.currentId(this) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("固定配色")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                ThemeManager.setPalette(this, palettes[which].id)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
