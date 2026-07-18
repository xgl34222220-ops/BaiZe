package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppJunkCardContent(item: AppJunkUiItem) {
    val context = LocalContext.current
    var expanded by rememberSaveable(item.packageName) { mutableStateOf(false) }
    val categories = item.categories
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = categories.isNotEmpty()) { expanded = !expanded }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApplicationIcon(item.packageName, item.label, Modifier.size(50.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        modifier = Modifier.weight(1f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        Formatter.formatFileSize(context, item.bytes),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }
                Text(
                    item.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                CategoryChipRow(categories, item.category)
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${item.files} 个文件${if (item.errors > 0) " · ${item.errors} 个未清理" else ""}",
                        modifier = Modifier.weight(1f),
                        color = if (item.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    if (categories.isNotEmpty()) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            if (expanded) "收起明细" else "展开明细",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded && categories.isNotEmpty()) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 14.dp, bottom = 7.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
                )
                categories.forEach { CategoryDetailRow(it) }
            }
        }
    }
}

@Composable
private fun CategoryChipRow(categories: List<AppJunkCategoryUiItem>, fallback: String) {
    val labels = if (categories.isNotEmpty()) {
        categories.map { friendlyCategory(it.name) }.distinct()
    } else {
        fallback.split('、', ',', '，').map { friendlyCategory(it) }.filter { it.isNotBlank() }.distinct()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.take(3).forEach { CategoryChip(it) }
        if (labels.size > 3) CategoryChip("+${labels.size - 3}")
    }
}

@Composable
private fun CategoryChip(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CategoryDetailRow(category: AppJunkCategoryUiItem) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(friendlyCategory(category.name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${category.files} 个文件${if (category.errors > 0) " · ${category.errors} 个未清理" else ""}",
                color = if (category.errors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            if (category.samplePath.isNotBlank()) {
                Text(
                    category.samplePath,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            Formatter.formatFileSize(context, category.bytes),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun friendlyCategory(raw: String): String {
    val value = raw.substringBeforeLast(':', raw).trim()
    return when {
        value.contains("WebView", true) -> "WebView"
        value.contains("外部") && value.contains("缓存") -> "外部缓存"
        value.contains("code", true) -> "代码缓存"
        value.contains("内部") && value.contains("缓存") -> "内部缓存"
        value.contains("空文件") -> "空文件"
        value.contains("空目录") -> "空目录"
        value.contains("日志") -> "应用日志"
        value.contains("扩展规则") -> "扩展缓存"
        value.contains("缓存") -> "应用缓存"
        value.isBlank() -> "应用垃圾"
        else -> value.take(12)
    }
}
