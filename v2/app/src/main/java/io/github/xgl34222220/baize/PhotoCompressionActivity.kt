package io.github.xgl34222220.baize

import android.os.Bundle
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.*

class PhotoCompressionActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val model: PhotoCompressionViewModel by viewModels()
    private val select = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importSingle(uri) }
    private val selectBatch = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) model.selectBatch(uris) }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { model.exportSingle(it) }
    private val exportBatch = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { model.exportBatch(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by appearance.settings.collectAsState()
            val state by model.state.collectAsState()
            var confirmBack by rememberSaveable { mutableStateOf(false) }
            fun back() { if (state.busy) confirmBack = true else finish() }
            BackHandler { back() }
            BaiZeTheme(settings) {
                Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
                    topBar = { DetailPageHeader("照片瘦身", "先预览 · 始终保留原图", { back() }) {} }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { DetailGlassPanel {
                            Text("仅处理普通 SDR JPEG。HDR、动图、实况、HEIC、RAW、带特殊色彩配置的照片会跳过。方向会按原图信息校正。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { select.launch(arrayOf("image/jpeg")) }, enabled = !state.busy && state.picker.isBlank()) { Text("选择照片") }
                            FileFilterChoices("JPEG 质量", listOf(60 to "60", 80 to "80", 90 to "90"), state.quality) { model.options(quality = it) }
                            FileFilterChoices("最长边", listOf(1280 to "1280", 2048 to "2048", 4096 to "4096"), state.edge) { model.options(edge = it) }
                            FileFilterChoices("元数据", PhotoMetadataMode.entries.map { it to it.label }, state.metadata) { model.options(metadata = it) }
                            Text("所有选项都会移除定位信息；保留时间或相机参数仍会保留相应个人信息。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = model::preview, enabled = state.sourceReady && !state.busy && state.picker.isBlank()) { Text("生成压缩预览") }
                            Text(state.status, style = MaterialTheme.typography.bodySmall)
                            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::stop) { Text("停止处理") } }
                        } }
                        state.preview?.let { result -> item { DetailGlassPanel {
                            Text("原图预览", style = MaterialTheme.typography.titleMedium)
                            Image(result.original.asImageBitmap(), "原图方向校正后的预览", Modifier.fillMaxWidth().height(220.dp))
                            Text("压缩副本预览", style = MaterialTheme.typography.titleMedium)
                            Image(result.compressed.asImageBitmap(), "压缩副本效果预览", Modifier.fillMaxWidth().height(220.dp))
                            Text("${Formatter.formatFileSize(this@PhotoCompressionActivity, result.originalBytes)} → ${Formatter.formatFileSize(this@PhotoCompressionActivity, result.outputBytes)} · ${result.width} × ${result.height}")
                            Text(if (result.savesSpace) "另存副本不会自动删除原图；两份同时保留会增加当前占用。" else "没有节省空间，建议保留原图，本次不导出。", style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = result.savesSpace && !state.busy && state.picker.isBlank(), onClick = {
                                if (model.prepareSingleExport()) export.launch("白泽压缩副本-${System.currentTimeMillis()}.jpg")
                            }) { Text("选择位置，另存副本") }
                        } } }
                        item { DetailGlassPanel {
                            Text("批量压缩", style = MaterialTheme.typography.titleMedium)
                            Text("最多 100 张，逐张使用上方参数；只另存更小的副本，原图保留。导出后会重新读取核对内容。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { selectBatch.launch(arrayOf("image/jpeg")) }, enabled = !state.busy && state.picker.isBlank()) { Text("选择多张照片") }
                            Text("已选择 ${state.photos.size} 张", style = MaterialTheme.typography.bodySmall)
                            Row { Checkbox(state.allowRepeat, model::allowRepeat, enabled = !state.busy && state.picker.isBlank()); Text("允许再次处理已压缩照片", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { if (model.prepareBatchExport()) exportBatch.launch(null) }, enabled = state.photos.isNotEmpty() && !state.busy && state.picker.isBlank()) { Text("选择文件夹，批量另存") }
                            state.outcomes.forEach { outcome ->
                                Text(outcome.name, style = MaterialTheme.typography.labelLarge)
                                Text(outcome.message, style = MaterialTheme.typography.bodySmall)
                            }
                        } }
                        item { DetailGlassPanel {
                            Text("压缩记录", style = MaterialTheme.typography.titleMedium)
                            Text("记录已核对的成功导出，最多保留 100 条；批量处理默认跳过相同参数的历史原图和已生成的副本。", style = MaterialTheme.typography.bodySmall)
                            if (state.history.isEmpty()) Text("暂无成功导出记录", style = MaterialTheme.typography.bodySmall)
                            state.history.take(20).forEach { row ->
                                Text(row.name, style = MaterialTheme.typography.labelLarge)
                                Text("${Formatter.formatFileSize(this@PhotoCompressionActivity, row.before)} → ${Formatter.formatFileSize(this@PhotoCompressionActivity, row.after)} · 质量 ${row.quality} · ${row.metadata.label}", style = MaterialTheme.typography.bodySmall)
                                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.epoch)), style = MaterialTheme.typography.labelSmall)
                            }
                        } }
                    }
                }
                if (confirmBack) BaiZeDialog(onDismissRequest = { confirmBack = false }, title = { Text("停止处理并返回？") },
                    text = { Text("已导出的副本会保留，正在写入的副本将停止；原图不会修改。") },
                    confirmButton = { BaiZeDialogButton(onClick = { model.stop(); finish() }) { Text("停止并返回") } },
                    dismissButton = { BaiZeDialogButton(onClick = { confirmBack = false }) { Text("继续处理") } })
            }
        }
        if (savedInstanceState == null) intent.getStringExtra("photo_uri")?.let { model.importSingle(Uri.parse(it)) }
    }
}
