package io.github.xgl34222220.baize

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.components.DetailResultRow
import io.github.xgl34222220.baize.ui.components.DetailSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ReviewSourceQuery {
    /** 轻量 MediaStore 查询（只取路径、大小、时间、类型），上限 12 万项。 */
    @Suppress("DEPRECATION")
    fun query(context: Context): List<StorageFileRecord> {
        if (!SharedStorageAccess.granted(context)) return emptyList()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE)
        val out = ArrayList<StorageFileRecord>()
        context.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { c ->
            val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA); val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE); val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE); val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (c.moveToNext() && out.size < 120_000) {
                val p = c.getString(path).orEmpty()
                out += StorageFileRecord(c.getLong(id), "", p, c.getString(name).orEmpty().ifBlank { p.substringAfterLast('/') },
                    c.getLong(size), c.getLong(modified), c.getString(mime).orEmpty())
            }
        }
        return out
    }
}

internal fun reviewSourceIntent(context: Context, source: ReviewSource): Intent = when (source) {
    ReviewSource.CORPSES -> Intent(context, ScanWorkbenchActivity::class.java).putExtra(ScanWorkbenchActivity.EXTRA_PROFILE, "corpses")
    ReviewSource.APK -> Intent(context, ApkScanActivity::class.java)
    else -> StorageToolsActivity.intent(context, requireNotNull(source.mode))
}

private fun reviewIcon(source: ReviewSource): ImageVector = when (source) {
    ReviewSource.CORPSES -> Icons.Rounded.FolderDelete; ReviewSource.APK -> Icons.Rounded.InstallMobile
    ReviewSource.SCREENSHOTS -> Icons.Rounded.Screenshot; ReviewSource.OLD_DOWNLOADS -> Icons.Rounded.Download
    ReviewSource.CHAT_MEDIA -> Icons.Rounded.Forum; ReviewSource.DUPLICATES -> Icons.Rounded.ContentCopy
    ReviewSource.ROOT -> Icons.Rounded.FolderOpen
}

/** 扫描结束后在结果列表下方显示；估算在 IO 线程进行，失败时只显示说明，不影响清理。 */
@Composable
internal fun WorkbenchReviewSources(visible: Boolean, refreshKey: Any?) {
    if (!visible) return
    val context = LocalContext.current
    val estimates by produceState(emptyMap<ReviewSource, ReviewEstimate>(), refreshKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { ReviewSourceEstimates.estimate(ReviewSourceQuery.query(context), System.currentTimeMillis() / 1000) }
                .getOrDefault(emptyMap())
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailSectionHeader("需要你复核", "默认不勾选；点开逐项确认，删除进入回收站可撤销")
        ReviewSource.entries.forEach { source ->
            val estimate = estimates[source]
            val value = when {
                estimate == null -> "打开查看"
                estimate.files == 0 -> "暂无"
                else -> "约 ${Formatter.formatFileSize(context, estimate.bytes)}"
            }
            DetailResultRow(source.title, value, if (estimate != null && estimate.files > 0) "${estimate.files} 项 · ${source.hint}" else source.hint,
                "", "", reviewIcon(source), first = true, last = true,
                onDetails = { runCatching { context.startActivity(reviewSourceIntent(context, source)) } })
            Spacer(Modifier.height(2.dp))
        }
    }
}
