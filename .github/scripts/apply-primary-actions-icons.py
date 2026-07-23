from pathlib import Path

ROOT = Path('.')


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected text missing in {path}: {old[:100]!r}')
    file.write_text(text.replace(old, new, 1), encoding='utf-8')


icon_path = ROOT / 'v2/app/src/main/java/io/github/xgl34222220/baize/ui/common/AppPackageIcon.kt'
icon_path.write_text('''package io.github.xgl34222220.baize.ui.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.AtomicFile
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
import androidx.compose.runtime.remember
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
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Composable
fun AppPackageIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    corner: Dp = 15.dp
) {
    val context = LocalContext.current.applicationContext
    val stablePackage = remember(packageName) { packageName.trim() }
    val bitmap by produceState<Bitmap?>(PersistentAppIconStore.memory(stablePackage), stablePackage) {
        value = withContext(Dispatchers.IO) { PersistentAppIconStore.load(context, stablePackage) }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null && !current.isRecycled) {
            Image(current.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(label.trim().firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AppPackageIconPreloader(packageNames: List<String>) {
    val context = LocalContext.current.applicationContext
    val stable = remember(packageNames) {
        packageNames.map(String::trim).filter(String::isNotBlank).distinct().take(160)
    }
    LaunchedEffect(stable) {
        withContext(Dispatchers.IO) { stable.forEach { PersistentAppIconStore.load(context, it) } }
    }
}

private object PersistentAppIconStore {
    private const val PX = 128
    private val memoryCache = object : LruCache<String, Bitmap>(192) {}
    private val packageLocks = ConcurrentHashMap<String, Any>()

    @Synchronized
    fun memory(packageName: String): Bitmap? = memoryCache.get(packageName)

    fun load(context: Context, packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        synchronized(this) { memoryCache.get(packageName) }?.let { return it }
        val lock = packageLocks.getOrPut(packageName) { Any() }
        return synchronized(lock) {
            synchronized(this) { memoryCache.get(packageName) }?.let { return@synchronized it }

            val fileName = sha256(packageName) + ".png"
            val persistentDirectory = File(context.noBackupFilesDir, "baize-app-icons-v2").apply { mkdirs() }
            val persistentFile = File(persistentDirectory, fileName)
            val legacyFile = File(File(context.filesDir, "baize-app-icons"), fileName)

            decode(persistentFile)?.let { return@synchronized remember(packageName, it) }
            decode(legacyFile)?.let { legacy ->
                writeAtomic(persistentFile, legacy)
                return@synchronized remember(packageName, legacy)
            }

            val pm = context.packageManager
            val info = appInfo(pm, packageName) ?: return@synchronized null
            val drawable = runCatching { info.loadIcon(pm).mutate() }.getOrNull() ?: return@synchronized null
            val bitmap = runCatching {
                Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = Canvas(target)
                    drawable.setBounds(0, 0, PX, PX)
                    drawable.draw(canvas)
                }
            }.getOrNull() ?: return@synchronized null

            remember(packageName, bitmap)
            writeAtomic(persistentFile, bitmap)
            bitmap
        }.also { packageLocks.remove(packageName, lock) }
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (decoded == null) runCatching { file.delete() }
        return decoded
    }

    @Synchronized
    private fun remember(packageName: String, bitmap: Bitmap): Bitmap {
        memoryCache.put(packageName, bitmap)
        return bitmap
    }

    private fun writeAtomic(target: File, bitmap: Bitmap) {
        runCatching {
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            var output: FileOutputStream? = null
            try {
                output = atomic.startWrite()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
                atomic.finishWrite(output)
                output = null
            } finally {
                output?.let(atomic::failWrite)
            }
        }
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
''', encoding='utf-8')

app_path = 'v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt'
replace_once(app_path, '    val clean: () -> Unit,\n    val scan: () -> Unit,', '    val clean: () -> Unit,\n    val organize: () -> Unit,\n    val scan: () -> Unit,')

