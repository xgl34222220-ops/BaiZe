package io.github.xgl34222220.baize

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

internal object ApkArchiveIcon {
    private val safeTags = setOf("vector", "path", "group", "clip-path", "adaptive-icon", "background",
        "foreground", "monochrome", "shape", "solid", "corners", "stroke", "size", "gradient", "padding",
        "inset", "layer-list", "item", "bitmap", "selector", "scale", "clip", "rotate")

    private class ResourceBudget {
        var bytes = 0L
        var pixels = 0L
        var xmlEvents = 0
    }

    fun load(resources: Resources, iconId: Int, zip: ZipFile, checkpoint: () -> Unit): Bitmap {
        if (iconId == 0) throw ApkArchiveReadException(ApkArchiveFailure.ICON_UNAVAILABLE)
        validate(resources, iconId, zip, mutableSetOf(), mutableSetOf(), ResourceBudget(), 0, checkpoint)
        checkpoint()
        // Use the archive's Resources directly: PackageManager's icon cache is keyed by package
        // name/resource id and could otherwise return an installed application's different icon.
        val value = TypedValue()
        resources.getValueForDensity(iconId, DisplayMetrics.DENSITY_DEFAULT, value, true)
        val resourcePath = value.string?.toString()
        val drawable = if (resourcePath != null && !resourcePath.endsWith(".xml")) {
            val options = BitmapFactory.Options().apply { inScaled = false; inJustDecodeBounds = true }
            resources.openRawResource(iconId).use { BitmapFactory.decodeStream(it, null, options) }
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize(options.outWidth, options.outHeight)
            val decoded = resources.openRawResource(iconId).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw ApkArchiveReadException(ApkArchiveFailure.ICON_UNAVAILABLE)
            BitmapDrawable(resources, decoded)
        } else {
            resources.getDrawableForDensity(iconId, DisplayMetrics.DENSITY_DEFAULT, null)
        }
        checkpoint()
        return render(drawable ?: throw ApkArchiveReadException(ApkArchiveFailure.ICON_UNAVAILABLE)).also { checkpoint() }
    }

    private fun validate(resources: Resources, id: Int, zip: ZipFile, seen: MutableSet<Int>, active: MutableSet<Int>, budget: ResourceBudget, depth: Int,
        checkpoint: () -> Unit) {
        checkpoint()
        if (depth > 12 || seen.size >= 64) throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
        if (id in active) throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
        if (id ushr 24 == 1 || !seen.add(id)) return // Framework resources are not archive payloads.
        active.add(id)
        val value = TypedValue()
        resources.getValueForDensity(id, DisplayMetrics.DENSITY_DEFAULT, value, true)
        val path = value.string?.toString() ?: run { active.remove(id); return }
        // Scalar strings (for example vector pathData) are not resource files. References to
        // raw images still need inspection; restricting this to drawable/mipmap misses them.
        if (zip.getEntry(path) == null && resources.getResourceTypeName(id) !in setOf("drawable", "mipmap", "raw")) {
            active.remove(id)
            return
        }
        budget.bytes += ApkArchivePolicy.checkResource(zip, path)
        if (budget.bytes > ApkArchivePolicy.MAX_RESOURCE_BYTES * 2) throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
        if (path.endsWith(".xml")) {
            resources.getXml(id).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    checkpoint()
                    if (++budget.xmlEvents > 4096 || parser.depth > 32) throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    // Do not allow XML to instantiate arbitrary Drawable classes.
                    if (parser.name !in safeTags) throw ApkArchiveReadException(ApkArchiveFailure.ICON_UNAVAILABLE)
                    for (index in 0 until parser.attributeCount) {
                        val reference = parser.getAttributeResourceValue(index, 0)
                        if (reference != 0) {
                            validate(resources, reference, zip, seen, active, budget, depth + 1, checkpoint)
                        }
                    }
                }
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            resources.openRawResource(id).use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) throw ApkArchiveReadException(ApkArchiveFailure.ICON_UNAVAILABLE)
            budget.pixels += options.outWidth.toLong() * options.outHeight
            if (budget.pixels > ApkArchivePolicy.MAX_RASTER_PIXELS) {
                throw ApkArchiveReadException(ApkArchiveFailure.RESOURCE_LIMIT)
            }
        }
        active.remove(id)
    }

    internal fun sampleSize(width: Int, height: Int): Int {
        var result = 1
        while (width / result > ApkArchivePolicy.MAX_ICON_PX * 2 || height / result > ApkArchivePolicy.MAX_ICON_PX * 2) {
            result *= 2
        }
        return result
    }

    internal fun render(drawable: Drawable): Bitmap {
        val size = ApkArchivePolicy.MAX_ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = size.toFloat() / maxOf(width, height)
        val drawnWidth = (width * scale).toInt().coerceIn(1, size)
        val drawnHeight = (height * scale).toInt().coerceIn(1, size)
        drawable.setBounds((size - drawnWidth) / 2, (size - drawnHeight) / 2,
            (size + drawnWidth) / 2, (size + drawnHeight) / 2)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
