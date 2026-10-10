package io.github.xgl34222220.baize

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.*

class ShizukuCacheActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private val model: ShizukuCacheViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.initialize()
        setContent {
            val settings by appearance.settings.collectAsState()
            val state by model.state.collectAsState()
            val shownApps = remember(state.apps, state.query) {
                state.apps.filter { state.query.isBlank() || it.label.contains(state.query, true) || it.packageName.contains(state.query, true) }
            }
            var confirmClean by rememberSaveable { mutableStateOf(false) }
            var confirmBack by rememberSaveable { mutableStateOf(false) }
            fun back() { if (state.busy) confirmBack = true else finish() }
            // 空闲时交给系统返回，保留预测性返回动画；任务进行中才拦截并确认。
            BackHandler(enabled = state.busy) { back() }
            BaiZeTheme(settings) {
                Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
                    topBar = { DetailPageHeader("免 Root 缓存清理", "通过 Shizuku · 逐个应用确认结果", { back() }) {} }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { DetailGlassPanel {
                            Text("仅处理当前用户所选应用的缓存。Shizuku 需要安装、启动并具备系统清缓存权限；无线调试启动不保证该权限。系统不允许时，可打开每个应用的系统缓存设置手动清理。", style = MaterialTheme.typography.bodySmall)
                            Text(state.status, style = MaterialTheme.typography.bodyMedium)
                            if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::stop) { Text("停止清理") } }
                            else {
                                TextButton(onClick = model::connect) { Text("连接 Shizuku") }
                                TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) } }) { Text("Shizuku 下载与启动说明") }
                            }
                            if (!state.protectionKnown) {
                                Text("保护名单尚未核对，清理暂不可用。", style = MaterialTheme.typography.bodySmall)
                                if (state.localModeAvailable) TextButton(onClick = model::enableLocalMode, enabled = !state.busy) { Text("使用本地保护规则") }
                                else TextButton(onClick = model::reconnectProtection, enabled = !state.busy) { Text("连接 Root，核对保护名单") }
                            }
                            TextButton(onClick = { confirmClean = true }, enabled = state.connected && state.supported && state.protectionKnown && !state.busy && state.selected.isNotEmpty()) {
                                Text("清除所选 ${state.selected.size} 个应用的缓存")
                            }
                        } }
                        if (state.results.isNotEmpty()) item { DetailGlassPanel {
                            Text("清理结果", style = MaterialTheme.typography.titleMedium)
                            state.results.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("系统未返回精确释放字节数；缓存可能由应用重新生成。", style = MaterialTheme.typography.bodySmall)
                        } }
                        item { OutlinedTextField(state.query, model::query, label = { Text("搜索应用") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                        items(shownApps, key = { it.packageName }, contentType = { "shizuku-app" }) { app ->
                            DetailGlassPanel {
                                Row(Modifier.fillMaxWidth().toggleable(app.packageName in state.selected,
                                    enabled = !state.busy && !app.isProtected, role = Role.Checkbox, onValueChange = { model.toggle(app.packageName) })) {
                                    Checkbox(app.packageName in state.selected, null, enabled = !state.busy && !app.isProtected)
                                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                        if (app.isProtected) Text("受保护或保护名单未确认", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                TextButton(enabled = !state.busy && !app.isProtected,
                                    modifier = Modifier.semantics { contentDescription = "系统缓存设置：${app.label}" },
                                    onClick = {
                                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${app.packageName}"))) }.onFailure {
                                            Toast.makeText(this@ShizukuCacheActivity, "无法打开系统设置，请在系统应用管理中查找该应用", Toast.LENGTH_LONG).show()
                                        }
                                    }) { Text("系统缓存设置") }
                            }
                        }
                        if (state.apps.isEmpty()) item { Text("暂无可选的第三方应用", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (confirmClean) BaiZeDialog(onDismissRequest = { confirmClean = false }, title = { Text("清除所选应用缓存？") },
                    text = { Text(state.apps.filter { it.packageName in state.selected }.joinToString("\n") { it.label } + "\n保留应用数据；缓存清理完成后无法撤回。") },
                    confirmButton = { val haptics = io.github.xgl34222220.baize.ui.components.rememberBaiZeHaptics(); BaiZeDialogButton(onClick = { haptics.confirmDelete(); confirmClean = false; model.cleanSelected() }) { Text("确认清缓存") } },
                    dismissButton = { BaiZeDialogButton(onClick = { confirmClean = false }) { Text("取消") } })
                if (confirmBack) BaiZeDialog(onDismissRequest = { confirmBack = false }, title = { Text("停止清理并返回？") },
                    text = { Text("已经完成的缓存清理会保留。") },
                    confirmButton = { BaiZeDialogButton(onClick = { model.stop(); finish() }) { Text("停止并返回") } },
                    dismissButton = { BaiZeDialogButton(onClick = { confirmBack = false }) { Text("继续清理") } })
            }
        }
    }
    override fun onResume() { super.onResume(); model.refreshApps() }
}
