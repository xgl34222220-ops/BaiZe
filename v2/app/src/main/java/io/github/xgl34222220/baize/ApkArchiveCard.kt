package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
internal fun ApkArchiveResultCard(item: ApkScanItem, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    var details by remember(item.previewKey) { mutableStateOf(false) }
    val context = LocalContext.current
    val archive = item.archive
    val size = Formatter.formatFileSize(context, item.bytes)
    val title = archive.appName.ifBlank { item.name }
    val tone = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .07f) else BaiZeTokens.colors.surfaceRaised
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp), color = tone) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "查看应用信息和文件详情") { details = true }
            .padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ArchiveArtwork(item, Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(archive.version.takeIf { it.isNotBlank() }?.let { "版本 $it" }, size)
                    .filterNotNull().joinToString(" · "), fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (title != item.name) Text(item.name, fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(archiveSummary(archive), fontSize = 11.sp, lineHeight = 16.sp,
                    color = if (archive.parseStatus == ApkArchiveParseStatus.FAILED) BaiZeTokens.colors.warning
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.retainedReason.isNotBlank()) Text(item.retainedReason,
                    fontSize = 12.sp, lineHeight = 18.sp, color = BaiZeTokens.colors.warning)
            }
            Checkbox(selected, { onToggle() }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "选择安装包${item.name}" })
        }
    }
    if (details) BaiZeDialog(onDismissRequest = { details = false }, title = { Text("安装包详情") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ArchiveArtwork(item, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
                    Text(archiveSummary(archive), fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("文件名\n${item.name}", fontSize = 13.sp, lineHeight = 20.sp)
                Text("文件大小\n$size", fontSize = 13.sp, lineHeight = 20.sp)
                if (archive.packageName.isNotBlank()) Text("应用包名\n${archive.packageName}", fontSize = 13.sp, lineHeight = 20.sp)
                if (archive.version.isNotBlank()) Text("安装包版本\n${archive.version}", fontSize = 13.sp, lineHeight = 20.sp)
                if (archive.installedVersion.isNotBlank()) Text("已装版本\n${archive.installedVersion}", fontSize = 13.sp, lineHeight = 20.sp)
                Text("完整路径\n${item.samplePath}", fontSize = 13.sp, lineHeight = 20.sp)
                if (item.retainedReason.isNotBlank()) Text("处理结果\n${item.retainedReason}", fontSize = 13.sp, lineHeight = 20.sp)
            } }
            Text("版本状态仅比较版本号，不代表签名兼容。删除安装包不会卸载已安装的应用。", fontSize = 12.sp, lineHeight = 18.sp)
        } }, confirmButton = { BaiZeDialogButton({ details = false }) { Text("完成") } })
}

@Composable
private fun ArchiveArtwork(item: ApkScanItem, modifier: Modifier) {
    val bitmap = item.archive.iconBitmap
    Surface(modifier.clip(RoundedCornerShape(13.dp)).testTag("apk-artwork:${item.uri}"),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .065f), shape = RoundedCornerShape(13.dp)) {
        if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = "来自安装包的应用图标", modifier = Modifier.fillMaxSize())
        else Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.InstallMobile, "安装包默认图标", Modifier.size(27.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun archiveSummary(archive: ApkArchiveInfo): String = when {
    archive.awaitingInspection -> "应用信息待识别"
    archive.parseStatus == ApkArchiveParseStatus.UNSUPPORTED -> "组合安装文件 · 使用默认图标"
    archive.parseStatus == ApkArchiveParseStatus.FAILED ->
        "使用默认图标 · ${archive.failureReason?.label ?: "暂时无法识别应用信息"}"
    archive.parseStatus == ApkArchiveParseStatus.PARTIAL ->
        "${archive.status.label} · ${archive.failureReason?.label ?: "部分应用信息暂不可用"}"
    else -> archive.status.label
}
