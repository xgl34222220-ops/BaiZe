package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityWhitelistBinding
import io.github.xgl34222220.baize.root.BaiZeProfileRootService
import io.github.xgl34222220.baize.root.IProfileRootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WhitelistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWhitelistBinding
    private val allApps = mutableListOf<AppEntry>()
    private val adapter = AppAdapter { entry, checked ->
        entry.selected = checked
        updateSelectionSummary()
    }

    private var service: IProfileRootService? = null
    private var serviceBound = false
    private var appsLoaded = false
    private var whitelistLoaded = false
    private var selectedPackages: Set<String> = emptySet()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            binding.saveButton.isEnabled = true
            loadWhitelist()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            binding.saveButton.isEnabled = false
            binding.statusText.text = "Root 服务已断开，当前选择尚未写入清理引擎"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.saveButton.isEnabled = false
        binding.clearButton.setOnClickListener {
            allApps.forEach { it.selected = false }
            applyFilter()
            updateSelectionSummary()
        }
        binding.saveButton.setOnClickListener { saveWhitelist() }
        binding.showSystemSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }
        binding.queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadInstalledApps()
        connectService()
    }

    private fun connectService() {
        binding.statusText.text = "正在连接 Root 服务并读取白名单…"
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
            serviceBound = true
        }.onFailure {
            serviceBound = false
            binding.statusText.text = "Root 服务连接失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val manager = packageManager
                val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    manager.getInstalledApplications(0)
                }
                installed.asSequence()
                    .filter { it.packageName != packageName }
                    .map { info ->
                        AppEntry(
                            packageName = info.packageName,
                            label = runCatching { manager.getApplicationLabel(info).toString() }
                                .getOrDefault(info.packageName),
                            system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            selected = false
                        )
                    }
                    .sortedWith(compareBy<AppEntry> { it.system }.thenBy { it.label.lowercase() })
                    .toList()
            }
            allApps.clear()
            allApps += apps
            appsLoaded = true
            applyLoadedWhitelist()
            applyFilter()
        }
    }

    private fun loadWhitelist() {
        val rootService = service ?: return
        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { rootService.getWhitelistPackages() }
            }.getOrNull()
            selectedPackages = raw?.let { value ->
                runCatching {
                    val array = JSONArray(value)
                    buildSet {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.getOrDefault(emptySet())
            } ?: emptySet()
            whitelistLoaded = true
            applyLoadedWhitelist()
            applyFilter()
        }
    }

    private fun applyLoadedWhitelist() {
        if (!appsLoaded || !whitelistLoaded) return
        allApps.forEach { it.selected = it.packageName in selectedPackages }
        updateSelectionSummary()
    }

    private fun applyFilter() {
        if (!appsLoaded) return
        val query = binding.queryInput.text?.toString().orEmpty().trim().lowercase()
        val showSystem = binding.showSystemSwitch.isChecked
        val visible = allApps.filter { app ->
            (showSystem || !app.system) &&
                (query.isBlank() || app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query))
        }
        adapter.submit(visible)
        binding.emptyText.visibility = if (visible.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        updateSelectionSummary()
    }

    private fun updateSelectionSummary() {
        val selected = allApps.count { it.selected }
        val visible = adapter.itemCount
        binding.selectionText.text = "已保护 $selected 个应用 · 当前显示 $visible 个"
        if (appsLoaded && whitelistLoaded && service != null) {
            binding.statusText.text = "选择应用后点击保存；白泽会保护其内部数据与 Android/data 目录。"
        }
    }

    private fun saveWhitelist() {
        val rootService = service ?: return
        val packages = allApps.asSequence().filter { it.selected }.map { it.packageName }.sorted().toList()
        val payload = JSONArray(packages).toString()
        binding.saveButton.isEnabled = false
        binding.statusText.text = "正在写入清理引擎白名单…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { rootService.saveWhitelistPackages(payload) }
            }
            result.onSuccess { raw ->
                val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                binding.statusText.text = if (json.optBoolean("success")) {
                    "已保存 ${json.optInt("count", packages.size)} 个应用，下一次扫描立即生效。"
                } else {
                    "保存失败：${json.optString("message", json.optString("error", "未知错误"))}"
                }
            }.onFailure {
                binding.statusText.text = "保存失败：${it.message ?: it.javaClass.simpleName}"
            }
            binding.saveButton.isEnabled = service != null
        }
    }

    override fun onDestroy() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val system: Boolean,
        var selected: Boolean
    )

    private class AppAdapter(
        private val onChecked: (AppEntry, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppEntry> = emptyList()

        fun submit(next: List<AppEntry>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val horizontal = (18 * density + 0.5f).toInt()
            val vertical = (10 * density + 0.5f).toInt()
            val checkBox = MaterialCheckBox(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(horizontal, vertical, horizontal, vertical)
                minHeight = (62 * density + 0.5f).toInt()
                isClickable = true
            }
            return Holder(checkBox)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.text = buildString {
                append(item.label)
                append('\n')
                append(item.packageName)
                if (item.system) append(" · 系统应用")
            }
            holder.checkBox.isChecked = item.selected
            holder.checkBox.alpha = if (item.system) 0.82f else 1f
            holder.checkBox.setOnCheckedChangeListener { _, checked -> onChecked(item, checked) }
            holder.checkBox.setOnClickListener {
                item.selected = holder.checkBox.isChecked
                onChecked(item, item.selected)
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(val checkBox: MaterialCheckBox) : RecyclerView.ViewHolder(checkBox)
    }
}
