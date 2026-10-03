package io.github.xgl34222220.baize

import android.app.Application
import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCompressionRegressionTest {
    private fun jpeg(file: File): File {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until 240) for (x in 0 until 320) bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }; bitmap.recycle()
        return file
    }
    @Test fun metadataChoiceRetainsTimeAndCameraButAlwaysRemovesGpsAndBakesOrientation() {
        val dir = Files.createTempDirectory("baize-photo-metadata").toFile()
        try {
            val source = jpeg(File(dir, "source.jpg"))
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05")
                setAttribute(ExifInterface.TAG_MAKE, "Synthetic Camera")
                setAttribute(ExifInterface.TAG_ORIENTATION, "6"); setLatLong(12.345, 67.890); saveAttributes()
            }
            val original = source.readBytes()
            for (mode in PhotoMetadataMode.entries) {
                val output = File(dir, "${mode.name}.jpg")
                val preview = PhotoCompression.preview(source, output, 60, 2048, mode)
                try {
                    assertEquals(240, preview.width); assertEquals(320, preview.height)
                    assertTrue(preview.savesSpace)
                    val metadata = ExifInterface(output)
                    assertNull(metadata.latLong)
                    assertEquals(if (mode == PhotoMetadataMode.STRIP) null else "2020:01:02 03:04:05", metadata.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                    assertEquals(if (mode == PhotoMetadataMode.CAMERA) "Synthetic Camera" else null, metadata.getAttribute(ExifInterface.TAG_MAKE))
                    assertEquals(ExifInterface.ORIENTATION_NORMAL, metadata.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
                } finally { preview.original.recycle(); preview.compressed.recycle() }
                assertArrayEquals(original, source.readBytes())
            }
        } finally { dir.deleteRecursively() }
    }
    private fun fixture(block: (PhotoBatchTestProvider, PhotoCompressionViewModel) -> Unit) {
        val provider = PhotoBatchTestProvider()
        val app = RuntimeEnvironment.getApplication<Application>()
        provider.attachInfo(app, ProviderInfo().apply { authority = PhotoBatchTestProvider.AUTHORITY; grantUriPermissions = true; exported = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS" })
        ShadowContentResolver.registerProviderInternal(PhotoBatchTestProvider.AUTHORITY, provider)
        val history = File(app.filesDir, "photo-compression-history.json"); history.delete()
        val model = PhotoCompressionViewModel(app, SavedStateHandle())
        try { block(provider, model) } finally { provider.root.deleteRecursively(); history.delete() }
    }
    private fun await(model: PhotoCompressionViewModel) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (model.state.value.busy && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertFalse(model.state.value.status, model.state.value.busy)
    }
    @Test fun batchExportsOnlySmallerValidJpegsAndHistorySkipsRepeatOriginalsAndCopies() = fixture { provider, model ->
        val source = jpeg(File(provider.root, "source.jpg")); val original = source.readBytes()
        File(provider.root, "invalid.jpg").writeText("not an image")
        model.options(quality = 60); model.selectBatch(listOf(provider.uri("source.jpg"), provider.uri("invalid.jpg")))
        model.exportBatch(provider.tree); await(model)
        assertEquals(1, model.state.value.outcomes.count { it.succeeded }); assertEquals(2, model.state.value.outcomes.size)
        assertEquals(1, model.state.value.history.size); assertEquals(1, provider.created)
        assertArrayEquals(original, source.readBytes())
        val exported = provider.outputIds.single()
        model.selectBatch(listOf(provider.uri("source.jpg"), provider.uri(exported)))
        model.exportBatch(provider.tree); await(model)
        assertEquals(0, model.state.value.outcomes.count { it.succeeded }); assertEquals(1, provider.created)
        assertTrue(model.state.value.outcomes.all { it.message.contains("已压缩") })
    }
    @Test fun failedExportVerificationRemovesOwnedCopyAndDoesNotCreateHistory() = fixture { provider, model ->
        val source = jpeg(File(provider.root, "source.jpg")); val original = source.readBytes()
        provider.corruptOutputReads = true
        model.selectBatch(listOf(provider.uri("source.jpg"))); model.exportBatch(provider.tree); await(model)
        assertEquals(1, provider.created); assertEquals(1, provider.deleted)
        assertEquals(0, model.state.value.outcomes.count { it.succeeded }); assertTrue(model.state.value.history.isEmpty())
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun cancelBetweenItemsPreservesSuccessfulCopyAndDoesNotExportRemainingOriginal() = fixture { provider, model ->
        jpeg(File(provider.root, "first.jpg")); jpeg(File(provider.root, "second.jpg"))
        provider.onRead = { if (it == "second.jpg") model.stop() }
        model.selectBatch(listOf(provider.uri("first.jpg"), provider.uri("second.jpg")))
        model.exportBatch(provider.tree); await(model)
        assertEquals(1, provider.created); assertEquals(1, model.state.value.history.size)
        assertTrue(model.state.value.status.contains("已停止")); assertTrue(File(provider.root, "second.jpg").exists())
    }
    @Test fun cancelledFolderPickerAndInterruptedProcessNeverReplayAnExport() = fixture { provider, model ->
        jpeg(File(provider.root, "source.jpg")); model.selectBatch(listOf(provider.uri("source.jpg")))
        assertTrue(model.prepareBatchExport()); model.exportBatch(null)
        assertFalse(model.state.value.busy); assertEquals("", model.state.value.picker); assertEquals(0, provider.created)
        val restored = PhotoCompressionViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle(mapOf("active" to true)))
        assertFalse(restored.state.value.busy); assertTrue(restored.state.value.status.contains("未自动重试")); assertEquals(0, provider.created)
    }
}

class PhotoBatchTestProvider : DocumentsProvider() {
    val root: File = Files.createTempDirectory("baize-photo-provider").toFile()
    var created = 0; var deleted = 0; var corruptOutputReads = false
    val outputIds = mutableListOf<String>()
    var onRead: (String) -> Unit = {}
    val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "destination")
    fun uri(id: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, id)
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> documentId
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (documentId == "destination") DocumentsContract.Document.MIME_TYPE_DIR else "image/jpeg"
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> null
        } }.toTypedArray()) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "destination" && mimeType == "image/jpeg")
        val id = "output-${++created}.jpg"; File(root, id).createNewFile(); outputIds += id; return id
    }
    override fun deleteDocument(documentId: String) { require(documentId in outputIds); File(root, documentId).delete(); outputIds.remove(documentId); deleted++ }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "destination" && documentId in outputIds
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        var file = File(root, documentId)
        if (mode == "r") { onRead(documentId)
            if (corruptOutputReads && documentId in outputIds) file = File(root, "corrupt.bin").apply { writeText("incomplete") } }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
    companion object { const val AUTHORITY = "baize.test.photo.documents" }
}
