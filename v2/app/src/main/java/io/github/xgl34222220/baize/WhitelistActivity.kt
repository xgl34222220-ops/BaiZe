package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import org.json.JSONArray
import org.json.JSONObject

@androidx.annotation.Keep
internal class WhitelistViewModel : ViewModel() {
    var state by mutableStateOf(WhitelistUiState())
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
            state = state.copy(connected = false, loading = false, saving = false,
                message = "Root 服务已断开；未保存的选择已保留，请重新连接。")
        }
        override fun onNullBinding(name: ComponentName?) {
            runCatching { RootService.unbind(this) }
            onServiceDisconnected(name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = state.copy(connected = false, loading = false, saving = false)
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
                        onToggle = { pkg -> if (canEditApps()) state = state.copy(draft = state.draft.toggle(pkg)) },
                        onClearApps = { if (canEditApps()) state = state.copy(draft = state.draft.copy(selected = emptySet())) },
                        onSaveApps = ::saveApps, onRemovePath = ::removePath)
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
                val packages = stringSet(remote.getWhitelistPackages())
                val paths = stringSet(remote.getWhitelistPaths()).sorted()
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
                Triple(packages, paths, apps)
            } }
            if (generation != loadGeneration || service !== remote) return@launch
            result.onSuccess { (packages, paths, apps) ->
                val draft = if (state.draft.dirty) state.draft.rebase(packages) else WhitelistDraft(packages, packages)
                state = state.copy(loading = false, packagesLoaded = true, pathsLoaded = true,
                    apps = apps, paths = paths, draft = draft,
                    message = "已保存 ${packages.size} 个应用、${paths.size} 条手动路径保护。修改后需重新扫描。")
            }.onFailure {
                if (it is CancellationException) throw it
                state = state.copy(loading = false, packagesLoaded = false, pathsLoaded = false,
                    message = "白名单读取失败，已禁止写入，原保护不变：${it.message}")
            }
        }
    }

    private fun saveApps() {
        val remote = service ?: return
        if (!canEditApps() || !state.draft.dirty) return
        val draft = state.draft
        state = state.copy(saving = true, message = "正在保存应用白名单…")
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                requireSuccess(WhitelistFileClient.updatePackages(remote, applicationContext.cacheDir, draft.added, draft.removed))
                stringSet(remote.getWhitelistPackages())
            } }
            if (service !== remote) return@launch
            result.onSuccess { latest ->
                state = state.copy(saving = false, draft = WhitelistDraft(latest, latest),
                    message = "应用白名单已保存。取消勾选的应用不再受这条保护；重新扫描后生效。")
            }.onFailure {
                if (it is CancellationException) throw it
                state = state.copy(saving = false, message = operationFailure(it))
            }
        }
    }

    private fun removePath(path: String) {
        val remote = service ?: return
        if (!state.pathsLoaded || state.loading || state.saving || path !in state.paths) return
        state = state.copy(saving = true, message = "正在取消此路径保护…")
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val response = requireSuccess(WhitelistFileClient.removePath(remote, applicationContext.cacheDir, path))
                stringSet(remote.getWhitelistPaths()).sorted() to response.optString("message")
            } }
            if (service !== remote) return@launch
            result.onSuccess { (paths, message) -> state = state.copy(saving = false, paths = paths, message = message) }
                .onFailure { if (it is CancellationException) throw it; state = state.copy(saving = false, message = operationFailure(it)) }
        }
    }

    private fun requireSuccess(raw: String): JSONObject = JSONObject(raw).also {
        check(it.optBoolean("success")) { it.optString("message", it.optString("error", "请求未确认")) }
    }
    private fun stringSet(raw: String): Set<String> = JSONArray(raw).let { array ->
        (0 until array.length()).map { array.getString(it) }.toSet()
    }
    private fun operationFailure(error: Throwable): String =
        "操作未确认，请刷新核对；不会自动重试。${error.message.orEmpty()}。若提示不支持的请求，请刷入本版模块并重启。"

    override fun onDestroy() {
        loadGeneration++
        if (bindingRequested) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
