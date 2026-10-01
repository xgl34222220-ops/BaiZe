package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.root.WhitelistFileClient
import io.github.xgl34222220.baize.ui.appearance.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@androidx.annotation.Keep
internal class WhitelistViewModel : ViewModel() {
    var state by mutableStateOf(WhitelistUiState())

    fun save(message: String, addingPath: Boolean = false, reload: () -> WhitelistProtectionSnapshot,
        operation: () -> Pair<WhitelistProtectionSnapshot, String>) {
        if (state.saving) return
        state = state.copy(saving = true, addingPath = addingPath, pathSaveError = "", message = message)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { operation() } }
            result.onSuccess { (snapshot, message) ->
                val packages = snapshot.effective.packages
                state = state.withProtection(snapshot).copy(saving = false, addingPath = false,
                    packagesLoaded = true, pathsLoaded = true, draft = WhitelistDraft(packages, packages),
                    pathSaveRevision = state.pathSaveRevision + if (addingPath) 1 else 0,
                    pathSaveError = "", message = message)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // Read back after an uncertain save; never automatically retry a mutation.
                val latest = withContext(Dispatchers.IO) { runCatching(reload).getOrNull() }
                val message = "修改未确认，请刷新核对；其它保护继续保留。${error.message.orEmpty()}"
                state = (latest?.let { state.withProtection(it).copy(
                    draft = WhitelistDraft(it.effective.packages, it.effective.packages)) } ?: state)
                    .copy(saving = false, addingPath = false, packagesLoaded = latest != null, pathsLoaded = latest != null,
                        pathSaveError = if (addingPath) message else "", message = message)
            }
        }
    }
}

