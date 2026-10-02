package io.github.xgl34222220.baize

import android.app.Application
import android.graphics.Bitmap
import android.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCompressionTest {
    @Test fun jpegPreviewPreservesOriginalAndNormalizesOrientationWithoutExif() {
        val root = Files.createTempDirectory("baize-photo-test").toFile()
        try {
            val source = File(root, "synthetic.jpg")
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            val random = java.util.Random(1)
            bitmap.setPixels(IntArray(800 * 600) { 0xff000000.toInt() or random.nextInt(0xffffff) }, 0, 800, 0, 0, 800, 600)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }; bitmap.recycle()
            ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); setAttribute(ExifInterface.TAG_DATETIME, "2020:01:01 00:00:00"); saveAttributes() }
            val before = source.readBytes()
            val result = PhotoCompression.preview(source, File(root, "preview.jpg"), 80, 1280)
            assertArrayEquals(before, source.readBytes())
            assertEquals(600, result.width); assertEquals(800, result.height)
            assertTrue(result.savesSpace)
            assertNull(ExifInterface(result.output.path).getAttribute(ExifInterface.TAG_DATETIME))
        } finally { root.deleteRecursively() }
    }
    @Test fun undefinedExifOrientationMeansNoTransformAndInvalidValuesStillFail() {
        assertEquals(1, PhotoCompressionPolicy.normalizedOrientation(0))
        for (orientation in 1..8) assertEquals(orientation, PhotoCompressionPolicy.normalizedOrientation(orientation))
        for (orientation in listOf(-1, 9, 65535)) assertTrue(runCatching { PhotoCompressionPolicy.normalizedOrientation(orientation) }.isFailure)
    }
    @Test fun ordinaryJpegWithoutExifCanBePreviewedWithoutChangingSource() {
        val root = Files.createTempDirectory("baize-photo-no-exif").toFile()
        try {
            val source = File(root, "plain.jpg")
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff4499cc.toInt())
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }; bitmap.recycle()
            val before = source.readBytes()
            val preview = PhotoCompression.preview(source, File(root, "preview.jpg"), 80, 1280)
            assertEquals(64, preview.width); assertEquals(48, preview.height)
            assertArrayEquals(before, source.readBytes())
            assertTrue(preview.output.isFile)
        } finally { root.deleteRecursively() }
    }
    @Test fun unsupportedHdrAnimatedAndTrailingMotionDataAreRejected() {
        assertTrue(runCatching { PhotoCompressionPolicy.checkJpeg("GIF89a".toByteArray()) }.isFailure)
        val concatenated = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0, 2, 0, 0xff.toByte(), 0xd9.toByte(), 0xff.toByte(), 0xd9.toByte())
        assertTrue(runCatching { PhotoCompressionPolicy.checkJpeg(concatenated) }.isFailure)
        val marker = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe2.toByte(), 0, 6, 1, 2, 3, 4, 0xff.toByte(), 0xd9.toByte())
        assertTrue(runCatching { PhotoCompressionPolicy.checkJpeg(marker) }.isFailure)
        assertTrue(runCatching { PhotoCompressionPolicy.checkJpeg(marker + byteArrayOf(1, 2)) }.isFailure)
        assertTrue(runCatching { PhotoCompressionPolicy.checkJpeg(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 127, 127, 0xff.toByte(), 0xd9.toByte())) }.isFailure)
    }
}
