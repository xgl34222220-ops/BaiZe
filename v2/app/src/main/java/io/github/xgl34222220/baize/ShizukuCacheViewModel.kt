package io.github.xgl34222220.baize

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.shizuku.IShizukuCacheService
import io.github.xgl34222220.baize.shizuku.ShizukuCacheService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

internal data class ShizukuCacheApp(val packageName: String, val label: String, val isProtected: Boolean)
internal data class ShizukuCacheState(val apps: List<ShizukuCacheApp> = emptyList(), val selected: Set<String> = emptySet(),
    val connected: Boolean = false, val supported: Boolean = false, val busy: Boolean = false, val query: String = "",
    val protectionKnown: Boolean = false, val localModeAvailable: Boolean = false,
    val status: String = "启动 Shizuku 后连接并授权，仅处理所选应用的缓存", val results: List<String> = emptyList())

internal fun cachePackageProtected(pkg: String, user: Int, rules: ApkProtectionRules): Boolean {
    if (pkg in rules.packages) return true
    val roots = listOf("/data/data/$pkg", "/data/user/$user/$pkg", "/data/user_de/$user/$pkg",
        "/storage/emulated/$user/Android/data/$pkg")
    return rules.paths.any { raw -> val path = raw.trimEnd('/')
        storageOwnerPackage("$path/file") == pkg || roots.any { root -> path == root || path.startsWith("$root/") || root.startsWith("$path/") }
    }
}

