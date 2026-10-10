package io.github.xgl34222220.baize

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.clean.IntValueDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 设置 →「性能工具（实验）」：Dex2oat 编译、数据库优化、进程压制、内存压制。全部默认关闭。 */
class PerfToolsActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val model: PerfToolsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model.initialize()
        setContent {
            val appearance by appearanceViewModel.settings.collectAsState()
            val state by model.state.collectAsState()
            BaiZeTheme(appearance) {
                PerfToolsScreen(state, ::finish, model::update, model::saveWhitelist, model::saveDbBlacklist, model::command)
            }
        }
    }

    override fun onResume() { super.onResume(); model.setVisible(true) }
    override fun onPause() { model.setVisible(false); super.onPause() }

    companion object {
        fun intent(context: Context) = Intent(context, PerfToolsActivity::class.java)
    }
}

data class PerfToolsUiState(
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val moduleReady: Boolean = true,
    val busy: Boolean = false,
    val config: PerfToolsConfig = PerfToolsConfig(),
    val whitelist: List<String> = emptyList(),
    val dbBlacklist: List<String> = emptyList(),
    val sqlite3: String = "",
    val freezerSupported: Boolean = false,
    val freezerReason: String = "",
    val loopState: String = "",
    val frozenCount: Int = 0,
    val frozenTotal: Int = 0,
    val thawedTotal: Int = 0,
    val memAvailableMb: Int = -1,
    val trimTotal: Int = 0,
    val killTotal: Int = 0,
    val runtimeFreezeSupport: String = "",
    val dexState: String = "",
    val dexCurrent: Int = 0,
    val dexTotal: Int = 0,
    val dexOk: Int = 0,
    val dexFailed: Int = 0,
    val dexPackage: String = "",
    val dexMode: String = "",
    val dbState: String = "",
    val dbReason: String = "",
    val dbOptimized: Int = 0,
    val dbSkipped: Int = 0,
    val log: List<String> = emptyList(),
    val message: String = ""
) {
    val dexRunning: Boolean get() = dexState == "running"
    val freezeAvailable: Boolean get() = freezerSupported && runtimeFreezeSupport != "no"
}

class PerfToolsViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(PerfToolsUiState())
    val state = mutable.asStateFlow()
    private val context get() = getApplication<Application>()
    @Volatile private var remote: IProfileRootService? = null
    private var bound = false
    private var closed = false
    private var initialized = false
    private var visible = false
    private var poller: Job? = null
    private val connection = object : RootService.Connection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed || binder == null) return
            remote = RootServiceClients.profile(binder, context.cacheDir); bound = true
            mutable.update { it.copy(connected = true) }
            refresh()
            startPolling()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null; bound = false; mutable.update { it.copy(connected = false) } }
        override fun onBindingFailed(name: ComponentName?, reason: RootService.BindingFailure) {
            remote = null; bound = false
            mutable.update { it.copy(loaded = true, message = "无法连接 Root 服务，性能工具需要 Root 权限") }
        }
        override fun onNullBinding(name: ComponentName?) = onBindingFailed(name, RootService.BindingFailure.NULL_BINDING)
    }

    fun initialize() {
        if (initialized) return
        initialized = true
        runCatching { RootService.bind(Intent(context, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE), connection) }
            .onFailure { mutable.update { it.copy(loaded = true, message = "无法连接 Root 服务") } }
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (value) { refresh(); startPolling() }
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive && visible && remote != null) {
                delay(if (state.value.dexRunning) 2_000 else 10_000)
                if (visible) refresh()
            }
        }
    }

    private suspend fun exchange(operation: String, arguments: JSONArray = JSONArray()): JSONObject? {
        val source = remote ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { JSONObject(RootServiceClients.profileExchange(source, context.cacheDir, operation, arguments)) }.getOrNull()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val json = exchange("readPerfTools") ?: return@launch
            mutable.update { merge(it, json) }
        }
    }

    private fun merge(current: PerfToolsUiState, json: JSONObject): PerfToolsUiState {
        val status = json.optJSONObject("status") ?: JSONObject()
        val dex = json.optJSONObject("dex2oat") ?: JSONObject()
        val db = json.optJSONObject("dbopt") ?: JSONObject()
        val freezer = json.optJSONObject("freezer") ?: JSONObject()
        val log = json.optJSONArray("log")?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty()
        return current.copy(
            loaded = true,
            moduleReady = json.optBoolean("moduleReady", true),
            config = if (current.busy) current.config else PerfToolsConfig.parse(json.optString("config")),
            whitelist = PerfToolsPolicy.normalizeList(json.optString("whitelist")),
            dbBlacklist = PerfToolsPolicy.normalizeList(json.optString("dbBlacklist")),
            sqlite3 = json.optString("sqlite3"),
            freezerSupported = freezer.optBoolean("supported"),
            freezerReason = freezer.optString("reason"),
            loopState = status.optString("loop_state"),
            frozenCount = status.optInt("frozen_count"),
            frozenTotal = status.optInt("frozen_total"),
            thawedTotal = status.optInt("thawed_total"),
            memAvailableMb = status.optInt("mem_avail_mb", -1),
            trimTotal = status.optInt("trim_total"),
            killTotal = status.optInt("kill_total"),
            runtimeFreezeSupport = status.optString("freeze_support"),
            dexState = dex.optString("state"),
            dexCurrent = dex.optInt("current"),
            dexTotal = dex.optInt("total"),
            dexOk = dex.optInt("ok"),
            dexFailed = dex.optInt("failed"),
            dexPackage = dex.optString("package"),
            dexMode = dex.optString("mode"),
            dbState = db.optString("state"),
            dbReason = db.optString("reason"),
            dbOptimized = db.optInt("optimized"),
            dbSkipped = db.optInt("skipped"),
            log = log
        )
    }

    private fun save(config: PerfToolsConfig, whitelist: List<String>, blacklist: List<String>) {
        mutable.update { it.copy(config = config, whitelist = whitelist, dbBlacklist = blacklist, busy = true) }
        viewModelScope.launch {
            val result = exchange("writePerfTools", JSONArray().put(config.encode())
                .put(whitelist.joinToString("\n")).put(blacklist.joinToString("\n")))
            mutable.update { it.copy(busy = false, message = if (result?.optBoolean("success") == true) "" else "保存失败，请检查 Root 连接") }
            refresh()
        }
    }

    fun update(config: PerfToolsConfig) = save(config, state.value.whitelist, state.value.dbBlacklist)
    fun saveWhitelist(raw: String) = save(state.value.config, PerfToolsPolicy.normalizeList(raw), state.value.dbBlacklist)
    fun saveDbBlacklist(raw: String) = save(state.value.config, state.value.whitelist,
        PerfToolsPolicy.normalizeList(raw).filterNot { ':' in it })

    fun command(name: String) {
        viewModelScope.launch {
            val result = exchange("perfToolsCommand", JSONArray().put(name))
            mutable.update { it.copy(message = result?.optString("message").orEmpty().ifBlank { "操作失败，请检查 Root 连接" }) }
            refresh()
            startPolling()
        }
    }

    override fun onCleared() {
        closed = true
        poller?.cancel()
        if (bound) runCatching { RootService.unbind(connection) }
        super.onCleared()
    }
}

/** 设置页入口行（放在「性能工具（实验）」分组里）。 */
@Composable
internal fun PerfToolsEntryRow() {
    val context = LocalContext.current
    LuoShuNavigationRow(Icons.Rounded.Speed, "性能工具", "Dex2oat、数据库、进程与内存压制（默认全部关闭）") {
        runCatching { context.startActivity(PerfToolsActivity.intent(context)) }
    }
}

private enum class PerfDialog { NONE, DEX_MODE, FREEZE_MINUTES, MEM_THRESHOLD, MEM_COOLDOWN, WHITELIST, DB_BLACKLIST }

