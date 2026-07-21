from pathlib import Path

root = Path(__file__).resolve().parents[1]


def rep(path: str, old: str, new: str, count: int = 1) -> None:
    target = root / path
    text = target.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing anchor: {path}: {old[:100]!r}")
    target.write_text(text.replace(old, new, count))


app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
rep(app, "import io.github.xgl34222220.baize.ui.logs.LogsRoute\n", "")
rep(app, '    Logs("日志", Icons.Rounded.Description),\n', "")
rep(app, '                            BaiZePage.Logs -> LogsRoute(UiStyle.MATERIAL, state.forLogsPage(), actions) { page = BaiZePage.Records }\n', "")
rep(app, '                                BaiZePage.Logs -> LogsRoute(UiStyle.MIUIX, state.forLogsPage(), actions) { page = BaiZePage.Records }\n', "")
rep(
    app,
    '''    val rawLogName: String = "",
    val rawLog: String = "",
    val history: List<HistoryUiItem> = emptyList(),
''',
    '''    val rawLogName: String = "",
    val rawLog: String = "",
    val lastTaskTime: String = "",
    val protectedItems: List<ProtectedUiItem> = emptyList(),
    val history: List<HistoryUiItem> = emptyList(),
'''
)
rep(
    app,
    '''data class GeneralJunkUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

@Immutable
data class HistoryUiItem(
''',
    '''data class GeneralJunkUiItem(
    val name: String,
    val files: Long,
    val bytes: Long,
    val errors: Long,
    val samplePath: String
)

@Immutable
data class ProtectedUiItem(
    val id: String,
    val category: String,
    val path: String,
    val reason: String,
    val risk: String,
    val selectable: Boolean
)

@Immutable
data class HistoryUiItem(
'''
)
rep(
    app,
    '''    val clearRawLog: () -> Unit,
    val whitelist: () -> Unit,
''',
    '''    val clearRawLog: () -> Unit,
    val reviewProtected: () -> Unit,
    val whitelist: () -> Unit,
'''
)

manifest = "v2/app/src/main/AndroidManifest.xml"
rep(
    manifest,
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />'
)
rep(
    manifest,
    '''        <activity
            android:name=".ProfileActivity"
''',
    '''        <activity
            android:name=".ProtectedReviewActivity"
            android:exported="false" />
        <activity
            android:name=".ProfileActivity"
'''
)

icon_file = root / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/common/AppPackageIcon.kt"
icon_file.write_text(r'''package io.github.xgl34222220.baize.ui.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@Composable
fun AppPackageIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    corner: Dp = 15.dp
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(PersistentAppIconStore.get(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { PersistentAppIconStore.load(context, packageName) }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(current.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(label.trim().firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun AppPackageIconPreloader(packageNames: List<String>) {
    val context = LocalContext.current.applicationContext
    val stable = packageNames.filter { it.isNotBlank() }.distinct().take(80)
    LaunchedEffect(stable) {
        withContext(Dispatchers.IO) { stable.forEach { PersistentAppIconStore.load(context, it) } }
    }
}

private object PersistentAppIconStore {
    private const val PX = 112
    private val memory = object : LruCache<String, Bitmap>(160) {}

    @Synchronized
    fun get(packageName: String): Bitmap? = memory.get(packageName)

    fun load(context: Context, packageName: String): Bitmap? {
        synchronized(this) { memory.get(packageName) }?.let { return it }
        val directory = File(context.filesDir, "baize-app-icons").apply { mkdirs() }
        val stableFile = File(directory, sha256(packageName) + ".png")
        val disk = runCatching { if (stableFile.isFile) BitmapFactory.decodeFile(stableFile.path) else null }.getOrNull()
        if (disk != null) {
            synchronized(this) { memory.put(packageName, disk) }
            return disk
        }
        val pm = context.packageManager
        val info = appInfo(pm, packageName) ?: return null
        val drawable = runCatching { info.loadIcon(pm).mutate() }.getOrNull() ?: return null
        val bitmap = runCatching {
            Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888).also { target ->
                drawable.setBounds(0, 0, PX, PX)
                drawable.draw(Canvas(target))
            }
        }.getOrNull() ?: return null
        synchronized(this) { memory.put(packageName, bitmap) }
        runCatching {
            val temporary = File(directory, stableFile.name + ".tmp")
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(stableFile)) {
                temporary.copyTo(stableFile, overwrite = true)
                temporary.delete()
            }
        }
        return bitmap
    }

    @Suppress("DEPRECATION")
    private fun appInfo(pm: PackageManager, packageName: String): ApplicationInfo? = runCatching {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags))
        } else {
            pm.getApplicationInfo(packageName, flags.toInt())
        }
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
''')

