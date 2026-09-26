package io.github.xgl34222220.baize

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.root.*
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Uses the same rows, spacing, headers and actions as the existing cleaning page. */
@Composable
internal fun ToolboxScreen(model: ToolboxViewModel, onBack: () -> Unit, onWhitelist: () -> Unit, onAppearance: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onNotification: () -> Unit) {
    var routes by rememberSaveable { mutableStateOf(listOf("home")) }
    val route = routes.last()
    fun navigate(target: String) { routes = routes + target }
    var edit by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current
    val config = model.snapshot.optJSONObject("config") ?: JSONObject()
    val editable = model.connected && !model.busy && !model.running
    val availability = model.snapshot.optJSONObject("availability") ?: JSONObject()
    val task = ToolboxCatalog.extensions.firstOrNull { route == "task:${it.id}" }
    val back = { if (routes.size == 1) onBack() else routes = routes.dropLast(1) }
    BackHandler(routes.size > 1) { routes = routes.dropLast(1) }
    val title = task?.title ?: when (route) {
        "system" -> "系统维护"; "plans" -> "扩展计划"; "settings" -> "执行设置"
        "history" -> "执行记录"; "batch" -> "批量执行"; else -> "扩展工具"
    }
    fun editValue(key: String) { edit = key; if (key.endsWith("Packages") || key == "processWhitelist") model.loadPackages() }
    fun changeTask(id: String, key: String, value: Boolean) = model.save { it.getJSONObject("tasks").getJSONObject(id).put(key, value) }
    Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
        topBar = { Box(Modifier.padding(horizontal = 16.dp)) {
            LuoShuPageHeader(title, back) {
                if (route == "home") {
                    LuoShuHeaderButton(Icons.Rounded.History, "执行记录") { navigate("history") }
                    LuoShuHeaderButton(Icons.Rounded.Tune, "执行设置") { navigate("settings") }
                }
            }
        } }) { padding ->
        AnimatedContent(route, Modifier.fillMaxSize().padding(padding),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "toolNavigation") { page ->
            key(page) {
                LazyColumn(Modifier.fillMaxSize().testTag("toolbox-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!model.connected || model.running || model.busy) item {
                        LuoShuGroup { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailStatusText(if (model.running) model.snapshot.optJSONObject("progress")?.optString("phase").orEmpty() else model.message)
                            if (model.running || model.busy) BaiZeProgress()
                            if (model.running) GlassActionButton("停止当前任务", { model.action("toolboxCancel") }, Modifier.fillMaxWidth(), secondary = true)
                            else if (!model.connected) GlassActionButton("连接 Root", model::bind, Modifier.fillMaxWidth(), secondary = true)
                        } }
                    }
                    when {
                        page == "home" -> {
                            item { LuoShuSection("应用专项", "按应用处理缓存与临时文件") }
                            item { LuoShuGroup {
                                ToolboxCatalog.extensions.filter { it.group == "应用专项" }.forEachIndexed { index, item ->
                                    if (index > 0) LuoShuGroupDivider()
                                    LuoShuNavigationRow(toolIcon(item.id), item.title, toolSubtitle(item, availability)) { navigate("task:${item.id}") }
                                }
                            } }
                            item { LuoShuSection("更多工具") }
                            item { LuoShuGroup {
                                LuoShuNavigationRow(Icons.Rounded.DriveFileMove, "下载转移", "设置来源、目标与目录重定向") { navigate("task:mounter") }
                                LuoShuGroupDivider()
                                LuoShuNavigationRow(Icons.Rounded.Build, "系统维护", "后台进程、数据库与应用编译") { navigate("system") }
                            } }
                            item { LuoShuSection("自动执行") }
                            item { LuoShuGroup {
                                LuoShuNavigationRow(Icons.Rounded.Schedule, "扩展计划", "为专项与维护任务设置执行时间") { navigate("plans") }
                                LuoShuGroupDivider()
                                LuoShuNavigationRow(Icons.Rounded.PlaylistPlay, "批量执行", "选择本次要运行的扩展工具") { navigate("batch") }
                            } }
                        }
                        page == "system" -> {
                            item { LuoShuSection("维护工具", "进入项目查看设备支持情况与执行设置") }
                            item { LuoShuGroup {
                                ToolboxCatalog.extensions.filter { it.group == "系统维护" }.forEachIndexed { index, item ->
                                    if (index > 0) LuoShuGroupDivider()
                                    LuoShuNavigationRow(toolIcon(item.id), item.title, toolSubtitle(item, availability)) { navigate("task:${item.id}") }
                                }
                            } }
                            item { DeviceSummary(model.snapshot.optJSONObject("device") ?: JSONObject()) }
                        }
                        page.startsWith("task:") -> {
                            val current = ToolboxCatalog.task(page.substringAfter(':'))
                            val ready = availability.optJSONObject(current.id) ?: JSONObject()
                            val state = config.optJSONObject("tasks")?.optJSONObject(current.id) ?: JSONObject()
                            item { LuoShuGroup { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                BaiZeIconTile(toolIcon(current.id))
                                Text(current.description, style = MaterialTheme.typography.bodyMedium)
                                Text(if (!model.connected) "连接 Root 后检查设备支持情况" else ready.optString("message", "正在检查…"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                GlassActionButton(if (current.group == "应用专项") "清理此应用缓存" else "开始执行",
                                    { model.action("toolboxRun", current.id) }, Modifier.fillMaxWidth(),
                                    enabled = editable && ready.optBoolean("available"))
                            } }
                            item { LuoShuSection("执行设置") }
                            item { TaskSettings(current.id, config, editable, ::editValue, model::save, onWhitelist) }
                            item { LuoShuSection("自动执行") }
                            item { LuoShuGroup {
                                LuoShuSwitchRow(Icons.Rounded.Schedule, "定时执行", "每 ${state.optInt("intervalDays", 1)} 天 · ${state.optString("time", "02:30")}",
                                    state.optBoolean("scheduled"), { changeTask(current.id, "scheduled", it) }, editable)
                                LuoShuGroupDivider()
                                SettingRow("执行时间", "schedule:${current.id}", config, editable, ::editValue)
                            } }
                            if (current.id in setOf("mounter", "process")) item { LuoShuGroup {
                                if (current.id == "mounter") {
                                    ActionRow(Icons.Rounded.Undo, "撤销最近一次转移", "按原记录恢复文件位置", editable) { model.action("toolboxRun", "undo") }
                                    LuoShuGroupDivider()
                                    ActionRow(Icons.Rounded.LinkOff, "解除目录重定向", "保留目标目录中的文件", editable) { model.action("toolboxRun", "unmount") }
                                } else ActionRow(Icons.Rounded.RestartAlt, "恢复进程状态", "解除白泽冻结及回收优先级调整", editable) { model.action("toolboxRun", "thaw") }
                            } }
                            item { LuoShuGroup { LuoShuNavigationRow(Icons.Rounded.History, "查看执行记录", model.message) { navigate("history") } } }
                        }
                        page == "plans" || page == "batch" -> {
                            item { LuoShuSection(if (page == "plans") "专项与维护计划" else "选择扩展工具",
                                if (page == "plans") "原有清理任务继续使用清理页的自动化策略" else "所选项目依次执行") }
                            if (page == "plans") item { LuoShuGroup {
                                LuoShuNavigationRow(Icons.Rounded.Tune, "执行条件", "设置关屏与充电条件") { navigate("settings") }
                            } }
                            ToolboxCatalog.extensions.groupBy { it.group }.forEach { (group, entries) ->
                                item { LuoShuSection(group) }
                                item { LuoShuGroup { entries.forEachIndexed { index, current ->
                                    if (index > 0) LuoShuGroupDivider()
                                    val state = config.optJSONObject("tasks")?.optJSONObject(current.id) ?: JSONObject()
                                    val property = if (page == "plans") "scheduled" else "enabled"
                                    LuoShuSwitchRow(toolIcon(current.id), current.title,
                                        if (page == "plans") "每 ${state.optInt("intervalDays", 1)} 天 · ${state.optString("time", "02:30")}" else toolSubtitle(current, availability),
                                        state.optBoolean(property), { changeTask(current.id, property, it) }, editable)
                                    if (page == "plans" && state.optBoolean("scheduled")) SettingRow("执行时间", "schedule:${current.id}", config, editable, ::editValue)
                                } } }
                            }
                            if (page == "batch") item {
                                val count = ToolboxCatalog.extensions.count { config.optJSONObject("tasks")?.optJSONObject(it.id)?.optBoolean("enabled") == true }
                                GlassActionButton("执行已选 $count 项", { model.action("toolboxRun", "all") }, Modifier.fillMaxWidth(), enabled = editable && count > 0)
                            }
                        }
                        page == "settings" -> {
                            item { LuoShuSection("执行条件") }
                            item { LuoShuGroup {
                                ConfigSwitch("仅关屏时自动执行", "screenOffOnly", config, editable, model::save, Icons.Rounded.PhoneAndroid)
                                LuoShuGroupDivider()
                                ConfigSwitch("仅充电时自动执行", "chargingOnly", config, editable, model::save, Icons.Rounded.BatteryChargingFull)
                                LuoShuGroupDivider()
                                ConfigSwitch("完成通知", "notifications", config, editable, { change -> model.save(change); onNotification() }, Icons.Rounded.Notifications)
                            } }
                            item { Text("计划会在条件满足后执行；错过时间可在六小时内补做。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            item { LuoShuSection("规则与保护") }
                            item { LuoShuGroup {
                                LuoShuNavigationRow(Icons.Rounded.Shield, "保护白名单", "与原有清理功能共用") { onWhitelist() }
                                LuoShuGroupDivider()
                                SettingRow("额外清理规则", "customRules", config, editable, ::editValue, "应用到原有规则扫描")
                                LuoShuGroupDivider()
                                SettingRow("文件归类规则", "organizerRules", config, editable, ::editValue, "应用到原有文件归类页面")
                                LuoShuGroupDivider()
                                val rules = model.snapshot.optJSONObject("rules") ?: JSONObject()
                                ActionRow(Icons.Rounded.CloudDownload, "更新规则库", rules.optJSONObject("metadata")?.optString("rules_version", "内置版本") ?: "内置版本", editable) { model.action("toolboxRulesUpdate") }
                                if (rules.optBoolean("rollbackAvailable")) {
                                    LuoShuGroupDivider()
                                    ActionRow(Icons.Rounded.Restore, "回退上版规则", "恢复更新前的规则库", editable) { model.action("toolboxRulesRollback") }
                                }
                            } }
                            item { LuoShuSection("配置与记录") }
                            item { LuoShuGroup {
                                ActionRow(Icons.Rounded.FileUpload, "导出配置", "保存扩展工具与计划设置", editable, onExport)
                                LuoShuGroupDivider()
                                ActionRow(Icons.Rounded.FileDownload, "导入配置", "读取已保存的设置", editable, onImport)
                                LuoShuGroupDivider()
                                ConfigSwitch("记录清理统计", "statistics", config, editable, model::save, Icons.Rounded.BarChart)
                                LuoShuGroupDivider()
                                SettingRow("执行次数清零阈值", "counterReset", config, editable, ::editValue)
                            } }
                        }
                        page == "history" -> {
                            val history = model.snapshot.optJSONArray("history") ?: JSONArray()
                            val stats = model.snapshot.optJSONObject("statistics") ?: JSONObject()
                            item { LuoShuGroup { Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column { Text("今日删除", style = MaterialTheme.typography.bodySmall); Text(Formatter.formatFileSize(context, stats.optLong("todayBytes")), style = MaterialTheme.typography.titleLarge) }
                                Column { Text("累计删除", style = MaterialTheme.typography.bodySmall); Text(Formatter.formatFileSize(context, stats.optLong("totalBytes")), style = MaterialTheme.typography.titleLarge) }
                            } } }
                            if (history.length() == 0) item { DetailEmptyState("暂无执行记录", "实际完成、跳过或失败后会在这里显示原因") }
                            items((0 until history.length()).map { history.getJSONObject(it) }, key = { it.optString("taskId") }) { row ->
                                LuoShuGroup { LuoShuNavigationRow(Icons.Rounded.History, row.optString("message"),
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("finishedAt")))) { detail = row } }
                            }
                            if (history.length() > 0) item { LuoShuGroup {
                                ActionRow(Icons.Rounded.DeleteOutline, "清空执行记录", "保留累计统计", editable) { model.action("toolboxClearHistory") }
                                LuoShuGroupDivider()
                                ActionRow(Icons.Rounded.RestartAlt, "清零统计", "保留执行记录", editable) { model.action("toolboxResetStats") }
                            } }
                        }
                    }
                    if (model.connected && !model.running && model.message !in setOf("已连接", "") && page != "home") item { DetailStatusText(model.message) }
                }
            }
        }
    }
    edit?.let { name -> ToolboxEditor(name, config, model.snapshot.optString("customRules"), model.packages, { edit = null }) { value ->
        if (name == "customRules") model.action("toolboxRulesSave", value)
        else if (name.startsWith("schedule:")) model.save { it.getJSONObject("tasks").getJSONObject(name.substringAfter(':')).apply {
            val values = value.split('|'); put("time", values[0]); put("intervalDays", values[1].toIntOrNull() ?: 1)
        } } else model.save { it.put(name, value) }
        edit = null
    } }
    detail?.let { row -> BaiZeDialog(onDismissRequest = { detail = null }, title = { Text("执行详情") },
        text = { LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(row.optString("message")) }
            val results = row.optJSONArray("results") ?: JSONArray()
            items((0 until results.length()).map { results.getJSONObject(it) }) { result ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${result.optString("title")} · ${ToolboxAvailability.status(result)}", fontWeight = FontWeight.Medium)
                    Text(result.optString("message"), style = MaterialTheme.typography.bodySmall)
                    if (result.has("deletedBytes")) Text("删除 ${Formatter.formatFileSize(context, result.optLong("deletedBytes"))} · ${result.optLong("deletedFiles")} 个文件", style = MaterialTheme.typography.bodySmall)
                    if (result.has("compactedBytes")) Text("数据库缩减 ${Formatter.formatFileSize(context, result.optLong("compactedBytes"))}", style = MaterialTheme.typography.bodySmall)
                    if (result.has("memoryBeforeKb")) Text("可用内存 ${Formatter.formatFileSize(context, result.optLong("memoryBeforeKb") * 1024)} → ${Formatter.formatFileSize(context, result.optLong("memoryAfterKb") * 1024)}", style = MaterialTheme.typography.bodySmall)
                    DetailExpandableText("查看执行明细", result.toString(2))
                }
            }
        } }, confirmButton = { BaiZeDialogButton(onClick = { detail = null }) { Text("关闭") } }) }
}

