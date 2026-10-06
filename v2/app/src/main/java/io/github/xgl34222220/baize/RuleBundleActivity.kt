package io.github.xgl34222220.baize

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RuleBundleActivity : ComponentActivity() {
    private val appearance: AppearanceViewModel by viewModels()
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var current by mutableStateOf("内置规则")
    private var differences by mutableStateOf(emptyList<String>())
    private var staged by mutableStateOf<VerifiedRuleBundle?>(null)
    private var rule by mutableStateOf("/storage/emulated/0/Download/*.tmp|7")
    private var preview by mutableStateOf<CustomRulePreview?>(null)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask {
            val raw = contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val read = input.read(buffer); if (read < 0) break; check(output.size() + read <= IndependentRuleBundles.MAX_BYTES) { "规则包超过 8 MiB" }; output.write(buffer, 0, read) }
                output.toByteArray()
            } ?: error("无法读取规则包")
            val store = IndependentRuleBundles.forContext(this)
            val verified = store.verify(raw)
            val old = store.active()?.files
            differences = verified.files.map { (name, bytes) ->
                val before = (old?.get(name) ?: assets.open(name).use { it.readBytes() }).toString(Charsets.UTF_8).lineSequence().toSet()
                val after = bytes.toString(Charsets.UTF_8).lineSequence().toSet()
                "$name · 新增 ${ (after - before).size } 行 / 移除 ${ (before - after).size } 行"
            }
            staged = verified
            "签名验证通过，等待确认启用"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setContent {
            val settings by appearance.settings.collectAsState()
            BaiZeTheme(settings) {
                Scaffold(containerColor = BaiZeTokens.colors.surfaceBase,
                    topBar = { DetailPageHeader("规则版本与试跑", "本地导入 · 验签 · 可回滚", ::finish) {} }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { DetailGlassPanel {
                            Text("当前：$current", style = MaterialTheme.typography.titleMedium)
                            Text("只接受由当前白泽应用签名证书验证的规则包。导入仅影响前台扫描，下次扫描启用；模块自动任务继续使用模块规则。损坏或不可信规则回退到内置规则。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { picker.launch(arrayOf("application/json", "application/octet-stream")) }, enabled = !busy) { Text("选择本地规则包") }
                            TextButton(onClick = { runTask { IndependentRuleBundles.forContext(this@RuleBundleActivity).rollback(); "已回滚至上一版，下次扫描生效" } }, enabled = !busy) { Text("回滚上一版") }
                            TextButton(onClick = { runTask { IndependentRuleBundles.forContext(this@RuleBundleActivity).useBundled(); "已使用内置规则，下次扫描生效" } }, enabled = !busy) { Text("使用内置规则") }
                            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        } }
                        item { DetailGlassPanel {
                            Text("自定义规则先试跑", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(rule, { rule = it; preview = null }, enabled = !busy, label = { Text("路径模式|保留天数") }, modifier = Modifier.fillMaxWidth())
                            Text("试跑只读系统索引，不删除文件；仅支持明确共享目录下的文件名通配。保护名单与风险分级仍由正式扫描核对。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { val snapshot = rule; runTask {
                                LocalCustomRulePreview.parse(snapshot)
                                preview = LocalCustomRulePreview.preview(snapshot, StorageMediaRepository.scanIndex(this@RuleBundleActivity))
                                "只读试跑完成"
                            } }, enabled = !busy) { Text("预览命中") }
                            TextButton(enabled = !busy, onClick = { runTask {
                                val target = File(filesDir, "custom-preview-enabled.rules")
                                if (target.exists()) IndependentRuleBundles.atomicWrite(File(filesDir, "custom-preview-disabled.rules"), target.readBytes())
                                IndependentRuleBundles.atomicWrite(target, ByteArray(0)); preview = null
                                "已停用全部试跑自定义规则，下次扫描生效"
                            } }) { Text("停用全部试跑规则") }
                            preview?.let { result ->
                                Text("命中 ${result.matches.size} 项 · ${android.text.format.Formatter.formatFileSize(this@RuleBundleActivity, result.matches.sumOf { it.verifiedBytes })}")
                                if (!result.complete) Text("索引或身份核对不完整，不能启用此规则")
                                result.matches.take(30).forEach { BaiZePathText(it.path) }
                                if (result.matches.size > 30) Text("仅展示前 30 项")
                                TextButton(enabled = !busy && result.complete && result.rule == rule.trim(), onClick = { runTask {
                                    val target = File(filesDir, "custom-preview-enabled.rules")
                                    val old = if (target.exists()) target.readLines() else emptyList()
                                    val lines = (old + result.rule).distinct().joinToString("\n")
                                    check(lines.toByteArray().size <= 64 * 1024) { "自定义规则已达容量上限" }
                                    IndependentRuleBundles.atomicWrite(target, lines.toByteArray())
                                    preview = null
                                    "已加入前台自定义规则；下次正式扫描仍需逐项确认清理"
                                } }) { Text("确认启用已预览规则") }
                            }
                        } }
                    }
                }
                staged?.let { bundle -> BaiZeDialog(onDismissRequest = { staged = null }, title = { Text("启用规则版本 ${bundle.version}？") },
                    text = { Column { Text("当前 $current → ${bundle.version}。包含 ${bundle.files.size} 个完整规则文件。旧版本保留用于回滚，已有清理授权不扩大。")
                        differences.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } }, confirmButton = { BaiZeDialogButton(onClick = { staged = null; runTask {
                        IndependentRuleBundles.forContext(this@RuleBundleActivity).install(bundle); "已启用版本 ${bundle.version}，下次前台扫描生效"
                    } }) { Text("启用") } }, dismissButton = { BaiZeDialogButton(onClick = { staged = null }) { Text("取消") } }) }
            }
        }
    }
    private fun refresh() { current = runCatching { IndependentRuleBundles.forContext(this).active()?.version?.toString() ?: "内置规则" }.getOrDefault("内置规则") }
    private fun runTask(action: () -> String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            status = withContext(Dispatchers.IO) { runCatching(action).getOrElse { it.message ?: "操作失败，原版本保留" } }
            refresh(); busy = false
        }
    }
}