@Composable
internal fun PerfToolsScreen(
    state: PerfToolsUiState,
    onBack: () -> Unit,
    onUpdate: (PerfToolsConfig) -> Unit,
    onSaveWhitelist: (String) -> Unit,
    onSaveDbBlacklist: (String) -> Unit,
    onCommand: (String) -> Unit
) {
    val c = state.config
    var dialog by rememberSaveable { mutableStateOf(PerfDialog.NONE) }
    val ready = state.connected && state.moduleReady && !state.busy
    Surface(Modifier.fillMaxSize(), color = BaiZeTokens.colors.surfaceBase) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { LuoShuPageHeader("性能工具（实验）", onBack) }
            item {
                PerfNote(buildString {
                    append("以下功能全部默认关闭，每项单独开关。开机阶段不会运行任何一项：常驻检查在开机完成 120 秒后才开始，编译与数据库优化只在充电且息屏时进行。")
                    if (!state.connected) append("\n需要 Root 连接。")
                    if (!state.moduleReady) append("\n当前模块版本缺少性能工具脚本，请先更新模块。")
                    if (state.message.isNotBlank()) append("\n${state.message}")
                })
            }

            // ---------- Dex2oat ----------
            item { LuoShuSection("Dex2oat 编译", "风险：编译期间耗电发热，「全部」或 everything 可能需要很久") }
            item {
                LuoShuGroup {
                    if (state.dexRunning) {
                        LuoShuNavigationRow(Icons.Rounded.Stop, "停止编译",
                            "正在编译 ${state.dexCurrent}/${state.dexTotal} · ${state.dexPackage}") { onCommand("dex2oat-stop") }
                        LinearProgressIndicator(
                            progress = { if (state.dexTotal > 0) state.dexCurrent.toFloat() / state.dexTotal else 0f },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    } else {
                        LuoShuNavigationRow(Icons.Rounded.PlayArrow, "立即编译",
                            dexSummary(state)) { if (ready) onCommand("dex2oat-start") }
                    }
                    LuoShuGroupDivider()
                    LuoShuSwitchRow(Icons.Rounded.BatteryChargingFull, "充电息屏时自动编译", "每天最多一次，亮屏或拔电立即停止",
                        c.dex2oatAuto, { onUpdate(c.copy(dex2oatAuto = it)) }, enabled = ready)
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Tune, "编译模式", c.dex2oatMode.label) { if (ready) dialog = PerfDialog.DEX_MODE }
                    LuoShuGroupDivider()
                    LuoShuSwitchRow(Icons.Rounded.Apps, "包含系统应用", if (c.dex2oatAllApps) "范围：全部应用" else "范围：用户应用",
                        c.dex2oatAllApps, { onUpdate(c.copy(dex2oatAllApps = it)) }, enabled = ready)
                    LuoShuGroupDivider()
                    LuoShuSwitchRow(Icons.Rounded.Refresh, "强制重新编译", "已编译的应用也重新编译（更慢）",
                        c.dex2oatForce, { onUpdate(c.copy(dex2oatForce = it)) }, enabled = ready)
                }
            }

            // ---------- 数据库优化 ----------
            item { LuoShuSection("数据库优化", "风险：VACUUM 期间应用启动可能短暂卡顿；损坏、加密与使用中的数据库自动跳过") }
            item {
                LuoShuGroup {
                    val noSqlite = state.loaded && state.sqlite3.isBlank()
                    LuoShuSwitchRow(Icons.Rounded.Storage, "充电息屏时优化应用数据库",
                        if (noSqlite) "设备无 sqlite3，暂不可用" else dbSummary(state),
                        c.dbOptimizeAuto && !noSqlite, { onUpdate(c.copy(dbOptimizeAuto = it)) }, enabled = ready && !noSqlite)
                    LuoShuGroupDivider()
                    LuoShuSwitchRow(Icons.Rounded.Block, "优化前强制停止应用", "关闭时只处理未在运行的应用（推荐关闭）",
                        c.dbForceStop, { onUpdate(c.copy(dbForceStop = it)) }, enabled = ready && !noSqlite)
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Shield, "数据库黑名单",
                        "内置微信、QQ、TIM 与系统应用；自定义 ${state.dbBlacklist.size} 个") { if (ready) dialog = PerfDialog.DB_BLACKLIST }
                }
            }

            // ---------- 进程压制 ----------
            item { LuoShuSection("进程压制", "风险：被冻结的应用在后台收不到消息、下载会暂停，切回前台自动解冻") }
            item {
                LuoShuGroup {
                    val unsupported = state.loaded && state.connected && !state.freezeAvailable
                    LuoShuSwitchRow(Icons.Rounded.AcUnit, "冻结后台应用",
                        if (unsupported) "设备不支持 cgroup v2 冻结，已停用（不会改用强制停止）${state.freezerReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
                        else freezeSummary(state),
                        c.freezeEnabled && !unsupported, { onUpdate(c.copy(freezeEnabled = it)) }, enabled = ready && !unsupported)
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Timer, "后台时长", "进入后台 ${c.freezeAfterMinutes} 分钟后冻结") {
                        if (ready) dialog = PerfDialog.FREEZE_MINUTES
                    }
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.VerifiedUser, "压制白名单",
                        "内置输入法、桌面、短信、拨号、微信、QQ、TIM；自定义 ${state.whitelist.size} 个") { if (ready) dialog = PerfDialog.WHITELIST }
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.LockOpen, "立即解冻全部", "当前冻结 ${state.frozenCount} 个进程") {
                        if (state.connected) onCommand("thaw-all")
                    }
                }
            }

            // ---------- 内存压制 ----------
            item { LuoShuSection("内存压制", "风险：结束后台进程会让这些应用下次冷启动") }
            item {
                LuoShuGroup {
                    LuoShuSwitchRow(Icons.Rounded.Memory, "内存不足时回收后台",
                        memSummary(state), c.memEnabled, { onUpdate(c.copy(memEnabled = it)) }, enabled = ready)
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.Speed, "可用内存阈值", "低于 ${c.memThresholdMb} MB 时发送回收通知") {
                        if (ready) dialog = PerfDialog.MEM_THRESHOLD
                    }
                    LuoShuGroupDivider()
                    LuoShuNavigationRow(Icons.Rounded.HourglassBottom, "回收冷却", "两次回收至少间隔 ${c.memCooldownSeconds} 秒") {
                        if (ready) dialog = PerfDialog.MEM_COOLDOWN
                    }
                    LuoShuGroupDivider()
                    LuoShuSwitchRow(Icons.Rounded.DeleteSweep, "同时结束后台缓存进程", "只结束整个应用都处于缓存状态且不在白名单的应用",
                        c.memKill, { onUpdate(c.copy(memKill = it)) }, enabled = ready)
                }
            }

            item { LuoShuSection("最近记录", "完整记录见「记录 / 审计」") }
            item {
                PerfNote(state.log.takeLast(12).reversed().joinToString("\n").ifBlank { "暂无记录" })
            }
        }
    }

    when (dialog) {
        PerfDialog.DEX_MODE -> ModeDialog(c.dex2oatMode, { dialog = PerfDialog.NONE }) { onUpdate(c.copy(dex2oatMode = it)) }
        PerfDialog.FREEZE_MINUTES -> IntValueDialog("后台时长", "应用整体进入后台满这么久才冻结。", c.freezeAfterMinutes,
            PerfToolsConfig.FREEZE_MINUTES, " 分钟", { dialog = PerfDialog.NONE }) { onUpdate(c.copy(freezeAfterMinutes = it)) }
        PerfDialog.MEM_THRESHOLD -> IntValueDialog("可用内存阈值", "可用内存低于该值时向后台应用发送回收通知。", c.memThresholdMb,
            PerfToolsConfig.MEM_THRESHOLD_MB, " MB", { dialog = PerfDialog.NONE }) { onUpdate(c.copy(memThresholdMb = it)) }
        PerfDialog.MEM_COOLDOWN -> IntValueDialog("回收冷却", "两次回收之间的最短间隔。", c.memCooldownSeconds,
            PerfToolsConfig.MEM_COOLDOWN_SECONDS, " 秒", { dialog = PerfDialog.NONE }) { onUpdate(c.copy(memCooldownSeconds = it)) }
        PerfDialog.WHITELIST -> ListDialog("压制白名单",
            "每行一个包名（保护整个应用）或进程名（如 com.example:push，只保护该进程）。\n内置：" +
                (PerfToolsPolicy.DEFAULT_WHITELIST + PerfToolsPolicy.DYNAMIC_WHITELIST_LABELS).joinToString("、"),
            state.whitelist, { dialog = PerfDialog.NONE }, onSaveWhitelist)
        PerfDialog.DB_BLACKLIST -> ListDialog("数据库黑名单",
            "每行一个包名，这些应用的数据库永不优化。\n内置：" + PerfToolsPolicy.DEFAULT_DB_BLACKLIST.joinToString("、") + "、全部系统应用",
            state.dbBlacklist, { dialog = PerfDialog.NONE }, onSaveDbBlacklist)
        PerfDialog.NONE -> Unit
    }
}

