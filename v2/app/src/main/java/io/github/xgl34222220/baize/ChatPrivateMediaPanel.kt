package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.components.BaiZeChipButton
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.components.BaiZeProgress
import io.github.xgl34222220.baize.ui.components.BaiZeTintedIcon
import io.github.xgl34222220.baize.ui.components.DetailGlassPanel
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTones

internal class ChatPrivateActions(
    val onScan: () -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onToggleApp: (String) -> Unit = {},
    val onAge: (Int) -> Unit = {},
    val onClean: () -> Unit = {},
    val onStop: () -> Unit = {}
)

/**
 * 聊天媒体页 · 应用私有数据（Root）：按文件夹与时间档位（7 / 30 / 90 / 180 天+）展示微信 / QQ / TIM 私有目录的媒体占用。
 * 默认不勾选；可按应用分组勾选或逐个文件夹勾选；只在用户点按时扫描。
 */
@Composable
internal fun ChatPrivateMediaPanel(state: ChatPrivateState, actions: ChatPrivateActions, enabled: Boolean = true) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
    DetailGlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BaiZeTintedIcon(Icons.Rounded.Forum, BaiZeTones.green)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("应用私有数据（Root）", style = MaterialTheme.typography.titleMedium)
                Text(ChatPrivateMedia.KEEP_TEXT_COPY, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.message.isNotBlank()) Text(state.message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (state.busy) {
            Spacer(Modifier.height(10.dp))
            BaiZeProgress(progress = null)
        }
        if (state.folders.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatPrivateMedia.AGE_OPTIONS.forEach { days ->
                    BaiZeChipButton("$days 天+ · ${size(state.folders.sumOf { it.bytesOlder(days) })}", { actions.onAge(days) },
                        primary = state.olderThanDays == days, enabled = enabled && !state.busy)
                }
            }
            state.apps.forEach { app ->
                val folders = state.folders.filter { it.app == app }
                val candidates = folders.filter { it.filesOlder(state.olderThanDays) > 0 }
                val allChecked = candidates.isNotEmpty() && candidates.all { it.path in state.selected }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable(enabled = enabled && !state.busy && candidates.isNotEmpty(), role = Role.Checkbox) { actions.onToggleApp(app) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allChecked, onCheckedChange = null, enabled = enabled && !state.busy && candidates.isNotEmpty())
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(app, style = MaterialTheme.typography.titleSmall)
                        Text("超过 ${state.olderThanDays} 天 ${size(folders.sumOf { it.bytesOlder(state.olderThanDays) })} · 共 ${size(folders.sumOf { it.bytes })}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                folders.forEach { folder ->
                    val count = folder.filesOlder(state.olderThanDays)
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp)
                        .clickable(enabled = enabled && !state.busy && count > 0, role = Role.Checkbox) { actions.onToggle(folder.path) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = folder.path in state.selected && count > 0, onCheckedChange = null, enabled = enabled && !state.busy && count > 0)
                        Column(Modifier.weight(1f).padding(start = 6.dp, top = 4.dp, bottom = 4.dp)) {
                            Text(folder.title, style = MaterialTheme.typography.bodyMedium)
                            Text("超过 ${state.olderThanDays} 天：$count 个 · ${size(folder.bytesOlder(state.olderThanDays))}　共 ${size(folder.bytes)}" +
                                if (folder.recoverable) "" else " · 无法移入回收站", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(ChatPrivateMedia.AGE_OPTIONS.joinToString("　") { "$it 天+ ${size(folder.bytesOlder(it))}" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.cleaning -> GlassActionButton("停止", actions.onStop, Modifier.fillMaxWidth(), secondary = true)
            state.selectedFolders.isNotEmpty() -> GlassActionButton("清理所选 ${state.selectedFiles} 个 · ${size(state.selectedBytes)}",
                actions.onClean, Modifier.fillMaxWidth(), enabled = enabled && !state.busy)
            else -> GlassActionButton(if (state.scanned) "重新扫描私有数据" else "扫描微信 / QQ 私有数据", actions.onScan, Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Refresh, enabled = enabled && !state.busy, secondary = true)
        }
    }
}

/**
 * 确认弹层：列出将处理的文件夹与预估；可选先结束应用（am force-stop）；
 * 超出回收站上限或无法同分区移动的部分会永久删除，必须单独勾选确认，否则只移入回收站、其余保留。
 */
@Composable
internal fun ChatPrivateCleanDialog(state: ChatPrivateState, onConfirm: (forceStop: Boolean, allowPermanent: Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
    val plan = state.plan
    var forceStop by remember { mutableStateOf(false) }
    var allowPermanent by remember { mutableStateOf(false) }
    val apps = state.selectedFolders.map { it.app }.distinct()
    BaiZeDialog(onDismissRequest = onDismiss,
        title = { Text("清理 ${apps.joinToString(" / ")} 的媒体文件？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ChatPrivateMedia.KEEP_TEXT_COPY + "。数据库、索引与配置文件始终保留。")
                state.selectedFolders.forEach { folder ->
                    Text("· ${folder.app} ${folder.title}：超过 ${state.olderThanDays} 天 ${folder.filesOlder(state.olderThanDays)} 个，${size(folder.bytesOlder(state.olderThanDays))}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("约 ${plan.recoverableFiles} 个（${size(plan.recoverableBytes)}）移入回收站（隔离区），保留期内可恢复。" +
                    "回收站上限 ${QUARANTINE_LIMIT_TEXT}，当前剩余 ${state.entriesLeft} 项 / ${size(state.bytesLeft)}。")
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { forceStop = !forceStop }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forceStop, onCheckedChange = null)
                    Text("先结束 ${apps.joinToString(" / ")}（Root am force-stop），避免清理时文件正被写入", Modifier.padding(start = 6.dp))
                }
                if (plan.permanentFiles > 0) {
                    Text("约 ${plan.permanentFiles} 个（${size(plan.permanentBytes)}）超出回收站上限或无法移入回收站，将永久删除，无法恢复。",
                        color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { allowPermanent = !allowPermanent }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowPermanent, onCheckedChange = null)
                        Text("我已了解，超出部分永久删除", Modifier.padding(start = 6.dp))
                    }
                    if (!allowPermanent) Text("不勾选则只移入回收站，其余文件原样保留。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            BaiZeDialogButton(enabled = plan.totalFiles > 0 && (plan.recoverableFiles > 0 || allowPermanent),
                onClick = { onConfirm(forceStop, allowPermanent && plan.permanentFiles > 0) }) {
                Text(if (allowPermanent && plan.permanentFiles > 0) "确认清理（含永久删除）" else "移入回收站")
            }
        },
        dismissButton = { BaiZeDialogButton(onClick = onDismiss) { Text("取消") } })
}

private const val QUARANTINE_LIMIT_TEXT = "3000 项 / 16 GB"
