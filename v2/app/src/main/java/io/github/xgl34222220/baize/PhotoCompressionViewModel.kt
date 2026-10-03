package io.github.xgl34222220.baize

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class PhotoBatchOutcome(val name: String, val message: String, val succeeded: Boolean = false)
internal data class PhotoCompressionState(val quality: Int = 80, val edge: Int = 2048,
    val metadata: PhotoMetadataMode = PhotoMetadataMode.STRIP, val busy: Boolean = false, val picker: String = "",
    val sourceReady: Boolean = false, val preview: PhotoCompressionPreview? = null,
    val photos: List<String> = emptyList(), val outcomes: List<PhotoBatchOutcome> = emptyList(),
    val history: List<PhotoCompressionRecord> = emptyList(), val allowRepeat: Boolean = false,
    val status: String = "选择 JPEG 照片，先比较再另存")

/** Activity recreation keeps the worker and progress; process death never silently replays an export. */
internal class PhotoCompressionViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val history = PhotoCompressionHistory(File(context.filesDir, "photo-compression-history.json"))
    private val cancelled = AtomicBoolean(false)
    private fun cacheFile(key: String) = saved.get<String>(key)?.let(::File)?.takeIf {
        it.parentFile?.canonicalPath == context.cacheDir.canonicalPath && it.isFile && it.name.startsWith("photo-") }
    private var source = cacheFile("source")
    private var pending = cacheFile("pending")
    private val mutableState = MutableStateFlow(PhotoCompressionState(
        quality = saved["quality"] ?: 80, edge = saved["edge"] ?: 2048,
        metadata = runCatching { PhotoMetadataMode.valueOf(saved["metadata"] ?: "STRIP") }.getOrDefault(PhotoMetadataMode.STRIP),
        photos = saved.get<ArrayList<String>>("photos")?.toList().orEmpty(), sourceReady = source != null,
        picker = saved.get<String>("picker").orEmpty(), history = runCatching { history.records() }.getOrDefault(emptyList()),
        status = if (saved.get<Boolean>("active") == true) "上次处理已中断，请核对目标副本；原图未修改，未自动重试。" else "选择 JPEG 照片，先比较再另存"))
    val state = mutableState.asStateFlow()
    init {
        saved["active"] = false
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("photo-") &&
            System.currentTimeMillis() - it.lastModified() > 86_400_000L && it != source && it != pending }.forEach { it.delete() }
    }
    fun options(quality: Int = state.value.quality, edge: Int = state.value.edge, metadata: PhotoMetadataMode = state.value.metadata) {
        if (state.value.busy || state.value.picker.isNotBlank()) return
        saved["quality"] = quality; saved["edge"] = edge; saved["metadata"] = metadata.name
        state.value.preview?.output?.delete()
        mutableState.update { it.copy(quality = quality, edge = edge, metadata = metadata, preview = null) }
    }
    fun allowRepeat(value: Boolean) { if (!state.value.busy) mutableState.update { it.copy(allowRepeat = value) } }
    private fun check() { if (cancelled.get()) throw CancellationException("已停止") }
    fun stop() { cancelled.set(true); mutableState.update { it.copy(status = "正在停止；已导出的副本保留") } }
    private fun task(action: () -> String) {
        if (state.value.busy) return
        cancelled.set(false); saved["active"] = true
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { action() }
                mutableState.update { it.copy(status = message) }
            } catch (error: Exception) {
                mutableState.update { it.copy(status = if (cancelled.get() || error is CancellationException)
                    "已停止，已导出的副本保留；原图未修改" else error.message ?: "处理未完成，原图未修改") }
            } finally { saved["active"] = false; mutableState.update { it.copy(busy = false) } }
        }
    }
    private fun name(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.take(100)?.takeIf { it.isNotBlank() } ?: "照片.jpg"
    private fun read(uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
            val buffer = ByteArray(65536); var total = 0L
            while (true) { check(); val count = input.read(buffer); if (count < 0) break; total += count
                require(total <= PhotoCompressionPolicy.MAX_INPUT_BYTES) { "照片超过 32 MiB，暂不处理" }
                output.write(buffer, 0, count) }
        } } ?: error("无法读取所选照片")
        PhotoCompressionPolicy.checkJpeg(file.readBytes())
    }
    fun importSingle(uri: Uri) {
        if (state.value.busy || state.value.picker.isNotBlank()) return
        task {
            val file = File(context.cacheDir, "photo-source-${UUID.randomUUID()}.jpg")
            try {
                read(uri, file); check()
                source?.delete(); state.value.preview?.output?.delete(); source = file
                saved["source"] = file.path; saved["name"] = name(uri)
                mutableState.update { it.copy(sourceReady = true, preview = null) }
                "照片已读取，原图未修改；选择参数后生成预览"
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
    fun preview() {
        val input = source ?: return; val options = state.value
        if (options.busy || options.picker.isNotBlank()) return
        task {
            val output = File(context.cacheDir, "photo-preview-${UUID.randomUUID()}.jpg")
            try {
                val result = PhotoCompression.preview(input, output, options.quality, options.edge, options.metadata)
                check(); state.value.preview?.output?.delete(); mutableState.update { it.copy(preview = result) }
                "预览已生成，确认效果后可另存副本"
            } catch (error: Exception) { output.delete(); throw error }
        }
    }
    fun prepareSingleExport(): Boolean {
        val current = state.value
        if (current.busy || current.picker.isNotBlank()) return false
        pending = current.preview?.takeIf { it.savesSpace }?.output ?: return false
        saved["pending"] = pending!!.path; saved["picker"] = "single"
        mutableState.update { it.copy(picker = "single") }; return true
    }
    fun cancelPicker() { saved["picker"] = ""; mutableState.update { it.copy(picker = "") } }
    private fun writeCopy(file: File, uri: Uri): String {
        try {
            val expected = file.inputStream().use { photoDigest(it, ::check) }
            context.contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { check(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            }; output.flush() } ?: error("无法写入目标")
            check()
            val actual = context.contentResolver.openInputStream(uri)?.use { photoDigest(it, ::check) } ?: error("目标无法读取，未确认导出完整")
            require(actual == expected) { "导出副本核对失败" }
            return actual
        } catch (error: Exception) {
            val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            if (cancelled.get() && deleted) throw error
            throw java.io.IOException("${error.message}；${if (deleted) "不完整副本已移除" else "请检查目标处留下的副本"}", error)
        }
    }
    private fun record(name: String, input: File, output: File, uri: Uri, options: PhotoCompressionState): String {
        val outputHash = writeCopy(output, uri)
        val row = PhotoCompressionRecord(name, input.inputStream().use { photoDigest(it) }, outputHash, uri.toString(),
            input.length(), output.length(), options.quality, options.edge, options.metadata)
        return try { history.add(row); mutableState.update { it.copy(history = history.records()) }; "已导出并核对，原图保留" }
            catch (_: Exception) { "已导出并核对，原图保留；压缩记录保存失败" }
    }
    fun exportSingle(uri: Uri?) {
        cancelPicker()
        if (uri == null) return
        val output = pending; val input = source; val options = state.value
        pending = null; saved["pending"] = null
        if (output == null || input == null || !output.isFile) {
            mutableState.update { it.copy(status = "预览已过期，未写入；请移除目标处的空白文档后重新预览") }; return
        }
        task { record(saved["name"] ?: "照片.jpg", input, output, uri, options) }
    }
    fun selectBatch(uris: List<Uri>) {
        if (state.value.busy) return
        val selected = uris.distinct().take(100).map { it.toString() }
        saved["photos"] = ArrayList(selected)
        mutableState.update { it.copy(photos = selected, outcomes = emptyList(), status = "已选择 ${selected.size} 张照片${if (uris.size > 100) "；最多 100 张，超出的未选入" else ""}，选择文件夹后批量另存") }
    }
    fun prepareBatchExport(): Boolean {
        if (state.value.busy || state.value.photos.isEmpty() || state.value.picker.isNotBlank()) return false
        saved["picker"] = "batch"; mutableState.update { it.copy(picker = "batch") }; return true
    }
    fun exportBatch(tree: Uri?) {
        cancelPicker()
        if (tree == null) return
        val options = state.value; val selected = options.photos.toList()
        if (selected.isEmpty()) return
        mutableState.update { it.copy(outcomes = emptyList()) }
        task {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            selected.forEachIndexed { index, value ->
                check(); val uri = Uri.parse(value); val label = name(uri)
                mutableState.update { it.copy(status = "正在处理 ${index + 1} / ${selected.size}：$label") }
                val input = File(context.cacheDir, "photo-batch-source-${UUID.randomUUID()}.jpg")
                val output = File(context.cacheDir, "photo-batch-output-${UUID.randomUUID()}.jpg")
                var result: PhotoCompressionPreview? = null
                val outcome = try {
                    read(uri, input)
                    val hash = input.inputStream().use { photoDigest(it, ::check) }
                    if (!options.allowRepeat && history.alreadyProcessed(hash, options.quality, options.edge, options.metadata))
                        PhotoBatchOutcome(label, "已压缩过或是压缩副本，跳过")
                    else {
                        result = PhotoCompression.preview(input, output, options.quality, options.edge, options.metadata)
                        check()
                        if (!result!!.savesSpace) PhotoBatchOutcome(label, "未节省空间，跳过") else {
                            val filename = "${label.substringBeforeLast('.').take(64)}-白泽-${UUID.randomUUID().toString().take(8)}.jpg"
                            val target = DocumentsContract.createDocument(context.contentResolver, parent, "image/jpeg", filename)
                                ?: error("无法创建新的压缩副本")
                            PhotoBatchOutcome(label, record(label, input, output, target, options), true)
                        }
                    }
                } catch (error: Exception) {
                    if (cancelled.get() || error is CancellationException) throw error
                    PhotoBatchOutcome(label, error.message ?: "无法处理，跳过")
                } finally { result?.original?.recycle(); result?.compressed?.recycle(); input.delete(); output.delete() }
                mutableState.update { it.copy(outcomes = it.outcomes + outcome) }
            }
            val success = state.value.outcomes.count { it.succeeded }
            "批量完成：$success 张已导出，${selected.size - success} 张跳过或失败；原图均保留"
        }
    }
    override fun onCleared() {
        cancelled.set(true)
        if (!state.value.busy && state.value.picker.isBlank()) { source?.delete(); state.value.preview?.output?.delete() }
        super.onCleared()
    }
}