activity_path = 'v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt'
replace_once(activity_path, '                    clean = { runSmartClean() },\n                    scan = { runNativeScan(cleanAfterScan = false) },', '                    clean = { runSmartClean() },\n                    organize = { runOneTapOrganize() },\n                    scan = { runNativeScan(cleanAfterScan = false) },')
replace_once(activity_path, '''        val mode = pendingModuleTask
        if (mode != null && rootService != null) {
            pendingModuleTask = null
            runModuleUtilityTask(requireNotNull(rootService), mode)
        }
''', '''        val mode = pendingModuleTask
        if (mode != null && rootService != null) {
            pendingModuleTask = null
            if (mode == "organize") {
                runDetachedOrganizer(requireNotNull(rootService))
            } else {
                runModuleUtilityTask(requireNotNull(rootService), mode)
            }
        }
''')
replace_once(activity_path, '''    private fun runApkScan() {
''', '''    private fun runOneTapOrganize() {
        if (dashboardState.value.running) {
            showTaskBusy("当前已有任务正在运行，请等待完成后再归类")
            return
        }
        val service = rootService
        if (service == null) {
            pendingModuleTask = "organize"
            dashboardState.value = dashboardState.value.copy(
                connected = false,
                ready = false,
                taskPhase = "正在连接 Root 服务，连接后自动开始文件归类"
            )
            connectPrimaryService()
            return
        }
        runDetachedOrganizer(service)
    }

    private fun runDetachedOrganizer(service: IProfileRootService) {
        if (dashboardState.value.running) {
            showTaskBusy("当前已有任务正在运行，请等待完成后再归类")
            return
        }
        dashboardState.value = dashboardState.value.copy(
            running = true,
            scanCompleted = false,
            taskPhase = "正在把文件归类交给独立 Root Worker…"
        )
        startNativePoll()
        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching { JSONObject(service.runModuleTask("organize")) }
            }
            pollJob?.cancel()
            if (response.isFailure) {
                rootService = null
                profileBound = false
                dashboardState.value = dashboardState.value.copy(
                    connected = false,
                    ready = false,
                    running = false,
                    serviceText = "Root 服务已断开，正在重新连接…",
                    taskPhase = "文件归类启动失败：${response.exceptionOrNull()?.message ?: "Root 服务异常"}"
                )
                connectPrimaryService()
                return@launch
            }
            val json = response.getOrThrow()
            if (json.optString("error") == "busy" || json.optInt("exitCode") == 3) {
                val message = json.optString("message", "当前已有任务正在运行")
                dashboardState.value = dashboardState.value.copy(running = false, taskPhase = message)
                toast(message)
                return@launch
            }
            if (json.optBoolean("accepted")) {
                dashboardState.value = dashboardState.value.copy(
                    running = true,
                    scanCompleted = false,
                    serviceText = "独立 Root Worker 已接管文件归类，关闭 App 也会继续",
                    taskPhase = json.optString("message", "文件归类已在后台启动")
                )
                startRecoveredTaskPoll()
                return@launch
            }
            updateRawLogFromResponse(json)
            dashboardState.value = dashboardState.value.copy(
                running = false,
                taskPhase = json.optString("message").ifBlank {
                    if (json.optBoolean("success")) "文件归类完成" else "文件归类未完成"
                }
            )
            refreshHistory()
            refreshModuleState()
            readServiceStatus()
        }
    }

    private fun runApkScan() {
''')

