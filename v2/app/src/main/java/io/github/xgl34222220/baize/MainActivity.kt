package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityMainBinding
import io.github.xgl34222220.baize.root.BaiZeRootService
import io.github.xgl34222220.baize.root.IBaiZeRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var rootService: IBaiZeRootService? = null
    private var bindingRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootService = IBaiZeRootService.Stub.asInterface(service)
            bindingRequested = true
            renderConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            bindingRequested = false
            renderDisconnected("Root 服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = "v${BuildConfig.VERSION_NAME} · 原生 App / Root Binder"
        binding.connectButton.setOnClickListener { connectRootService() }
        binding.scanButton.setOnClickListener { runPreviewScan() }
        binding.cancelButton.setOnClickListener {
            rootService?.cancelCurrentTask()
            binding.resultText.text = "正在请求停止…"
        }
        renderDisconnected("尚未连接 Root 服务")
    }

    private fun rootIntent(): Intent = Intent(this, BaiZeRootService::class.java)
        .addCategory(RootService.CATEGORY_DAEMON_MODE)

    private fun connectRootService() {
        if (rootService != null) {
            renderConnected()
            return
        }
        binding.statusText.text = "正在请求 Root 权限并启动服务…"
        binding.connectButton.isEnabled = false
        try {
            RootService.bind(rootIntent(), connection)
            bindingRequested = true
        } catch (error: Throwable) {
            bindingRequested = false
            renderDisconnected(error.message ?: "Root 服务启动失败")
        }
    }

    private fun renderConnected() {
        binding.connectButton.isEnabled = true
        binding.scanButton.isEnabled = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { rootService?.ping().orEmpty() }
            }
            val json = result.getOrNull()?.let(::JSONObject)
            val uid = json?.optInt("uid", -1) ?: -1
            val module = when {
                json?.optBoolean("moduleV2") == true -> "v2 模块已安装"
                json?.optBoolean("moduleV1") == true -> "检测到 v1 模块"
                else -> "模块桥接未安装"
            }
            binding.statusText.text = if (uid == 0) "Root 服务已连接 · $module" else "服务已连接，但 UID=$uid"
        }
    }

    private fun renderDisconnected(message: String) {
        binding.statusText.text = message
        binding.connectButton.isEnabled = true
        binding.scanButton.isEnabled = false
        binding.cancelButton.isEnabled = false
    }

    private fun runPreviewScan() {
        val service = rootService ?: return
        binding.scanButton.isEnabled = false
        binding.cancelButton.isEnabled = true
        binding.progressIndicator.show()
        binding.resultText.text = "正在非递归扫描缓存候选…"

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { service.scanPreview() }
            }
            binding.progressIndicator.hide()
            binding.scanButton.isEnabled = rootService != null
            binding.cancelButton.isEnabled = false

            result.onSuccess { raw ->
                val json = JSONObject(raw)
                val cancelled = json.optBoolean("cancelled")
                val elapsed = json.optLong("elapsedMs")
                val total = json.optInt("totalCandidates")
                val packages = json.optInt("packagesVisited")
                val internal = json.optInt("internalCache")
                val code = json.optInt("internalCodeCache")
                val external = json.optInt("externalCache")
                binding.resultText.text = buildString {
                    append(if (cancelled) "扫描已停止" else "扫描完成")
                    append(" · ${elapsed}ms\n")
                    append("访问应用目录：$packages\n")
                    append("内部缓存：$internal\n")
                    append("代码缓存：$code\n")
                    append("外部缓存：$external\n")
                    append("候选目录：$total\n\n")
                    append("Alpha 1 仅验证原生扫描与 Binder 通信，不执行删除。")
                }
            }.onFailure { error ->
                binding.resultText.text = "扫描失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    override fun onDestroy() {
        if (bindingRequested) {
            runCatching { RootService.unbind(connection) }
        }
        super.onDestroy()
    }
}