/** Application and explicit-path protection share a discoverable, reversible management page. */
class WhitelistActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val model: WhitelistViewModel by viewModels()
    private var state: WhitelistUiState
        get() = model.state
        set(value) { model.state = value }
    private var service: IProfileRootService? = null
    private var bindingRequested = false
    private var loadGeneration = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = RootServiceClients.profile(binder, applicationContext.cacheDir)
            state = state.copy(connected = true)
            load()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            loadGeneration++
            service = null
            bindingRequested = false
            state = state.copy(connected = false, loading = false,
                message = "Root 服务已断开；未保存的选择已保留，请重新连接。")
        }
        override fun onNullBinding(name: ComponentName?) {
            runCatching { RootService.unbind(this) }
            onServiceDisconnected(name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = state.copy(connected = false, loading = false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val settings = appearance.settings.collectAsStateWithLifecycle().value
            val systemDark = isSystemInDarkTheme()
            val dark = settings.themeMode == ThemeMode.DARK || settings.themeMode == ThemeMode.SYSTEM && systemDark
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BaiZeTheme(settings) {
                CompositionLocalProvider(LocalAppearanceSettings provides settings) {
                    WhitelistManagerScreen(state,
                        onBack = ::finish, onRefresh = { if (service == null) connect() else load() },
                        onToggle = ::toggleAppProtection,
                        onClearApps = ::clearAppProtection,
                        onRemovePath = ::removePath, onAddPath = ::addPath)
                }
            }
        }
        connect()
    }

    private fun canEditApps() = state.connected && state.packagesLoaded && !state.loading && !state.saving

    private fun connect() {
        if (bindingRequested) return
        bindingRequested = true
        state = state.copy(message = "正在连接 Root 服务…")
        runCatching {
            RootService.bind(Intent(this, BaiZeProfileRootService::class.java)
                .addCategory(RootService.CATEGORY_DAEMON_MODE), connection)
        }.onFailure {
            bindingRequested = false
            state = state.copy(connected = false, message = "Root 连接失败：${it.message}。请确认模块已安装并授权。")
        }
    }

    private fun load() {
        val remote = service ?: return
        if (state.saving) return
        val generation = ++loadGeneration
        state = state.copy(loading = true, message = "正在读取应用与路径白名单…")
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                // A failed read is not an empty whitelist and can never authorize a save.
                val snapshot = manager(remote).read()
                val packages = snapshot.effective.packages
                val catalog = runCatching { JSONObject(remote.getInstalledPackageCatalog()).getJSONArray("packages") }.getOrNull()
                val entries = linkedMapOf<String, Boolean>()
                if (catalog != null) for (i in 0 until catalog.length()) {
                    val row = catalog.getJSONObject(i)
                    entries[row.getString("packageName")] = row.optBoolean("system")
                }
                if (entries.isEmpty()) {
                    @Suppress("DEPRECATION")
                    packageManager.getInstalledApplications(0).forEach { info ->
                        entries[info.packageName] = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    }
                }
                // Include protected packages even when uninstalled or not visible in the catalog.
                packages.forEach { entries.putIfAbsent(it, false) }
                val apps = entries.map { (pkg, system) ->
                    val label = runCatching {
                        val info = if (Build.VERSION.SDK_INT >= 33)
                            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                        else { @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0) }
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(pkg)
                    WhitelistApp(pkg, label, system)
                }.sortedWith(compareBy<WhitelistApp> { it.system }.thenBy { it.label.lowercase() }.thenBy { it.packageName })
                snapshot to apps
            } }
            if (generation != loadGeneration || service !== remote) return@launch
            result.onSuccess { (snapshot, apps) ->
                val packages = snapshot.effective.packages
                val draft = if (state.draft.dirty) state.draft.rebase(packages) else WhitelistDraft(packages, packages)
                state = state.withProtection(snapshot).copy(loading = false, packagesLoaded = true, pathsLoaded = true,
                    apps = apps, draft = draft,
                    message = "已保护 ${packages.size} 个应用、${snapshot.pathEntries.size} 个路径。包含旧版设置，修改后请重新扫描核对。")
            }.onFailure {
                if (it is CancellationException) throw it
                state = state.copy(loading = false, packagesLoaded = false, pathsLoaded = false,
                    message = "白名单读取失败，已禁止写入，原保护不变：${it.message}")
            }
        }
    }

    private fun toggleAppProtection(packageName: String) {
        if (!canEditApps()) return
        saveApps(state.draft.toggle(packageName))
    }

    private fun clearAppProtection() {
        if (!canEditApps() || state.draft.selected.isEmpty()) return
        saveApps(state.draft.copy(selected = emptySet()))
    }

    private fun saveApps(draft: WhitelistDraft) {
        val remote = service ?: return
        if (!canEditApps() || !draft.dirty) return
        val repository = manager(remote)
        state = state.copy(draft = draft)
        model.save("正在保存应用保护…", reload = repository::read) {
            repository.updatePackages(draft.added, draft.removed) to "应用保护已保存；重新扫描后核对新的保护范围。"
        }
    }

    private fun removePath(path: String) {
        val remote = service ?: return
        if (!state.pathsLoaded || state.loading || state.saving || path !in state.paths) return
        val repository = manager(remote)
        model.save("正在移除此路径保护…", reload = repository::read) {
            repository.removePath(path) to "已移除此路径保护；父目录、子目录和应用保护仍按各自记录生效，文件不变。"
        }
    }

    private fun addPath(path: String) {
        val remote = service ?: return
        if (!state.pathsLoaded || state.loading || state.saving) return
        val repository = manager(remote)
        model.save("正在添加路径保护…", addingPath = true, reload = repository::read) {
            val result = repository.addPath(path)
            result.snapshot to if (result.alreadyProtected) "此路径已有保护，已合并显示相同位置的记录。"
                else "路径保护已保存；该文件或目录内全部子项都会保留。"
        }
    }

    private fun manager(remote: IProfileRootService): WhitelistManagerRepository {
        val context = applicationContext
        @Suppress("DEPRECATION")
        val primary = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
        return WhitelistManagerRepository(context, object : WhitelistProtectionAccess {
            override fun read() = ApkProtectionStore.readRoot(requireNotNull(ApkProtectionStore.source(context, remote)))
            override fun updatePackages(added: Set<String>, removed: Set<String>) {
                requireSuccess(WhitelistFileClient.updatePackages(remote, context.cacheDir, added, removed))
            }
            override fun addPath(path: String) { requireSuccess(remote.addWhitelistPath(path)) }
            override fun removePath(path: String) { requireSuccess(WhitelistFileClient.removePath(remote, context.cacheDir, path)) }
        }, primary)
    }

    companion object {
        private fun requireSuccess(raw: String): JSONObject = JSONObject(raw).also {
            check(it.optBoolean("success")) { it.optString("message", it.optString("error", "请求未确认")) }
        }
    }

    override fun onDestroy() {
        loadGeneration++
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