material_path = 'v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt'
replace_once(material_path, '''        item {
            MaterialNextTaskCard(
                task = nextTask,
                countdown = taskCountdownLabel(nextTask, nowEpoch),
                enabled = scheduler.enabled,
                onClick = onOpenClean
            )
        }
        item { MaterialSectionHeader("任务计划", "每项任务独立显示下一次执行时间", onOpenClean) }
''', '''        item {
            MaterialNextTaskCard(
                task = nextTask,
                countdown = taskCountdownLabel(nextTask, nowEpoch),
                enabled = scheduler.enabled,
                onClick = onOpenClean
            )
        }
        item {
            MaterialPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        item { MaterialSectionHeader("任务计划", "每项任务独立显示下一次执行时间", onOpenClean) }
''')
replace_once(material_path, '''@Composable
private fun MaterialSectionHeader(
''', '''@Composable
private fun MaterialPrimaryActions(
    enabled: Boolean,
    onClean: () -> Unit,
    onOrganize: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MaterialActionButton(
            title = "一键清理",
            subtitle = "按当前规则立即执行",
            icon = Icons.Rounded.CleaningServices,
            primary = true,
            enabled = enabled,
            onClick = onClean,
            modifier = Modifier.weight(1f)
        )
        MaterialActionButton(
            title = "一键归类",
            subtitle = "整理明确的用户文件",
            icon = Icons.Rounded.FolderCopy,
            primary = false,
            enabled = enabled,
            onClick = onOrganize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MaterialActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(82.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 10.sp, maxLines = 2, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun MaterialSectionHeader(
''')

miuix_path = 'v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/miuix/HomeScreenMiuix.kt'
replace_once(miuix_path, '''        item { MiuixHomeHeader(state, scheduler.enabled, actions.refresh) }
        item { MiuixNextTaskPanel(nextTask, nowEpoch, scheduler.enabled, onOpenClean) }
        item { MiuixSectionTitle("任务计划", "所有任务按条件自动运行") }
''', '''        item { MiuixHomeHeader(state, scheduler.enabled, actions.refresh) }
        item { MiuixNextTaskPanel(nextTask, nowEpoch, scheduler.enabled, onOpenClean) }
        item {
            MiuixPrimaryActions(
                enabled = state.ready && !state.running,
                onClean = actions.clean,
                onOrganize = actions.organize
            )
        }
        item { MiuixSectionTitle("任务计划", "所有任务按条件自动运行") }
''')
replace_once(miuix_path, '''@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
''', '''@Composable
private fun MiuixPrimaryActions(
    enabled: Boolean,
    onClean: () -> Unit,
    onOrganize: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = BaiZeTokens.colors.surfaceRaised
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MiuixActionButton(
                title = "一键清理",
                subtitle = "立即执行清理规则",
                icon = Icons.Rounded.CleaningServices,
                primary = true,
                enabled = enabled,
                onClick = onClean,
                modifier = Modifier.weight(1f)
            )
            MiuixActionButton(
                title = "一键归类",
                subtitle = "整理用户下载文件",
                icon = Icons.Rounded.FolderCopy,
                primary = false,
                enabled = enabled,
                onClick = onOrganize,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiuixActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fill = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .10f)
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(17.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (enabled) fill else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f),
        contentColor = if (enabled) content else MaterialTheme.colorScheme.outline
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun MiuixSectionTitle(title: String, subtitle: String) {
''')

for history_path in [
    'v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/miuix/HistoryScreenMiuix.kt',
    'v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/material/HistoryScreenMaterial.kt',
]:
    replace_once(history_path, 'import io.github.xgl34222220.baize.ui.common.AppPackageIcon\n', 'import io.github.xgl34222220.baize.ui.common.AppPackageIcon\nimport io.github.xgl34222220.baize.ui.common.AppPackageIconPreloader\n')
    signature = 'fun HistoryScreenMiuix(state: HistoryUiState, actions: HistoryUiActions) {\n    val bottomInset' if 'miuix' in history_path else 'fun HistoryScreenMaterial(state: HistoryUiState, actions: HistoryUiActions) {\n    val bottomInset'
    replacement = signature.split('\n    val bottomInset')[0] + '''
    val iconPackages = buildList {
        addAll(state.recentApps.map { it.packageName })
        state.records.forEach { record -> addAll(record.apps.map { it.packageName }) }
    }
    AppPackageIconPreloader(iconPackages)
    val bottomInset'''
    replace_once(history_path, signature, replacement)

print('primary actions and persistent icon fixes applied')
