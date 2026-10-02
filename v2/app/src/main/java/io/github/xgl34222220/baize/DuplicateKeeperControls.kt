package io.github.xgl34222220.baize

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DuplicateKeeperControls(state: StorageToolsUiState, onChange: (DuplicateKeeperPreference, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DetailGlassPanel {
        Text("保留偏好：${state.keeperPreference.label}", style = MaterialTheme.typography.titleSmall)
        Text("按内容相同分组，目录和日期不能证明哪份是原件。每组可手动选择保留副本。", style = MaterialTheme.typography.bodySmall)
        if (state.keeperPreference == DuplicateKeeperPreference.DIRECTORY) BaiZePathText(state.keeperDirectory)
        TextButton(onClick = { open = true }, enabled = !state.running) { Text("设置保留偏好") }
    }
    if (open) {
        var choice by remember { mutableStateOf(state.keeperPreference) }
        var directory by remember { mutableStateOf(state.keeperDirectory) }
        val valid = choice != DuplicateKeeperPreference.DIRECTORY || StorageMediaRepository.safeSharedFile("${directory.trim().trimEnd('/')}/file")
        BaiZeDialog(onDismissRequest = { open = false }, title = { Text("明确保留哪份") }, text = {
            Column {
                FileFilterChoices("保留顺序", DuplicateKeeperPreference.entries.map { it to it.label }, choice) { choice = it }
                if (choice == DuplicateKeeperPreference.DIRECTORY) OutlinedTextField(directory, { directory = it }, label = { Text("完整目录路径") },
                    supportingText = { Text("如 /storage/emulated/0/Pictures；未命中时优先最新副本") }, isError = !valid, modifier = Modifier.fillMaxWidth())
                Text("应用后清空当前勾选，需重新确认。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { BaiZeDialogButton(onClick = { onChange(choice, directory); open = false }, enabled = valid) { Text("应用") } },
            dismissButton = { BaiZeDialogButton(onClick = { open = false }) { Text("取消") } })
    }
}

@Composable
internal fun StorageComparisonThumbnail(record: StorageFileRecord) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, record.identity) {
        value = withContext(Dispatchers.IO) { runCatching {
            val guard = ApkDeletionGuard.forContext(context)
            if (!StorageMediaRepository.unchanged(record, guard)) return@runCatching null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(record.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(options.outWidth, options.outHeight) / sample > 256) sample *= 2
            val decoded = BitmapFactory.decodeFile(record.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (StorageMediaRepository.unchanged(record, guard)) decoded else { decoded?.recycle(); null }
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), "图片预览：${record.name}", Modifier.fillMaxWidth().height(120.dp)) }
}
