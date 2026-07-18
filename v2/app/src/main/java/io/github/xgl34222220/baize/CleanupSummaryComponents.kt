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
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.padding(start = 13.dp).weight(1f)) {
                Text("本次结果", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("全部按实际扫描或删除结果统计", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Text(
                Formatter.formatFileSize(context, totalBytes),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Black
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryStat("${apps.size + junk.size}", "分类来源")
            SummaryStat("$totalFiles", "文件项目")
            SummaryStat("$totalErrors", "未处理")
        }

        if (largestApp != null || largestJunk != null) {
            Spacer(Modifier.height(16.dp))
            Text("最大来源", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (appWins && largestApp != null) {
                    ApplicationIcon(largestApp.packageName, largestApp.label, Modifier.size(30.dp))
                    Text(
                        largestApp.label,
                        modifier = Modifier.padding(start = 9.dp).weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(Formatter.formatFileSize(context, largestApp.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(Formatter.formatFileSize(context, largestJunk.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.name.contains("APK", true) || item.name.contains("安装包")) Icons.Rounded.InstallMobile else Icons.Rounded.DeleteSweep,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(25.dp)
            )
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(friendlyGeneralName(item.name), modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            Text(
                "${item.files} 个文件${if (item.errors > 0) " · ${item.errors} 个未处理" else ""}",
                color = if (item.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            if (item.samplePath.isNotBlank()) {
                Text(item.samplePath, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
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
