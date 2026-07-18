package io.github.xgl34222220.baize

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.databinding.ActivityWhitelistBinding
import io.github.xgl34222220.baize.databinding.ItemWhitelistAppBinding
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
    private val iconCache = LruCache<String, Drawable>(48)
    private var service: IProfileRootService? = null
    private var serviceBound = false
    private var currentFilter = Filter.ALL
    private var loading = true
    private var localFallbackStarted = false

    private val adapter = AppAdapter(
        iconLoader = ::loadIcon,
        onToggle = { entry, checked ->
            entry.selected = checked
            applyFilter()
        }
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProfileRootService.Stub.asInterface(binder)
            serviceBound = true
            binding.saveButton.isEnabled = true
            loadCatalogAndWhitelist()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.backButton.setOnClickListener { finish() }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.appList.setHasFixedSize(true)
        binding.saveButton.isEnabled = false
        binding.selectVisibleButton.isEnabled = false
        binding.clearButton.isEnabled = false

        binding.clearButton.setOnClickListener {
            allApps.forEach { it.selected = false }
            applyFilter()
        }
        binding.selectVisibleButton.setOnClickListener {
            val visible = filteredApps()
            val shouldSelect = visible.any { !it.selected }
            visible.forEach { it.selected = shouldSelect }
            applyFilter()
        }
        binding.saveButton.setOnClickListener { saveWhitelist() }

        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.filterUser -> Filter.USER
                R.id.filterSystem -> Filter.SYSTEM
                R.id.filterProtected -> Filter.PROTECTED
                else -> Filter.ALL
            }
            applyFilter()
        }
        binding.filterAll.isChecked = true

        binding.queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        showLoading("正在通过 Root 服务读取全部已安装应用…")
        connectService()
        binding.root.postDelayed({
            if (service == null && loading && !isFinishing) {
                lifecycleScope.launch { loadLocalFallback("Root 服务连接较慢，先显示系统可见应用…") }
            }
        }, ROOT_FALLBACK_DELAY_MS)
    }

    private fun applySystemBarInsets() {
        val headerTop = binding.headerContainer.paddingTop
        val rootBottom = binding.whitelistRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.whitelistRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.headerContainer.updatePadding(top = headerTop + bars.top)
            binding.whitelistRoot.updatePadding(bottom = rootBottom + bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = !ThemeManager.isDark(this@WhitelistActivity)
            isAppearanceLightNavigationBars = !ThemeManager.isDark(this@WhitelistActivity)
        }
        ViewCompat.requestApplyInsets(binding.whitelistRoot)
    }

    private fun connectService() {
        runCatching {
            RootService.bind(
                Intent(this, BaiZeProfileRootService::class.java)
                    .addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
        }.onFailure {
            serviceBound = false
            binding.statusText.text = "Root 服务连接失败：${it.message ?: it.javaClass.simpleName}"
            lifecycleScope.launch { loadLocalFallback("Root 不可用，正在读取系统可见应用…") }
        }
    }

    private fun loadCatalogAndWhitelist() {
        val rootService = service ?: return
        showLoading("正在读取应用目录与白名单…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val catalogRaw = runCatching { rootService.getInstalledPackageCatalog() }.getOrNull()
                val whitelistRaw = runCatching { rootService.getWhitelistPackages() }.getOrNull()
                val selected = parseWhitelist(whitelistRaw)
                val rootCatalog = parseRootCatalog(catalogRaw)
                val catalog = if (rootCatalog.isNotEmpty()) rootCatalog else loadLocalCatalog()
                val apps = buildEntries(catalog, selected)
                CatalogResult(
                    apps = apps,
                    selectedCount = selected.size,
                    source = if (rootCatalog.isNotEmpty()) "Root 完整目录" else "系统兼容目录"
                )
            }
            renderCatalog(result)
        }
    }

    private suspend fun loadLocalFallback(message: String) {
        if (localFallbackStarted || service != null) return
        localFallbackStarted = true
        showLoading(message)
        val result = withContext(Dispatchers.IO) {
            val catalog = loadLocalCatalog()
            CatalogResult(buildEntries(catalog, emptySet()), 0, "系统兼容目录")
        }
        if (service == null) renderCatalog(result)
    }

    private fun renderCatalog(result: CatalogResult) {
        allApps.clear()
        allApps += result.apps
        loading = false
        binding.loadingIndicator.visibility = View.GONE
        binding.clearButton.isEnabled = allApps.isNotEmpty()
        binding.statusText.text = if (allApps.isEmpty()) {
            "仍未读取到应用。请确认模块已刷入并授予 Root，然后返回重试。"
        } else {
            "已从${result.source}读取 ${allApps.size} 个应用；已保存保护 ${result.selectedCount} 个。"
        }
        applyFilter()
    }

    private fun showLoading(message: String) {
        loading = true
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE
        binding.statusText.text = message
        binding.selectionText.text = "正在准备应用列表…"
    }

    private fun parseRootCatalog(raw: String?): List<CatalogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("packages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val packageName = item.optString("packageName").trim()
                    if (PACKAGE_NAME.matches(packageName) && packageName != this@WhitelistActivity.packageName) {
                        add(CatalogEntry(packageName, item.optBoolean("system")))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseWhitelist(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val packageName = array.optString(index).trim()
                    if (PACKAGE_NAME.matches(packageName)) add(packageName)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun loadLocalCatalog(): List<CatalogEntry> {
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        return installed.asSequence()
            .filter { it.packageName != packageName }
            .map { info ->
                CatalogEntry(
                    packageName = info.packageName,
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    private fun buildEntries(catalog: List<CatalogEntry>, selected: Set<String>): List<AppEntry> {
        val manager = packageManager
        return catalog.asSequence()
            .distinctBy { it.packageName }
            .map { entry ->
                val info = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        manager.getApplicationInfo(entry.packageName, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        manager.getApplicationInfo(entry.packageName, 0)
                    }
                }.getOrNull()
                val label = info?.let { runCatching { manager.getApplicationLabel(it).toString() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                AppEntry(
                    packageName = entry.packageName,
                    label = label,
                    system = entry.system || (info?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0),
                    selected = entry.packageName in selected
                )
            }
            .sortedWith(
                compareByDescending<AppEntry> { it.selected }
                    .thenBy { it.system }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    private fun filteredApps(): List<AppEntry> {
        val query = binding.queryInput.text?.toString().orEmpty().trim().lowercase()
        return allApps.filter { app ->
            val filterMatch = when (currentFilter) {
                Filter.ALL -> true
                Filter.USER -> !app.system
                Filter.SYSTEM -> app.system
                Filter.PROTECTED -> app.selected
            }
            filterMatch && (
                query.isBlank() ||
                    app.label.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
                )
        }
    }

    private fun applyFilter() {
        if (loading) return
        val visible = filteredApps()
        adapter.submit(visible)
        val selected = allApps.count { it.selected }
        val userCount = allApps.count { !it.system }
        val systemCount = allApps.size - userCount
        binding.selectionText.text = "已保护 $selected 个 · 用户应用 $userCount 个 · 系统应用 $systemCount 个"
        binding.emptyText.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.appList.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        binding.selectVisibleButton.isEnabled = visible.isNotEmpty()
        binding.selectVisibleButton.text = if (visible.isNotEmpty() && visible.all { it.selected }) {
            "取消当前 ${visible.size} 个"
        } else {
            "全选当前 ${visible.size} 个"
        }
        binding.clearButton.isEnabled = selected > 0
    }

    private fun saveWhitelist() {
        val rootService = service ?: return
        val packages = allApps.asSequence()
            .filter { it.selected }
            .map { it.packageName }
            .sorted()
            .toList()
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
                    "已保护 ${json.optInt("count", packages.size)} 个应用，下一次扫描立即生效。"
                } else {
                    "保存失败：${json.optString("message", json.optString("error", "未知错误"))}"
                }
            }.onFailure {
                binding.statusText.text = "保存失败：${it.message ?: it.javaClass.simpleName}"
            }
            binding.saveButton.isEnabled = service != null
        }
    }

    private fun loadIcon(packageName: String): Drawable {
        iconCache.get(packageName)?.let { return it }
        val icon = runCatching { packageManager.getApplicationIcon(packageName) }
            .getOrElse { ContextCompat.getDrawable(this, R.drawable.ic_baize) ?: packageManager.defaultActivityIcon }
        iconCache.put(packageName, icon)
        return icon
    }

    override fun onDestroy() {
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    private enum class Filter { ALL, USER, SYSTEM, PROTECTED }

    private data class CatalogEntry(val packageName: String, val system: Boolean)

    private data class CatalogResult(
        val apps: List<AppEntry>,
        val selectedCount: Int,
        val source: String
    )

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val system: Boolean,
        var selected: Boolean
    )

    private class AppAdapter(
        private val iconLoader: (String) -> Drawable,
        private val onToggle: (AppEntry, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppEntry> = emptyList()

        fun submit(next: List<AppEntry>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemWhitelistAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(private val binding: ItemWhitelistAppBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: AppEntry) {
                binding.appName.text = item.label
                binding.packageName.text = item.packageName
                binding.appIcon.setImageDrawable(iconLoader(item.packageName))
                binding.typeText.text = if (item.system) "系统" else "用户"
                binding.selectedCheck.setOnCheckedChangeListener(null)
                binding.selectedCheck.isChecked = item.selected

                val primaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
                val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
                val outline = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
                binding.root.setCardBackgroundColor(if (item.selected) primaryContainer else surface)
                binding.root.strokeColor = outline
                binding.root.strokeWidth = if (item.selected) dp(binding.root, 1) else 0

                binding.selectedCheck.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
                binding.root.setOnClickListener { onToggle(item, !item.selected) }
            }

            private fun dp(view: View, value: Int): Int =
                (value * view.resources.displayMetrics.density + 0.5f).toInt()
        }
    }

    companion object {
        private const val ROOT_FALLBACK_DELAY_MS = 3_500L
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    }
}
