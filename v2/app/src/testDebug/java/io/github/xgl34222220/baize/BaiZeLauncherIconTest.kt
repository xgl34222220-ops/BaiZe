package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Uses framework adaptive-icon inflation and native drawing, including the 108:72 viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BaiZeLauncherIconTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    @Config(sdk = [26, 35])
    fun realAdaptiveDrawableRendersAtLauncherAndArchiveSizes() {
        for (circle in listOf(true, false)) {
            for (size in listOf(48, 192)) {
                val icon = launcher()
                val bitmap = renderAdaptive(icon, size, circle)
                assertEquals("Framework must expand both layers beyond the visible viewport", size * 3 / 2, icon.foreground.bounds.width())
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals("The two OEM masks must produce different corner geometry", if (circle) 0 else 255,
                    Color.alpha(bitmap.getPixel(size / 10, size / 10)))
                assertEquals(255, Color.alpha(bitmap.getPixel(size / 2, size / 2)))
                val emeraldEdge = bitmap.getPixel(size / 2, size / 16)
                assertTrue("The masked top edge must be emerald, with no white tile margin", Color.green(emeraldEdge) > Color.red(emeraldEdge) + 10)
                save(bitmap, "launcher-${maskName(circle)}-${size}dp-api${Build.VERSION.SDK_INT}")
            }
        }
    }

    @Test
    fun themedLayerRetainsItsFaceAt24And48DpAndFitsTheSafeCircle() {
        for (circle in listOf(true, false)) {
            for (size in listOf(24, 48)) {
                for (dark in listOf(false, true)) {
                    val monochrome = requireNotNull(launcher().monochrome)
                    monochrome.mutate().setTint(if (dark) Color.rgb(158, 244, 217) else Color.rgb(0, 80, 66))
                    val background = ColorDrawable(if (dark) Color.rgb(0, 55, 46) else Color.rgb(172, 242, 218))
                    val icon = AdaptiveIconDrawable(background, monochrome)
                    val bitmap = renderAdaptive(icon, size, circle)
                    assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                    save(bitmap, "themed-${if (dark) "dark" else "light"}-${maskName(circle)}-${size}dp")
                }
            }
        }
        // Inspect the actual inflated monochrome layer before Android's viewport crop.
        val mask = render(requireNotNull(launcher().monochrome), 432)
        var solidPixels = 0
        for (y in 0 until 432) for (x in 0 until 432) {
            if (Color.alpha(mask.getPixel(x, y)) > 32) {
                solidPixels++
                assertTrue("Monochrome silhouette exceeds the 66/108 safe circle at $x,$y", hypot(x + .5 - 216, y + .5 - 216) <= 132.0)
            }
        }
        assertTrue(solidPixels > 10_000)
    }

    @Test
    fun notificationUsesTransparentWhiteGlyphAndRasterDecodeBudgetStaysSmall() {
        val notification = render(requireNotNull(resources.getDrawable(R.drawable.ic_baize_notification, null)), 24)
        var visible = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val pixel = notification.getPixel(x, y)
            if (Color.alpha(pixel) > 32) {
                visible++
                assertTrue(Color.red(pixel) >= 250 && Color.green(pixel) >= 250 && Color.blue(pixel) >= 250)
            }
        }
        assertTrue("The small icon must contain a face, not a solid tile", visible in 80..320)
        assertEquals(0, Color.alpha(notification.getPixel(0, 0)))
        save(notification, "notification-24dp")
        val pixels = listOf(R.drawable.ic_baize_art, R.drawable.ic_baize_monochrome_art).sumOf { id ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            resources.openRawResource(id).use { BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue(bounds.outWidth in 1..432 && bounds.outHeight in 1..432)
            bounds.outWidth.toLong() * bounds.outHeight
        }
        assertTrue(pixels < ApkArchivePolicy.MAX_RASTER_PIXELS)
    }

    private fun launcher(): AdaptiveIconDrawable {
        val drawable = resources.getDrawable(R.mipmap.ic_baize, null)
        assertTrue("Launcher resource must resolve to an adaptive icon", drawable is AdaptiveIconDrawable)
        return drawable.mutate() as AdaptiveIconDrawable
    }

    private fun renderAdaptive(icon: AdaptiveIconDrawable, size: Int, circle: Boolean): Bitmap {
        // AOSP's mask is a 100x100 OEM configuration path. Override only that input;
        // AdaptiveIconDrawable itself performs layer expansion, clipping, and drawing.
        val previous = ReflectionHelpers.getStaticField<Path>(AdaptiveIconDrawable::class.java, "sMask")
        val mask = Path().apply {
            if (circle) addCircle(50f, 50f, 50f, Path.Direction.CW)
            else addRoundRect(RectF(0f, 0f, 100f, 100f), 22f, 22f, Path.Direction.CW)
        }
        return try {
            ReflectionHelpers.setStaticField(AdaptiveIconDrawable::class.java, "sMask", mask)
            render(icon, size)
        } finally {
            ReflectionHelpers.setStaticField(AdaptiveIconDrawable::class.java, "sMask", previous)
        }
    }

    private fun render(drawable: Drawable, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(it))
        }

    private fun maskName(circle: Boolean) = if (circle) "circle" else "rounded-square"

    private fun save(bitmap: Bitmap, name: String) {
        val target = File("build/reports/ui-screenshots/baize-icon-$name.png")
        requireNotNull(target.parentFile).mkdirs()
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
