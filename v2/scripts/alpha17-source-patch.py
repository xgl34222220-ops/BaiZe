from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


root = Path("v2")
manager = root / "app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt"
activity = root / "app/src/main/java/io/github/xgl34222220/baize/ThemeSettingsActivity.kt"

replace_once(
    manager,
    'import com.google.android.material.color.DynamicColors\n',
    '''import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.utilities.Hct
''',
    "dynamic color imports",
)

replace_once(
    manager,
    '''        if (monet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }

    private fun migrateLegacySettings(context: Context) {
''',
    '''        if (monet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = DynamicColorsOptions.Builder()
                .setContentBasedSource(styledMonetSeed(activity))
                .build()
            DynamicColors.applyToActivityIfAvailable(activity, options)
        }
    }

    /**
     * Material 1.12 exposes content-based dynamic colors but not the newer variant API used by
     * the reference app. Derive a real wallpaper/accent seed in HCT so every visible style option
     * produces a distinct palette instead of being a decorative preference only.
     */
    private fun styledMonetSeed(context: Context): Int {
        val sourceColor = if (currentId(context) == "default" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { context.getColor(android.R.color.system_accent1_500) }
                .getOrDefault(currentPalette(context).preview.first())
        } else {
            currentPalette(context).preview.first()
        }
        val source = Hct.fromInt(sourceColor)
        var hue = source.hue
        var chroma = source.chroma

        when (currentMonetStyle(context).id) {
            "tonal_spot" -> chroma = 36.0
            "neutral" -> chroma = 8.0
            "vibrant" -> chroma = maxOf(72.0, source.chroma * 1.30)
            "expressive" -> {
                hue = (hue + 240.0) % 360.0
                chroma = maxOf(44.0, source.chroma * 0.90)
            }
            "rainbow" -> {
                hue = (hue + 60.0) % 360.0
                chroma = 56.0
            }
            "fruit_salad" -> {
                hue = (hue + 310.0) % 360.0
                chroma = 48.0
            }
            "monochrome" -> chroma = 0.0
            "fidelity" -> chroma = maxOf(24.0, source.chroma)
            "content" -> chroma = maxOf(32.0, source.chroma * 0.82)
        }

        if (currentColorStandard(context).id == "m3_2025" && currentMonetStyle(context).id != "monochrome") {
            hue = (hue + 12.0) % 360.0
            chroma = chroma * 1.15 + 6.0
        }
        return Hct.from(hue, chroma.coerceIn(0.0, 120.0), 50.0).toInt()
    }

    private fun migrateLegacySettings(context: Context) {
''',
    "functional Monet seed variants",
)

replace_once(
    activity,
    '''            ThemeManager.setMonetStyle(this, option.id)
            renderValues()
''',
    '''            ThemeManager.setMonetStyle(this, option.id)
            recreate()
''',
    "Monet style live apply",
)
replace_once(
    activity,
    '''            ThemeManager.setColorStandard(this, option.id)
            renderValues()
''',
    '''            ThemeManager.setColorStandard(this, option.id)
            recreate()
''',
    "color standard live apply",
)