contract = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/HistoryContract.kt"
rep(contract, "import io.github.xgl34222220.baize.HistoryUiItem\n", "import io.github.xgl34222220.baize.HistoryUiItem\nimport io.github.xgl34222220.baize.ProtectedUiItem\n")
rep(contract, "    val latestResult: String,\n    val lifetimeRuns: Long,\n", "    val latestResult: String,\n    val lastTaskTime: String,\n    val lifetimeRuns: Long,\n")
rep(contract, "    val recentJunk: List<GeneralJunkUiItem>,\n    val records: List<HistoryUiItem>\n", "    val recentJunk: List<GeneralJunkUiItem>,\n    val protectedItems: List<ProtectedUiItem>,\n    val records: List<HistoryUiItem>\n")
rep(contract, "    val onClearHistory: () -> Unit\n", "    val onClearHistory: () -> Unit,\n    val onReviewProtected: () -> Unit\n")
rep(contract, "    latestResult = history.firstOrNull()?.result.orEmpty(),\n    lifetimeRuns = lifetimeRuns,\n", "    latestResult = history.firstOrNull()?.result.orEmpty(),\n    lastTaskTime = lastTaskTime.ifBlank { history.firstOrNull()?.time.orEmpty() },\n    lifetimeRuns = lifetimeRuns,\n")
rep(contract, "    recentJunk = recentJunk,\n    records = history\n", "    recentJunk = recentJunk,\n    protectedItems = protectedItems,\n    records = history\n")

route = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/HistoryRoute.kt"
rep(route, "        onClearHistory = dashboardActions.clearHistory\n", "        onClearHistory = dashboardActions.clearHistory,\n        onReviewProtected = dashboardActions.reviewProtected\n")

screen = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/miuix/HistoryScreenMiuix.kt"
rep(screen, "import androidx.compose.material.icons.rounded.History\n", "import androidx.compose.material.icons.rounded.History\nimport androidx.compose.material.icons.rounded.Lock\nimport androidx.compose.material.icons.rounded.Shield\n")
rep(screen, "import androidx.compose.material3.HorizontalDivider\n", "import androidx.compose.material3.Button\nimport androidx.compose.material3.HorizontalDivider\n")
rep(screen, "import io.github.xgl34222220.baize.HistoryUiItem\n", "import io.github.xgl34222220.baize.HistoryUiItem\nimport io.github.xgl34222220.baize.ProtectedUiItem\n")
rep(screen, "import io.github.xgl34222220.baize.ui.common.AppPackageIcon\n", "import io.github.xgl34222220.baize.ui.common.AppPackageIcon\nimport io.github.xgl34222220.baize.ui.common.AppPackageIconPreloader\n")
rep(screen, "    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()\n\n    LazyColumn(\n", "    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()\n    AppPackageIconPreloader(state.recentApps.map { it.packageName })\n\n    LazyColumn(\n")
rep(
    screen,
    '''            if (state.recentJunk.isNotEmpty()) {
                item(key = "junk-title", contentType = "title") {
                    SectionTitle("OTHER JUNK", "其他垃圾", "安装包、日志、临时文件与碎片")
                }
                items(
                    items = state.recentJunk,
                    key = { "junk-${it.name}-${it.samplePath}" },
                    contentType = { "junk-result" }
                ) { GeneralResultCard(it) }
            }
        }

        item(key = "records-title", contentType = "title") {
''',
    '''            if (state.recentJunk.isNotEmpty()) {
                item(key = "junk-title", contentType = "title") {
                    SectionTitle("OTHER JUNK", "其他垃圾", "安装包、日志、临时文件与碎片")
                }
                items(
                    items = state.recentJunk,
                    key = { "junk-${it.name}-${it.samplePath}" },
                    contentType = { "junk-result" }
                ) { GeneralResultCard(it) }
            }
        }

        if (state.protectedItems.isNotEmpty()) {
            item(key = "protected-title", contentType = "title") {
                SectionTitle("PROTECTED REVIEW", "异常与受保护项目", "展示准确路径和原因；可复查项目由用户手动选择")
            }
            items(
                items = state.protectedItems,
                key = { "protected-${it.id}-${it.path}" },
                contentType = { "protected-result" }
            ) { ProtectedResultCard(it) }
            item(key = "protected-action", contentType = "action") {
                Button(
                    onClick = actions.onReviewProtected,
                    modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("重新扫描并手动选择可清理项目", fontWeight = FontWeight.Bold)
                }
            }
        }

        item(key = "records-title", contentType = "title") {
'''
)
rep(
    screen,
    '''                Text("本次结果", fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text(
''',
    '''                Text("本次结果", fontSize = 17.sp, fontWeight = FontWeight.Black)
                if (state.lastTaskTime.isNotBlank()) {
                    Text("执行时间 ${state.lastTaskTime}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(
'''
)
rep(
    screen,
    '''@Composable
private fun RecordCard(item: HistoryUiItem) {
''',
    '''@Composable
private fun ProtectedResultCard(item: ProtectedUiItem) {
    val tint = if (item.selectable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    GroupSurface {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (item.selectable) Icons.Rounded.Shield else Icons.Rounded.Lock, null, tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.category.ifBlank { "受保护项目" }, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(item.reason.ifBlank { "未提供保护原因" }, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    item.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 14.sp
                )
            }
            Text(if (item.selectable) "可复查" else "硬保护", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun RecordCard(item: HistoryUiItem) {
'''
)

print("v2.2.3 UI and records patch applied")