internal class ShizukuCacheViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val mutableState = MutableStateFlow(ShizukuCacheState())
    val state = mutableState.asStateFlow()
    private var service: IShizukuCacheService? = null
    private var root: IProfileRootService? = null
    private var rootBound = false
    private var binding = false
    private var closed = false
    private var initialized = false
    private val cancelled = AtomicBoolean(false)
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuCacheService::class.java))
        .daemon(false).processNameSuffix("shizuku-cache").tag("baize-cache-only").version(BuildConfig.VERSION_CODE)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed || binder == null) return
            service = IShizukuCacheService.Stub.asInterface(binder); binding = false
            val current = service
            viewModelScope.launch {
                val capability = withContext(Dispatchers.IO) { runCatching { JSONObject(current!!.capabilities()) }.getOrNull() }
                if (current !== service || closed) return@launch
                mutableState.update { it.copy(connected = capability != null, supported = capability?.optBoolean("cacheOnly") == true,
                    status = when { capability == null -> "服务未就绪，请重新连接"
                        !capability.optBoolean("cacheOnly") -> "当前系统不支持只清缓存，未执行清理"
                        else -> "Shizuku 已连接；选择应用后确认清理当前缓存" }) }
                refreshApps()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) = disconnected()
    }
    private val rootConnection = object : RootService.Connection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed || binder == null) return
            root = RootServiceClients.profile(binder, context.cacheDir); refreshApps()
        }
        override fun onServiceDisconnected(name: ComponentName?) { root = null; rootBound = false; refreshApps() }
        override fun onBindingFailed(name: ComponentName?, reason: RootService.BindingFailure) { root = null; rootBound = false; refreshApps() }
    }
    private val received = Shizuku.OnBinderReceivedListener { if (!closed) mutableState.update { it.copy(status = "Shizuku 已启动，点击连接并授权") } }
    private val dead = Shizuku.OnBinderDeadListener { disconnected() }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 30021 && !closed) {
            if (result == PackageManager.PERMISSION_GRANTED) bind() else mutableState.update { it.copy(status = "未授权 Shizuku，未执行清理") }
        }
    }
    fun initialize() {
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission); refreshApps()
    }
    private fun disconnected() {
        service = null; binding = false; cancelled.set(true)
        mutableState.update { it.copy(connected = false, supported = false, selected = emptySet(), status = "Shizuku 已断开；未自动重试，请重新连接") }
    }
    fun connect() {
        if (closed || state.value.busy || binding) return
        runCatching {
            if (!Shizuku.pingBinder()) { mutableState.update { it.copy(status = "Shizuku 尚未启动，请在 Shizuku 中完成启动后重试") }; return }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) { mutableState.update { it.copy(status = "需要 Shizuku 13 或更新版本") }; return }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else if (Shizuku.shouldShowRequestPermissionRationale()) mutableState.update { it.copy(status = "请在 Shizuku 的授权管理中允许白泽") }
            else Shizuku.requestPermission(30021)
        }.onFailure { mutableState.update { state -> state.copy(status = "连接未完成：${it.message.orEmpty()}") } }
    }
    private fun bind() {
        if (closed || binding || service != null) return
        binding = true; mutableState.update { it.copy(status = "正在连接 Shizuku 缓存服务…") }
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { binding = false; mutableState.update { state -> state.copy(status = "无法启动缓存服务：${it.message.orEmpty()}") } }
        viewModelScope.launch { delay(15_000); if (binding && !closed) { binding = false
            runCatching { Shizuku.unbindUserService(args, connection, false) }
            mutableState.update { it.copy(status = "连接超时，请在 Shizuku 中核对授权后重试") } } }
    }
    fun enableLocalMode() { if (!state.value.busy) { ApkProtectionStore.enableLocalOnly(context); refreshApps() } }
    fun reconnectProtection() {
        if (rootBound || closed) return
        rootBound = true
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), rootConnection) }
            .onFailure { rootBound = false; refreshApps() }
    }
    private fun protection() = ApkProtectionStore.refresh(context, ApkProtectionStore.source(context, root))
    fun refreshApps() {
        if (closed || state.value.busy) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val protection = protection(); val user = android.os.Process.myUid() / 100_000
                @Suppress("DEPRECATION") val apps = context.packageManager.getInstalledApplications(0)
                    .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { ShizukuCacheApp(it.packageName, context.packageManager.getApplicationLabel(it).toString().take(100),
                        protection is ApkProtectionState.Unknown || protection.rules?.let { rules -> cachePackageProtected(it.packageName, user, rules) } != false) }
                    .sortedBy { it.label.lowercase() }
                apps to protection
            } }
            result.onSuccess { (apps, protection) -> mutableState.update { it.copy(apps = apps,
                selected = it.selected.intersect(apps.filterNot { app -> app.isProtected }.map { app -> app.packageName }.toSet()),
                protectionKnown = protection !is ApkProtectionState.Unknown, localModeAvailable = !ApkProtectionStore.rootWasUsed(context)) } }
                .onFailure { mutableState.update { state -> state.copy(protectionKnown = false, selected = emptySet(), status = "无法核对应用与保护名单：${it.message.orEmpty()}") } }
        }
    }
    fun query(value: String) { mutableState.update { it.copy(query = value) } }
    fun toggle(pkg: String) {
        if (state.value.busy || state.value.apps.none { it.packageName == pkg && !it.isProtected }) return
        mutableState.update { if (pkg in it.selected) it.copy(selected = it.selected - pkg)
            else if (it.selected.size < 30) it.copy(selected = it.selected + pkg) else it.copy(status = "单次最多选择 30 个应用") }
    }
    fun stop() {
        cancelled.set(true)
        viewModelScope.launch(Dispatchers.IO) { runCatching { service?.cancel() } }
    }
    fun cleanSelected() {
        val snapshot = state.value; val current = service ?: return
        if (snapshot.busy || !snapshot.supported || !snapshot.protectionKnown || snapshot.selected.isEmpty()) return
        cancelled.set(false); mutableState.update { it.copy(busy = true, results = emptyList()) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    snapshot.selected.forEachIndexed { index, pkg ->
                        if (cancelled.get() || service !== current) return@forEachIndexed
                        val protection = protection()
                        check(protection !is ApkProtectionState.Unknown && protection.rules != null) { "保护名单无法核对，已停止清理" }
                        if (cachePackageProtected(pkg, android.os.Process.myUid() / 100_000, protection.rules!!)) {
                            mutableState.update { it.copy(results = it.results + "$pkg：受保护，已跳过") }; return@forEachIndexed
                        }
                        mutableState.update { it.copy(status = "正在清缓存 ${index + 1} / ${snapshot.selected.size}：$pkg") }
                        val reply = JSONObject(current.clearCaches(JSONArray().put(pkg).toString()))
                        mutableState.update { it.copy(results = it.results + "$pkg：${reply.optString("message", "结果未确认")}") }
                    }
                }
                mutableState.update { it.copy(status = if (cancelled.get()) "已停止；已完成的缓存清理不会撤回" else "处理结束，请查看各应用结果") }
            } catch (error: Exception) { mutableState.update { it.copy(status = "处理未完成：${error.message.orEmpty()}；未自动重试") } }
            finally { mutableState.update { it.copy(busy = false, selected = emptySet()) }; refreshApps() }
        }
    }
    override fun onCleared() {
        closed = true; cancelled.set(true)
        runCatching { service?.cancel() }; runCatching { Shizuku.unbindUserService(args, connection, true) }
        Shizuku.removeBinderReceivedListener(received); Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (rootBound) runCatching { RootService.unbind(rootConnection) }
        super.onCleared()
    }
}