private fun dexSummary(s: PerfToolsUiState): String = when (s.dexState) {
    "", "running" -> "使用 cmd package compile，进度在此显示"
    else -> "上次：${PerfToolsPolicy.dex2oatStateLabel(s.dexState)} · ${s.dexMode} · 成功 ${s.dexOk}，失败 ${s.dexFailed}（${s.dexCurrent}/${s.dexTotal}）"
}

private fun dbSummary(s: PerfToolsUiState): String = when (s.dbState) {
    "" -> "只处理未运行的用户应用，先做完整性检查"
    "unavailable" -> s.dbReason.ifBlank { "设备无 sqlite3，暂不可用" }
    "running" -> "正在优化…"
    else -> "上次：优化 ${s.dbOptimized} 个，跳过 ${s.dbSkipped} 个"
}

private fun freezeSummary(s: PerfToolsUiState): String = when {
    !s.config.freezeEnabled -> "使用 cgroup v2 冻结，不会强制停止应用"
    s.loopState == "running" -> "已冻结 ${s.frozenCount} 个进程 · 累计冻结 ${s.frozenTotal}，解冻 ${s.thawedTotal}"
    else -> "开机完成 2 分钟后开始，约 1 分钟内生效"
}

private fun memSummary(s: PerfToolsUiState): String = when {
    !s.config.memEnabled -> "向后台应用发送 trim-memory，可选结束缓存进程"
    s.loopState == "running" && s.memAvailableMb >= 0 -> "当前可用 ${s.memAvailableMb} MB · 回收 ${s.trimTotal} 次，结束 ${s.killTotal} 个"
    else -> "开机完成 2 分钟后开始，约 1 分钟内生效"
}

