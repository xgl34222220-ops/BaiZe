package io.github.xgl34222220.baize

import android.os.Bundle
import android.media.MediaScannerConnection
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class FileTrashActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private var entries by mutableStateOf(emptyList<TrashEntry>())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var budget by mutableStateOf(OrdinaryFileTrash.DEFAULT_BUDGET)
    private var recoverChanged by mutableStateOf<TrashEntry?>(null)
    private var confirm by mutableStateOf<TrashEntry?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        budget = OrdinaryFileTrash.budget(this)
        refresh()
        setContent {
            val settings by appearance.settings.collectAsState()
            BaiZeTheme(settings) {
                Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
                    topBar = { DetailPageHeader("文件回收站", "恢复普通文件 · 不覆盖已有内容", ::finish) {} }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { DetailGlassPanel {
                            Text("已占用 ${Formatter.formatFileSize(this@FileTrashActivity, entries.sumOf { it.bytes })} / ${Formatter.formatFileSize(this@FileTrashActivity, budget)}")
                            Text("前台手动处理的 APK、下载、大文件和重复副本统一保留。模块自动清理仍按原配置执行，不进入此回收站。移动不释放空间；30 天后标为到期，仍需你确认永久删除。卸载白泽会删除回收站。", style = MaterialTheme.typography.bodySmall)
                            Text("容量上限", style = MaterialTheme.typography.labelLarge)
                            Row { listOf(1L, 5L, 10L).forEach { gib ->
                                TextButton(enabled = !busy, onClick = {
                                    budget = gib * 1024 * 1024 * 1024
                                    getSharedPreferences("ordinary-trash", MODE_PRIVATE).edit().putLong("budget", budget).apply()
                                }) { Text("$gib GiB${if (budget == gib * 1024 * 1024 * 1024) " ✓" else ""}") }
                            }
                            }
                            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        } }
                        if (entries.isEmpty()) item { DetailGlassPanel { Text("回收站为空") } }
                        items(entries, key = { it.id }) { entry -> DetailGlassPanel {
                            Text(entry.original.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                            BaiZePathText(entry.original)
                            Text("${Formatter.formatFileSize(this@FileTrashActivity, entry.bytes)} · ${if (entry.expires <= System.currentTimeMillis()) "已到期，可手动清空" else "保留至 " + DateFormat.getDateInstance().format(Date(entry.expires))}", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = !busy, onClick = { runOperation {
                                    val restored = OrdinaryFileTrash.forContext(this@FileTrashActivity).restore(entry.id)
                                    MediaScannerConnection.scanFile(this@FileTrashActivity, arrayOf(restored.path), null, null)
                                    "已恢复至 ${restored.path}"
                                } }) { Text("恢复") }
                                TextButton(enabled = !busy, onClick = { confirm = entry }) { Text("永久删除") }
                            }
                            TextButton(enabled = !busy, onClick = { recoverChanged = entry }) { Text("恢复内容变化的副本") }
                            TextButton(enabled = !busy, onClick = { runOperation {
                                OrdinaryFileTrash.forContext(this@FileTrashActivity).forgetMissing(entry.id)
                                "已移除无内容记录，原文件未操作"
                            } }) { Text("清理无内容记录") }
                        } }
                    }
                }
                recoverChanged?.let { entry -> BaiZeDialog(onDismissRequest = { recoverChanged = null },
                    title = { Text("恢复当前内容副本？") }, text = { Text("${entry.original}\n内容可能在移动后被其他应用改写，无法保证与原扫描相同。将另存当前可读内容并核对完整副本；已有文件不会被覆盖。") },
                    confirmButton = { BaiZeDialogButton(onClick = { recoverChanged = null; runOperation {
                        val restored = OrdinaryFileTrash.forContext(this@FileTrashActivity).restore(entry.id, allowChanged = true)
                        MediaScannerConnection.scanFile(this@FileTrashActivity, arrayOf(restored.path), null, null)
                        "已恢复当前内容至 ${restored.path}"
                    } }) { Text("恢复当前内容") } }, dismissButton = { BaiZeDialogButton(onClick = { recoverChanged = null }) { Text("取消") } }) }
                confirm?.let { entry -> BaiZeDialog(onDismissRequest = { confirm = null },
                    title = { Text("永久删除此文件？") },
                    text = { Text("${entry.original}\n此操作无法撤销，将删除 ${Formatter.formatFileSize(this@FileTrashActivity, entry.bytes)} 内容；实际可用空间以系统统计为准。") },
                    confirmButton = { BaiZeDialogButton(onClick = { confirm = null; runOperation {
                        val bytes = OrdinaryFileTrash.forContext(this@FileTrashActivity).purge(entry.id)
                        "已永久删除 ${Formatter.formatFileSize(this@FileTrashActivity, bytes)} 内容；实际可用空间以系统统计为准"
                    } }) { Text("永久删除") } },
                    dismissButton = { BaiZeDialogButton(onClick = { confirm = null }) { Text("取消") } }) }
            }
        }
    }
    private fun refresh() { lifecycleScope.launch { entries = withContext(Dispatchers.IO) { OrdinaryFileTrash.forContext(this@FileTrashActivity).entries() } } }
    private fun runOperation(action: () -> String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            message = withContext(Dispatchers.IO) { runCatching(action).getOrElse { it.message ?: "操作失败，未确认完成" } }
            entries = withContext(Dispatchers.IO) { OrdinaryFileTrash.forContext(this@FileTrashActivity).entries() }
            busy = false
        }
    }
}