private fun toolSubtitle(task: ToolboxCatalog.Task, availability: JSONObject): String {
    val state = availability.optJSONObject(task.id)
    return if (state != null && !state.optBoolean("available")) state.optString("message") else task.description
}
private fun toolIcon(id: String): ImageVector = when (id) {
    "wechat", "qq" -> Icons.Rounded.ChatBubbleOutline
    "dy" -> Icons.Rounded.PlayCircleOutline; "wyy" -> Icons.Rounded.MusicNote
    "mounter" -> Icons.Rounded.DriveFileMove; "process" -> Icons.Rounded.Memory
    "database" -> Icons.Rounded.Storage; "dex2" -> Icons.Rounded.Apps
    "dirty" -> Icons.Rounded.SdStorage; "memory" -> Icons.Rounded.Speed
    else -> Icons.Rounded.Article
}
@Composable private fun ActionRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, action: () -> Unit) {
    LuoShuNavigationRow(icon, title, subtitle, action, enabled)
}
@Composable private fun SettingRow(title: String, key: String, config: JSONObject, enabled: Boolean, edit: (String) -> Unit, description: String? = null) {
    val value = when {
        description != null -> description
        key.startsWith("schedule:") -> config.optJSONObject("tasks")?.optJSONObject(key.substringAfter(':')).let { "${it?.optString("time", "02:30")} · 每 ${it?.optInt("intervalDays", 1)} 天" }
        key.endsWith("Packages") || key == "processWhitelist" -> "已选择 ${ToolboxConfig.packages(config.optString(key)).size} 个应用"
        key.endsWith("Rules") -> if (config.optString(key).isBlank()) "尚未设置" else "${config.optString(key).lineSequence().count { it.isNotBlank() }} 条规则"
        key == "processMode" -> when (config.optString(key, "kill")) { "freeze" -> "冻结缓存进程"; "oom" -> "调整回收优先级"; else -> "结束后台进程" }
        key == "memoryThreshold" -> "${config.optInt(key, 80)}%"
        key == "counterReset" -> if (config.optLong(key) == 0L) "不自动清零" else "${config.optLong(key)} 次"
        else -> config.optString(key).ifBlank { "尚未设置" }
    }
    ActionRow(if (key.startsWith("schedule:")) Icons.Rounded.Schedule else Icons.Rounded.Tune, title, value, enabled) { edit(key) }
}
@Composable private fun ConfigSwitch(title: String, key: String, config: JSONObject, enabled: Boolean,
    save: ((JSONObject) -> Unit) -> Unit, icon: ImageVector = Icons.Rounded.Tune, subtitle: String = "") {
    LuoShuSwitchRow(icon, title, subtitle, config.optBoolean(key, key in setOf("screenOffOnly", "skipFrozen", "notifications", "statistics")),
        { value -> save { it.put(key, value) } }, enabled)
}
@Composable private fun TaskSettings(id: String, config: JSONObject, editable: Boolean, edit: (String) -> Unit,
    save: ((JSONObject) -> Unit) -> Unit, whitelist: () -> Unit) {
    LuoShuGroup {
        when (id) {
            "process" -> {
                SettingRow("管理应用", "processPackages", config, editable, edit); LuoShuGroupDivider()
                SettingRow("保护应用", "processWhitelist", config, editable, edit); LuoShuGroupDivider()
                SettingRow("管理方式", "processMode", config, editable, edit); LuoShuGroupDivider()
                SettingRow("内存占用阈值", "memoryThreshold", config, editable, edit); LuoShuGroupDivider()
                ConfigSwitch("保留已冻结进程", "skipFrozen", config, editable, save); LuoShuGroupDivider()
                ConfigSwitch("持续检查后台", "processContinuous", config, editable, save)
                if (config.optBoolean("processContinuous")) { LuoShuGroupDivider(); SettingRow("检查间隔（秒）", "pressureIntervalSeconds", config, editable, edit) }
            }
            "database" -> {
                SettingRow("选择应用", "databasePackages", config, editable, edit); LuoShuGroupDivider()
                ConfigSwitch("压缩数据库", "vacuum", config, editable, save, subtitle = "仅处理未运行应用的标准数据库")
            }
            "dex2" -> {
                SettingRow("选择应用", "compilePackages", config, editable, edit); LuoShuGroupDivider()
                SettingRow("编译模式", "compilerFilter", config, editable, edit); LuoShuGroupDivider()
                ConfigSwitch("强制重新编译", "forceCompile", config, editable, save)
            }
            "dirty" -> {
                SettingRow("脏段阈值", "dirtyThreshold", config, editable, edit); LuoShuGroupDivider()
                SettingRow("运行时限（秒）", "gcSeconds", config, editable, edit)
            }
            "mounter" -> {
                SettingRow("转移规则", "downloadRules", config, editable, edit); LuoShuGroupDivider()
                ConfigSwitch("启用目录重定向", "bindRedirect", config, editable, save, subtitle = "转移后将新文件写入目标目录")
            }
            else -> LuoShuNavigationRow(Icons.Rounded.Shield, "保护白名单", "管理受到保护的应用与路径", whitelist)
        }
    }
}
@Composable private fun DeviceSummary(device: JSONObject) {
    val context = LocalContext.current
    LuoShuGroup { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("设备状态", style = MaterialTheme.typography.titleMedium)
        val mem = device.optJSONObject("memory") ?: JSONObject()
        if (mem.optLong("totalKb") > 0) Text("可用内存 ${Formatter.formatFileSize(context, mem.optLong("availableKb") * 1024)} / ${Formatter.formatFileSize(context, mem.optLong("totalKb") * 1024)}", style = MaterialTheme.typography.bodySmall)
        val nodes = device.optJSONArray("f2fs") ?: JSONArray()
        for (i in 0 until nodes.length()) { val n = nodes.getJSONObject(i); Text("F2FS ${n.optString("device")} · 脏段 ${n.optLong("dirtySegments")}", style = MaterialTheme.typography.bodySmall) }
        if (device.length() == 0) Text("连接 Root 后读取", style = MaterialTheme.typography.bodySmall)
    } }
}
@Composable internal fun ToolboxEditor(key: String, config: JSONObject, customRules: String, packages: List<Pair<String, String>>, dismiss: () -> Unit, save: (String) -> Unit) {
    val schedule = key.startsWith("schedule:")
    val packageList = key.endsWith("Packages") || key == "processWhitelist"
    val task = if (schedule) config.optJSONObject("tasks")?.optJSONObject(key.substringAfter(':')) ?: JSONObject() else JSONObject()
    var value by remember(key) { mutableStateOf(if (schedule) task.optString("time", "02:30") else if (key == "customRules") customRules else config.optString(key)) }
    var days by remember(key) { mutableStateOf(task.optInt("intervalDays", 1).toString()) }
    var query by remember(key) { mutableStateOf("") }
    val choices = when (key) {
        "processMode" -> listOf("kill" to "结束后台进程", "freeze" to "冻结缓存进程", "oom" to "调整回收优先级")
        "compilerFilter" -> listOf("verify" to "验证代码", "speed-profile" to "按使用情况编译", "speed" to "速度优先", "everything" to "完整编译")
        else -> emptyList()
    }
    val titles = mapOf("processPackages" to "进程管理应用", "processWhitelist" to "进程保护名单", "databasePackages" to "数据库应用", "compilePackages" to "编译应用",
        "memoryThreshold" to "内存占用阈值（10–99%）", "dirtyThreshold" to "F2FS 脏段阈值", "gcSeconds" to "F2FS 运行时限（1–60 秒）", "fragmentDays" to "碎片保留天数",
        "processMode" to "进程模式", "pressureIntervalSeconds" to "后台检查间隔（秒）", "compilerFilter" to "编译模式", "organizerRules" to "文件归类规则", "downloadRules" to "下载转移规则", "customRules" to "额外清理规则", "counterReset" to "次数清零阈值")
    BaiZeDialog(onDismissRequest = dismiss, title = { Text(if (schedule) "${ToolboxCatalog.task(key.substringAfter(':')).title}计划" else titles[key].orEmpty()) },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (key in listOf("organizerRules", "downloadRules")) Text("每行一条：来源+*.apk&&*.zip+目标。支持来源目录通配符。例：/sdcard/Download+*.apk+/sdcard/BaiZe归类/安装包。转移后可在功能页撤销。", style = MaterialTheme.typography.bodySmall)
                if (key == "customRules") Text("每行：目录绝对路径|保留天数。此名单增加清理规则；全局保护名单继续优先。", style = MaterialTheme.typography.bodySmall)
                if (key == "processMode") Text("仅处理所选应用的后台进程，受保护应用会被跳过。冻结和回收优先级可在详情页恢复。", style = MaterialTheme.typography.bodySmall)
                if (key == "compilerFilter") Text("所选模式需由当前系统支持，执行结果中会保留系统返回信息。", style = MaterialTheme.typography.bodySmall)
                if (key == "databasePackages") Text("只处理未运行应用的标准 SQLite 数据库；跳过加密数据库和未提交事务。", style = MaterialTheme.typography.bodySmall)
                if (!packageList && choices.isEmpty()) OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(if (schedule) "时间 HH:mm" else "设置值") }, minLines = if (key.endsWith("Rules")) 3 else 1)
                if (schedule) OutlinedTextField(days, { days = it }, Modifier.fillMaxWidth(), label = { Text("间隔天数（1–30）") })
                if (packageList) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索应用") }, singleLine = true)
                    TextButton(onClick = { value = "" }) { Text("清空选择") }
                }
            }
            items(choices) { (code, title) ->
                Row(Modifier.fillMaxWidth().clickable { value = code }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(value == code, null); Text(title, Modifier.padding(start = 8.dp))
                }
            }
            if (packageList) items(packages.filter { (it.first + it.second).contains(query, true) }, key = { it.first }) { (pkg, label) ->
                val selected = value.split(Regex("[\\s,，;；]+")).filter(String::isNotBlank).toSet()
                Row(Modifier.fillMaxWidth().clickable { value = (if (pkg in selected) selected - pkg else selected + pkg).sorted().joinToString("\n") }, verticalAlignment = Alignment.CenterVertically) {
                    ApplicationIcon(pkg, label, Modifier.size(38.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) { Text(label); Text(pkg, style = MaterialTheme.typography.labelSmall) }
                    Checkbox(pkg in selected, null)
                }
            }
        } },
        confirmButton = { BaiZeDialogButton(onClick = { save(if (schedule) "$value|$days" else value) }) { Text("保存") } },
        dismissButton = { BaiZeDialogButton(onClick = dismiss) { Text("取消") } })
}
