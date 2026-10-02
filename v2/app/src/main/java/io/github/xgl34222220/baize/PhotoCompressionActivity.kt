package io.github.xgl34222220.baize

import android.os.Bundle
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoCompressionActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private var source: File? = null
    private var preview by mutableStateOf<PhotoCompressionPreview?>(null)
    private var quality by mutableStateOf(80)
    private var edge by mutableStateOf(2048)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("选择一张 JPEG 照片，先比较再另存")
    private var pendingExport: File? = null
    private var exportRequested by mutableStateOf(false)
    private val select = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri) }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        exportRequested = false
        if (uri != null) {
            val outputFile = pendingExport ?: preview?.takeIf { it.savesSpace }?.output
            if (outputFile != null && outputFile.isFile) runTask {
                contentResolver.openOutputStream(uri, "w")?.use { output -> outputFile.inputStream().use { it.copyTo(output) }; output.flush() }
                    ?: error("无法写入目标；请检查是否留下不完整副本")
                pendingExport = null
                "已另存压缩副本，原图未修改。新副本不含 EXIF、定位和拍摄时间。"
            } else status = "预览已过期，未写入副本；请检查并移除刚创建的空白文档后重新预览。"
        } else pendingExport = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun restoredFile(key: String): File? = savedInstanceState?.getString(key)?.let { File(it) }
            ?.takeIf { it.parentFile?.canonicalPath == cacheDir.canonicalPath && it.isFile }
        source = restoredFile("source")
        pendingExport = restoredFile("export")
        quality = savedInstanceState?.getInt("quality", 80) ?: 80
        edge = savedInstanceState?.getInt("edge", 2048) ?: 2048
        exportRequested = savedInstanceState?.getBoolean("pickerOutstanding", false) == true && pendingExport != null
        if (savedInstanceState?.getBoolean("copyInterrupted", false) == true) status = "上次导出结果待核对，请检查目标副本是否完整；原图未修改，可重新生成预览。"
        cacheDir.listFiles().orEmpty().filter { it.name.startsWith("photo-") && System.currentTimeMillis() - it.lastModified() > 86_400_000L && it != source && it != pendingExport }.forEach { it.delete() }
        setContent {
            val settings by appearance.settings.collectAsState()
            BaiZeTheme(settings) {
                Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
                    topBar = { DetailPageHeader("照片瘦身", "先预览 · 始终保留原图", ::finish) {} }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { DetailGlassPanel {
                            Text("仅处理普通 SDR JPEG。HDR、动图、实况、HEIC、RAW、带特殊色彩配置的照片会被拒绝。新副本会移除 EXIF、定位、拍摄时间；方向会按原图信息校正。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { select.launch(arrayOf("image/jpeg")) }, enabled = !busy && !exportRequested) { Text("选择照片") }
                            FileFilterChoices("JPEG 质量", listOf(60 to "60", 80 to "80", 90 to "90"), quality) { if (!busy && !exportRequested) { quality = it; preview = null } }
                            FileFilterChoices("最长边", listOf(1280 to "1280", 2048 to "2048", 4096 to "4096"), edge) { if (!busy && !exportRequested) { edge = it; preview = null } }
                            TextButton(onClick = { buildPreview() }, enabled = source != null && !busy && !exportRequested) { Text("生成压缩预览") }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        } }
                        preview?.let { result -> item { DetailGlassPanel {
                            Text("原图预览", style = MaterialTheme.typography.titleMedium)
                            Image(result.original.asImageBitmap(), "原图方向校正后的预览", Modifier.fillMaxWidth().height(220.dp))
                            Text("压缩副本预览", style = MaterialTheme.typography.titleMedium)
                            Image(result.compressed.asImageBitmap(), "压缩副本效果预览", Modifier.fillMaxWidth().height(220.dp))
                            Text("${Formatter.formatFileSize(this@PhotoCompressionActivity, result.originalBytes)} → ${Formatter.formatFileSize(this@PhotoCompressionActivity, result.outputBytes)} · ${result.width} × ${result.height}")
                            Text(if (result.savesSpace) "另存副本不会自动删除原图；两份同时保留会增加当前占用。" else "没有节省空间，建议保留原图，本次不导出。", style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = result.savesSpace && !busy && !exportRequested, onClick = {
                                pendingExport = result.output; exportRequested = true; export.launch("白泽压缩副本-${System.currentTimeMillis()}.jpg")
                            }) { Text("选择位置，另存副本") }
                        } } }
                    }
                }
            }
        }
        if (savedInstanceState == null) intent.getStringExtra("photo_uri")?.let { import(Uri.parse(it)) }
    }
    private fun import(uri: Uri) {
        if (busy || exportRequested) return
        preview = null
        runTask {
            val file = File(cacheDir, "photo-source-${java.util.UUID.randomUUID()}.jpg")
            try {
                contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                        check(total <= PhotoCompressionPolicy.MAX_INPUT_BYTES) { "照片超过 32 MiB，暂不处理" }; output.write(buffer, 0, count)
                    }
                } } ?: error("无法读取所选照片")
                PhotoCompressionPolicy.checkJpeg(file.readBytes())
                source?.delete(); source = file
                "照片已读取，原图未修改；选择质量后生成预览"
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
    private fun buildPreview() {
        val input = source ?: return
        val q = quality; val size = edge
        runTask {
            val output = File(cacheDir, "photo-preview-${java.util.UUID.randomUUID()}.jpg")
            val result = PhotoCompression.preview(input, output, q, size)
            preview?.output?.delete(); preview = result
            "预览已生成，确认效果后可另存副本"
        }
    }
    private fun runTask(action: () -> String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            status = withContext(Dispatchers.IO) { runCatching(action).getOrElse { it.message ?: "处理未完成，原图保持不变" } }
            busy = false
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source", source?.path); outState.putString("export", pendingExport?.path)
        outState.putInt("quality", quality); outState.putInt("edge", edge)
        outState.putBoolean("pickerOutstanding", exportRequested)
        outState.putBoolean("copyInterrupted", busy && pendingExport != null && !exportRequested)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        super.onDestroy()
        if (!busy && !isChangingConfigurations && !exportRequested) { source?.delete(); preview?.output?.delete() }
    }
}
