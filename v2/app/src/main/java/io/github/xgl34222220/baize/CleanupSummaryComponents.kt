package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
internal fun CurrentCleanupSummaryContent(apps: List<AppJunkUiItem>, junk: List<GeneralJunkUiItem>) {
    val context = LocalContext.current
    val totalBytes = apps.sumOf { it.bytes.coerceAtLeast(0L) } + junk.sumOf { it.bytes.coerceAtLeast(0L) }
    val totalFiles = apps.sumOf { it.files.coerceAtLeast(0L) } + junk.sumOf { it.files.coerceAtLeast(0L) }
    val totalErrors = apps.sumOf { it.errors.coerceAtLeast(0L) } + junk.sumOf { it.errors.coerceAtLeast(0L) }
    val largestApp = apps.maxByOrNull { it.bytes }
    val largestJunk = junk.maxByOrNull { it.bytes }
    val appWins = (largestApp?.bytes ?: -1L) >= (largestJunk?.bytes ?: -1L)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp))
            Text("本次结果", Modifier.padding(start = 7.dp), fontSize = 13.sp,
                lineHeight = 19.sp, fontWeight = FontWeight.Medium)
        }
        Text(Formatter.formatFileSize(context, totalBytes), Modifier.padding(top = 12.dp),
            style = BaiZeTokens.type.hero, color = MaterialTheme.colorScheme.onSurface)
        Text("按实际扫描或删除结果统计", Modifier.padding(top = 3.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(BaiZeTokens.colors.surfaceBase).padding(vertical = 13.dp)) {
            SummaryStat("${apps.size + junk.size}", "分类来源", Modifier.weight(1f))
            SummaryStat("$totalFiles", "文件项目", Modifier.weight(1f))
            SummaryStat("$totalErrors", "未处理", Modifier.weight(1f))
        }

        if (largestApp != null || largestJunk != null) {
            Spacer(Modifier.height(16.dp))
            Text("最大来源", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (appWins && largestApp != null) {
                    ApplicationIcon(largestApp.packageName, largestApp.label, Modifier.size(30.dp))
                    Text(
                        largestApp.label,
                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(Formatter.formatFileSize(context, largestApp.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                } else if (largestJunk != null) {
                    Icon(
                        if (largestJunk.name.contains("APK", true) || largestJunk.name.contains("安装包")) Icons.Rounded.InstallMobile else Icons.Rounded.DeleteSweep,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        friendlyGeneralName(largestJunk.name),
                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(Formatter.formatFileSize(context, largestJunk.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun GeneralJunkCardContent(item: GeneralJunkUiItem) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.name.contains("APK", true) || item.name.contains("安装包")) Icons.Rounded.InstallMobile else Icons.Rounded.DeleteSweep,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
        }
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(friendlyGeneralName(item.name), modifier = Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "${item.files} 个文件${if (item.errors > 0) " · ${item.errors} 个未处理" else ""}",
                color = if (item.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            if (item.samplePath.isNotBlank()) {
                Text(item.samplePath, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun friendlyGeneralName(raw: String): String = when {
    raw.contains("APK", true) || raw.contains("安装包") -> "APK 安装包"
    raw.contains("安装临时") -> "安装临时文件"
    raw.contains("碎片") -> "残留碎片"
    raw.contains("DropBox") -> "系统诊断日志"
    raw.contains("日志") -> raw.take(16)
    else -> raw.take(18)
}
