package io.github.xgl34222220.baize

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.FileOutputStream

internal object PhotoCompressionPolicy {
    // Android may report ORIENTATION_UNDEFINED (0) for an ordinary JPEG
    // without EXIF. That means no orientation transform, not corrupt metadata.
    fun normalizedOrientation(value: Int): Int {
        require(value in 0..8) { "图片方向信息异常，暂不处理" }
        return if (value == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else value
    }
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    fun checkJpeg(bytes: ByteArray) {
        require(bytes.size in 4..MAX_INPUT_BYTES && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 255 == 216) { "仅支持普通 SDR JPEG；动图、PNG、HEIC、RAW 等暂不处理" }
        require(bytes[bytes.size - 2].toInt() and 255 == 255 && bytes.last().toInt() and 255 == 217) { "包含尾随数据或实况内容，保留原图，不处理" }
        var offset = 2
        var encodedScan = false
        var sawScan = false
        while (offset + 1 < bytes.size) {
            if (encodedScan) {
                while (offset < bytes.size && bytes[offset].toInt() and 255 != 255) offset++
                require(offset + 1 < bytes.size) { "JPEG 扫描数据不完整" }
            }
            require(bytes[offset].toInt() and 255 == 255) { "JPEG 结构无法安全识别" }
            while (offset + 1 < bytes.size && bytes[offset + 1].toInt() and 255 == 255) offset++
            require(offset + 1 < bytes.size) { "JPEG 标记不完整" }
            val marker = bytes[offset + 1].toInt() and 255
            if (encodedScan && (marker == 0 || marker in 208..215)) { offset += 2; continue }
            encodedScan = false
            if (marker == 217) {
                require(sawScan && offset + 2 == bytes.size) { "包含拼接图片或尾随数据，暂不处理" }
                return
            }
            require(marker != 216 && marker != 0 && marker !in 208..215 && offset + 3 < bytes.size) { "JPEG 内容不完整" }
            val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            require(length >= 2 && offset + 2 + length <= bytes.size) { "JPEG 标记长度无效" }
            if (marker in 225..239) {
                val content = bytes.copyOfRange(offset + 4, offset + 2 + length).toString(Charsets.ISO_8859_1)
                require((marker == 225 && content.startsWith("Exif\u0000\u0000")) || (marker == 226 && content.startsWith("ICC_PROFILE\u0000"))) { "含 HDR、色彩配置、实况或未知扩展元数据，暂不处理" }
                require(listOf("hdrgm", "gainmap", "motionphoto", "microvideo", "mpf").none { content.contains(it, true) }) { "含 HDR 或实况扩展，暂不处理" }
            }
            if (marker == 218) { encodedScan = true; sawScan = true }
            offset += 2 + length
        }
        error("JPEG 编码数据不完整")
    }
}
internal data class PhotoCompressionPreview(val original: Bitmap, val compressed: Bitmap, val output: File,
    val originalBytes: Long, val outputBytes: Long, val width: Int, val height: Int) {
    val savesSpace: Boolean get() = outputBytes in 1 until originalBytes
}
internal object PhotoCompression {
    fun preview(source: File, output: File, quality: Int, maxEdge: Int,
        metadata: PhotoMetadataMode = PhotoMetadataMode.STRIP): PhotoCompressionPreview {
        require(quality in 50..95 && maxEdge in setOf(1280, 2048, 4096))
        require(source != output && source.length() <= PhotoCompressionPolicy.MAX_INPUT_BYTES)
        PhotoCompressionPolicy.checkJpeg(source.readBytes())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L) { "图片过大或格式无法安全解码，暂不处理" }
        val exif = ExifInterface(source.path)
        require(exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 1) == 1) { "图片色彩空间不是标准 sRGB，暂不处理" }
        val orientation = PhotoCompressionPolicy.normalizedOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 4_194_304L) sample *= 2
        var bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "无法读取图片" }
        require(bitmap.colorSpace?.isSrgb == true) { "图片不是标准 sRGB 色彩空间，暂不处理" }
        if (Build.VERSION.SDK_INT >= 34) require(!bitmap.hasGainmap()) { "HDR 图片暂不处理" }
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
        } }
        if (orientation != 1) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        val ratio = minOf(1f, maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (ratio < 1) bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
        // App-private preview only. User-visible export always uses CreateDocument as a new copy.
        FileOutputStream(output).use { stream -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) { "编码失败" }; stream.fd.sync() }
        PhotoMetadata.copy(exif, output, metadata)
        val compressed = requireNotNull(BitmapFactory.decodeFile(output.path))
        return PhotoCompressionPreview(bitmap, compressed, output, source.length(), output.length(), bitmap.width, bitmap.height)
    }
}

internal enum class PhotoMetadataMode(val label: String) { STRIP("全部移除"), CAPTURE_TIME("保留拍摄时间"), CAMERA("时间与相机参数") }

internal object PhotoMetadata {
    private val time = listOf("DateTime", "DateTimeOriginal", "DateTimeDigitized", "SubSecTime", "SubSecTimeOriginal",
        "SubSecTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized")
    private val camera = listOf("Make", "Model", "LensMake", "LensModel", "FNumber", "ExposureTime", "PhotographicSensitivity", "FocalLength")
    fun copy(source: ExifInterface, output: File, mode: PhotoMetadataMode) {
        if (mode == PhotoMetadataMode.STRIP) return
        val target = ExifInterface(output)
        (time + if (mode == PhotoMetadataMode.CAMERA) camera else emptyList()).forEach { tag ->
            source.getAttribute(tag)?.takeIf { it.length <= 256 }?.let { target.setAttribute(tag, it) }
        }
        // Pixels have already been rotated. Never copy GPS, maker notes, thumbnails, XMP or HDR.
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.saveAttributes()
    }
}