@Composable
private fun PerfNote(text: String) {
    LuoShuGroup {
        Text(text, Modifier.padding(16.dp), fontSize = 13.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModeDialog(current: Dex2oatMode, onDismiss: () -> Unit, onSelect: (Dex2oatMode) -> Unit) {
    BaiZeDialog(
        onDismissRequest = onDismiss,
        title = { Text("编译模式") },
        text = {
            Column {
                Dex2oatMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = mode == current, onClick = { onSelect(mode); onDismiss() })
                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == current, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(mode.label)
                            Text(when (mode) {
                                Dex2oatMode.SPEED_PROFILE -> "按使用记录编译常用代码，空间与速度平衡"
                                Dex2oatMode.SPEED -> "编译全部代码，占用更多空间"
                                Dex2oatMode.EVERYTHING -> "最完整，最耗时"
                            }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { BaiZeDialogButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ListDialog(title: String, description: String, entries: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(entries) { mutableStateOf(entries.joinToString("\n")) }
    BaiZeDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description, fontSize = 13.sp, lineHeight = 19.sp)
                OutlinedTextField(value = text, onValueChange = { text = it.take(32_000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), minLines = 5,
                    supportingText = { Text("有效条目 ${PerfToolsPolicy.normalizeList(text).size} 个") })
            }
        },
        confirmButton = { BaiZeDialogButton(onClick = { onSave(text); onDismiss() }) { Text("保存") } },
        dismissButton = { BaiZeDialogButton(onClick = onDismiss) { Text("取消") } }
    )
}
