from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"missing patch start: {label} in {path}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"missing patch end: {label} in {path}")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


root = Path("v2")
dashboard = root / "app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
manifest = root / "app/src/main/AndroidManifest.xml"
aidl = root / "app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
root_service = root / "app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt"
theme_manager = root / "app/src/main/java/io/github/xgl34222220/baize/ThemeManager.kt"
polish = root / "app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt"
layout = root / "app/src/main/res/layout/activity_dashboard.xml"
build_gradle = root / "app/build.gradle.kts"
module_prop = root / "module/module.prop"
customize = root / "module/customize.sh"
package_script = root / "scripts/package-module.sh"

# Version and dashboard actions.
replace_once(dashboard, '        binding.versionText.text = "Alpha 12.1"\n', '        binding.versionText.text = "Alpha 13"\n', "dashboard version")
replace_once(
    dashboard,
    '''            readServiceStatus()
            loadSchedulerConfig()
            refreshModuleState()
''',
    '''            readServiceStatus()
            loadSchedulerConfig()
            refreshModuleState()
            refreshWhitelist()
''',
    "refresh whitelist after root connection",
)
replace_once(
    dashboard,
    '''        binding.reconnectButton.setOnClickListener {
            if (serviceBound) runCatching { RootService.unbind(connection) }
            profileService = null
            serviceBound = false
            connectService()
        }
        binding.savePlanButton.setOnClickListener { saveSchedulerConfig() }
''',
    '''        binding.reconnectButton.setOnClickListener {
            if (serviceBound) runCatching { RootService.unbind(connection) }
            profileService = null
            serviceBound = false
            connectService()
        }
        binding.manageWhitelistButton.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        binding.saveProtectionButton.setOnClickListener {
            saveSettingsPatch(
                notification = binding.notificationSwitch.isChecked,
                maxFileMb = binding.largeFileSlider.value.toInt()
            )
        }
        binding.crashReportButton.setOnClickListener { showCrashDialog() }
        binding.savePlanButton.setOnClickListener { saveSchedulerConfig() }
''',
    "settings actions",
)
replace_between(
    dashboard,
    "    private fun refreshWhitelist() {\n",
    "    private fun readStringSetCompat(key: String): Set<String> {\n",
    '''    private fun refreshWhitelist() {
        val rootService = profileService
        if (rootService == null) {
            binding.whitelistText.text = "白名单：等待 Root 服务连接"
            binding.manageWhitelistButton.isEnabled = false
            return
        }
        binding.manageWhitelistButton.isEnabled = true
        lifecycleScope.launch {
            val raw = runCatching {
                withContext(Dispatchers.IO) { rootService.getWhitelistPackages() }
            }.getOrNull()
            val count = raw?.let {
                runCatching { org.json.JSONArray(it).length() }.getOrDefault(0)
            } ?: 0
            binding.whitelistText.text = if (count > 0) {
                "已保护 $count 个应用 · 内部数据与外部目录均跳过"
            } else {
                "尚未添加应用白名单"
            }
        }
    }

''',
    "root backed whitelist summary",
)
# Remove the now obsolete SharedPreferences compatibility helper entirely.
replace_between(
    dashboard,
    "    private fun readStringSetCompat(key: String): Set<String> {\n",
    "    private fun connectService() {\n",
    "",
    "remove local whitelist helper",
)

# Redesign settings page with obvious whitelist management and save actions.
settings_page = '''        <androidx.core.widget.NestedScrollView
            android:id="@+id/settingsPage"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:fillViewport="true"
            android:clipToPadding="false"
            android:visibility="gone">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingStart="18dp"
                android:paddingTop="14dp"
                android:paddingEnd="18dp"
                android:paddingBottom="126dp">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="偏好设置"
                    android:textAppearance="@style/TextAppearance.BaiZe.PageTitle" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="3dp"
                    android:text="外观、清理保护与服务管理"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:textSize="12sp" />

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="18dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="外观"
                            android:textAppearance="@style/TextAppearance.BaiZe.SectionTitle" />

                        <TextView
                            android:id="@+id/themeSummaryText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:text="正在读取当前主题"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="12sp" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/themeButton"
                            style="@style/Widget.BaiZe.Button.Outlined"
                            android:layout_width="match_parent"
                            android:layout_height="50dp"
                            android:layout_marginTop="14dp"
                            android:text="选择主题配色" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="18dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="清理保护"
                            android:textAppearance="@style/TextAppearance.BaiZe.SectionTitle" />

                        <TextView
                            android:id="@+id/whitelistText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="8dp"
                            android:text="白名单：等待 Root 服务连接"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="12sp" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/manageWhitelistButton"
                            style="@style/Widget.Material3.Button.FilledTonalButton"
                            android:layout_width="match_parent"
                            android:layout_height="52dp"
                            android:layout_marginTop="12dp"
                            android:text="管理应用白名单" />

                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/notificationSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="14dp"
                            android:text="任务完成后发送通知"
                            android:textColor="?attr/colorOnSurface" />

                        <TextView
                            android:id="@+id/largeFileText"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="14dp"
                            android:text="单文件保护上限 256 MB"
                            android:textColor="?attr/colorOnSurface"
                            android:textSize="13sp"
                            android:textStyle="bold" />

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="3dp"
                            android:text="超过上限的单个文件只统计，不会删除"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="10sp" />

                        <com.google.android.material.slider.Slider
                            android:id="@+id/largeFileSlider"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:value="256"
                            android:stepSize="16"
                            android:valueFrom="16"
                            android:valueTo="2048" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/saveProtectionButton"
                            android:layout_width="match_parent"
                            android:layout_height="54dp"
                            android:layout_marginTop="10dp"
                            android:text="保存保护设置" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="18dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="服务与诊断"
                            android:textAppearance="@style/TextAppearance.BaiZe.SectionTitle" />

                        <TextView
                            android:id="@+id/settingsStatusText"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="8dp"
                            android:text="正在连接 Root 服务"
                            android:textColor="?attr/colorOnSurfaceVariant"
                            android:textSize="12sp" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/reconnectButton"
                            style="@style/Widget.BaiZe.Button.Outlined"
                            android:layout_width="match_parent"
                            android:layout_height="50dp"
                            android:layout_marginTop="14dp"
                            android:text="重新连接 Root 服务" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/crashReportButton"
                            style="@style/Widget.BaiZe.Button.Outlined"
                            android:layout_width="match_parent"
                            android:layout_height="50dp"
                            android:layout_marginTop="10dp"
                            android:text="查看最近崩溃诊断" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>
            </LinearLayout>
        </androidx.core.widget.NestedScrollView>
'''
replace_between(
    layout,
    '        <androidx.core.widget.NestedScrollView\n            android:id="@+id/settingsPage"',
    '    </FrameLayout>\n\n    <io.github.xgl34222220.baize.ui.FloatingGlassDock',
    settings_page,
    "settings page layout",
)

# Whitelist Activity declaration and AIDL contract.
replace_once(
    manifest,
    '        <activity android:name=".CleanCenterActivity" android:exported="false" />\n',
    '        <activity android:name=".CleanCenterActivity" android:exported="false" />\n        <activity android:name=".WhitelistActivity" android:exported="false" android:windowSoftInputMode="adjustResize" />\n',
    "whitelist activity manifest",
)
aidl.write_text('''package io.github.xgl34222220.baize.root;

interface IProfileRootService {
    String ping();
    String getProfileCatalog();
    String scanProfile(String profile, String optionsJson);
    String getProfilePage(String snapshotId, int offset, int limit);
    String cleanProfileSelected(String snapshotId, String selectionJson, String optionsJson);

    String runModuleTask(String mode);
    String getModuleState();
    String getSchedulerConfig();
    String saveSchedulerConfig(String configJson);

    String getWhitelistPackages();
    String saveWhitelistPackages(String packagesJson);

    String getTaskState();
    void cancelCurrentTask();
}
''', encoding="utf-8")

# Root-backed whitelist storage. App selections are expanded to all Android user roots and written
# into the same whitelist.conf consumed by cleaner.sh, so the UI is no longer cosmetic.
replace_once(root_service, 'import org.json.JSONObject\n', 'import org.json.JSONArray\nimport org.json.JSONObject\n', "JSONArray import")
replace_once(
    root_service,
    '''        override fun getSchedulerConfig(): String = configJson()

        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())

        override fun getTaskState(): String {
''',
    '''        override fun getSchedulerConfig(): String = configJson()

        override fun saveSchedulerConfig(configJson: String?): String = saveConfig(configJson.orEmpty())

        override fun getWhitelistPackages(): String = this@BaiZeProfileRootService.whitelistPackagesJson()

        override fun saveWhitelistPackages(packagesJson: String?): String =
            this@BaiZeProfileRootService.persistWhitelistPackages(packagesJson.orEmpty())

        override fun getTaskState(): String {
''',
    "whitelist binder methods",
)
whitelist_helpers = r'''    private fun whitelistPackagesJson(): String = JSONArray(readWhitelistPackages().sorted()).toString()

    private fun persistWhitelistPackages(raw: String): String {
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return JSONObject().put("error", "invalid_json").put("message", "白名单格式无效").toString()
        }
        val packages = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val packageName = array.optString(index).trim()
            if (!PACKAGE_NAME.matches(packageName)) {
                return JSONObject().put("error", "invalid_package").put("package", packageName).toString()
            }
            packages += packageName
            if (packages.size > 500) {
                return JSONObject().put("error", "too_many_packages").put("limit", 500).toString()
            }
        }

        File(STATE_DIR).mkdirs()
        val sidecar = packages.sorted().joinToString("\n", postfix = if (packages.isEmpty()) "" else "\n")
        writeAtomic(File(WHITELIST_PACKAGES_FILE), sidecar)
        rebuildWhitelistFile(packages)
        return JSONObject()
            .put("success", true)
            .put("count", packages.size)
            .put("message", "应用白名单已写入清理引擎")
            .toString()
    }

    private fun readWhitelistPackages(): Set<String> {
        val sidecar = File(WHITELIST_PACKAGES_FILE)
        if (sidecar.isFile) {
            return sidecar.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { PACKAGE_NAME.matches(it) }
                .toSet()
        }

        val inferred = linkedSetOf<String>()
        File(WHITELIST_FILE).takeIf { it.isFile }?.forEachLine { raw ->
            val line = raw.trim()
            for (pattern in GENERATED_PATH_PATTERNS) {
                val packageName = pattern.matchEntire(line)?.groupValues?.getOrNull(1)
                if (!packageName.isNullOrBlank()) inferred += packageName
            }
        }
        return inferred
    }

    private fun rebuildWhitelistFile(packages: Set<String>) {
        val file = File(WHITELIST_FILE)
        val manual = mutableListOf<String>()
        var generatedSection = false
        if (file.isFile) {
            file.forEachLine { raw ->
                val line = raw.trim()
                when (line) {
                    APP_WHITELIST_BEGIN -> generatedSection = true
                    APP_WHITELIST_END -> generatedSection = false
                    else -> if (!generatedSection && line.startsWith("/") && !isGeneratedAppPath(line)) {
                        manual += line
                    }
                }
            }
        }

        val users = linkedSetOf("0")
        listOf("/data/user", "/data/user_de", "/data/media").forEach { root ->
            File(root).listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.mapTo(users) { it.name }
        }

        val output = buildString {
            append("# 白泽清理保护白名单。自定义绝对路径可继续逐行添加。\n")
            manual.distinct().sorted().forEach { append(it).append('\n') }
            if (manual.isNotEmpty()) append('\n')
            append(APP_WHITELIST_BEGIN).append('\n')
            for (packageName in packages.sorted()) {
                append("# app:").append(packageName).append('\n')
                for (user in users.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    append("/data/user/").append(user).append('/').append(packageName).append('\n')
                    append("/data/user_de/").append(user).append('/').append(packageName).append('\n')
                    append("/data/media/").append(user).append("/Android/data/").append(packageName).append('\n')
                }
            }
            append(APP_WHITELIST_END).append('\n')
        }
        writeAtomic(file, output)
    }

    private fun isGeneratedAppPath(path: String): Boolean =
        GENERATED_PATH_PATTERNS.any { it.matches(path.trimEnd('/')) }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp.${Process.myPid()}")
        temporary.writeText(text)
        temporary.setReadable(true, true)
        temporary.setWritable(true, true)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

'''
replace_once(root_service, '    private fun ensureConfig() {\n', whitelist_helpers + '    private fun ensureConfig() {\n', "whitelist persistence helpers")
replace_once(
    root_service,
    '''        private const val CONFIG_FILE = "$STATE_DIR/config.conf"

        private val MODULE_TASKS = setOf(
''',
    '''        private const val CONFIG_FILE = "$STATE_DIR/config.conf"
        private const val WHITELIST_FILE = "$STATE_DIR/whitelist.conf"
        private const val WHITELIST_PACKAGES_FILE = "$STATE_DIR/whitelist.packages"
        private const val APP_WHITELIST_BEGIN = "# BEGIN BAIZE APP WHITELIST"
        private const val APP_WHITELIST_END = "# END BAIZE APP WHITELIST"

        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
        private val GENERATED_PATH_PATTERNS = listOf(
            Regex("^/data/(?:user|user_de)/\\d+/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)$"),
            Regex("^/data/media/\\d+/Android/data/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)$")
        )

        private val MODULE_TASKS = setOf(
''',
    "whitelist constants",
)

# Real app picker and manager.
(root / "app/src/main/java/io/github/xgl34222220/baize/WhitelistActivity.kt").write_text(r'''package io.github.xgl34222220.baize

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
''', encoding="utf-8")

(root / "app/src/main/res/layout/activity_whitelist.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:paddingStart="18dp"
    android:paddingTop="14dp"
    android:paddingEnd="18dp"
    android:paddingBottom="18dp">

    <com.google.android.material.button.MaterialButton
        android:id="@+id/backButton"
        style="@style/Widget.BaiZe.Button.Outlined"
        android:layout_width="wrap_content"
        android:layout_height="46dp"
        android:text="返回" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="应用白名单"
        android:textAppearance="@style/TextAppearance.BaiZe.PageTitle" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="被选中的应用将完全跳过普通、规则、深度与卸载残留清理"
        android:textColor="?attr/colorOnSurfaceVariant"
        android:textSize="12sp" />

    <com.google.android.material.card.MaterialCardView
        style="@style/Widget.BaiZe.GlassCard"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="14dp">

            <EditText
                android:id="@+id/queryInput"
                android:layout_width="match_parent"
                android:layout_height="50dp"
                android:background="@android:color/transparent"
                android:hint="搜索应用名称或包名"
                android:imeOptions="actionDone"
                android:inputType="text"
                android:paddingStart="8dp"
                android:paddingEnd="8dp"
                android:singleLine="true"
                android:textColor="?attr/colorOnSurface"
                android:textColorHint="?attr/colorOnSurfaceVariant" />

            <com.google.android.material.switchmaterial.SwitchMaterial
                android:id="@+id/showSystemSwitch"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="显示系统应用"
                android:textColor="?attr/colorOnSurface" />
        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

    <TextView
        android:id="@+id/selectionText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:text="正在读取应用列表…"
        android:textColor="?attr/colorOnSurfaceVariant"
        android:textSize="12sp" />

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:layout_weight="1">

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/appList"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:paddingBottom="8dp" />

        <TextView
            android:id="@+id/emptyText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:gravity="center"
            android:text="没有匹配的应用"
            android:textColor="?attr/colorOnSurfaceVariant"
            android:visibility="gone" />
    </FrameLayout>

    <TextView
        android:id="@+id/statusText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="正在连接 Root 服务…"
        android:textColor="?attr/colorOnSurfaceVariant"
        android:textSize="11sp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:orientation="horizontal">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/clearButton"
            style="@style/Widget.BaiZe.Button.Outlined"
            android:layout_width="0dp"
            android:layout_height="52dp"
            android:layout_weight="1"
            android:text="全部取消" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/saveButton"
            android:layout_width="0dp"
            android:layout_height="52dp"
            android:layout_marginStart="10dp"
            android:layout_weight="1.4"
            android:text="保存白名单" />
    </LinearLayout>
</LinearLayout>
''', encoding="utf-8")

# Cleaner, calmer MIUI-style palette. Fixed themes follow system day/night; Monet remains optional.
themes = root / "app/src/main/res/values/themes.xml"
themes.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.BaiZe.Base" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorOnPrimary">#FFFFFF</item>
        <item name="colorOnPrimaryContainer">#15254A</item>
        <item name="colorOnSecondary">#FFFFFF</item>
        <item name="colorOnSecondaryContainer">#12383B</item>
        <item name="colorError">#BA1A1A</item>
        <item name="colorOnError">#FFFFFF</item>
        <item name="colorOnSurface">#17191F</item>
        <item name="colorOnSurfaceVariant">#61656F</item>
        <item name="colorOutline">#C5C9D3</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:fontFamily">sans-serif</item>
        <item name="android:windowBackground">#F6F7FB</item>
        <item name="android:statusBarColor">#F6F7FB</item>
        <item name="android:navigationBarColor">#F6F7FB</item>
        <item name="materialCardViewStyle">@style/Widget.BaiZe.GlassCard</item>
        <item name="materialButtonStyle">@style/Widget.BaiZe.Button</item>
    </style>

    <style name="Theme.BaiZe" parent="Theme.BaiZe.Blue" />

    <style name="Theme.BaiZe.Blue" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#356DF3</item>
        <item name="colorPrimaryContainer">#DDE6FF</item>
        <item name="colorSecondary">#177A83</item>
        <item name="colorSecondaryContainer">#C8EEF1</item>
        <item name="colorTertiary">#7259C8</item>
        <item name="colorSurface">#F8F9FD</item>
        <item name="colorSurfaceVariant">#ECEFF6</item>
    </style>

    <style name="Theme.BaiZe.Aurora" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#7658D6</item>
        <item name="colorPrimaryContainer">#E9E0FF</item>
        <item name="colorSecondary">#3D739D</item>
        <item name="colorSecondaryContainer">#D4E9F8</item>
        <item name="colorTertiary">#A34E86</item>
        <item name="colorSurface">#FAF8FD</item>
        <item name="colorSurfaceVariant">#F0ECF6</item>
    </style>

    <style name="Theme.BaiZe.Jade" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#167C68</item>
        <item name="colorPrimaryContainer">#C6F0E4</item>
        <item name="colorSecondary">#3B718E</item>
        <item name="colorSecondaryContainer">#D2E9F4</item>
        <item name="colorTertiary">#6A6F35</item>
        <item name="colorSurface">#F7FAF8</item>
        <item name="colorSurfaceVariant">#E9F1ED</item>
    </style>

    <style name="Theme.BaiZe.Sunset" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#A95B25</item>
        <item name="colorPrimaryContainer">#FFE2CC</item>
        <item name="colorSecondary">#846417</item>
        <item name="colorSecondaryContainer">#F5E5B8</item>
        <item name="colorTertiary">#A44E61</item>
        <item name="colorSurface">#FCF8F5</item>
        <item name="colorSurfaceVariant">#F4ECE7</item>
    </style>

    <style name="Theme.BaiZe.Amoled" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">#82B1FF</item>
        <item name="colorPrimaryContainer">#163B70</item>
        <item name="colorSecondary">#77D7D1</item>
        <item name="colorSecondaryContainer">#174441</item>
        <item name="colorTertiary">#C2B5FF</item>
        <item name="colorSurface">#000000</item>
        <item name="colorSurfaceVariant">#111318</item>
        <item name="colorOnSurface">#F3F4F8</item>
        <item name="colorOnSurfaceVariant">#ADB2BD</item>
        <item name="colorOutline">#3F434C</item>
        <item name="android:windowBackground">#000000</item>
        <item name="android:statusBarColor">#000000</item>
        <item name="android:navigationBarColor">#000000</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="materialCardViewStyle">@style/Widget.BaiZe.GlassCard</item>
        <item name="materialButtonStyle">@style/Widget.BaiZe.Button</item>
    </style>

    <style name="Theme.BaiZe.Monet" parent="Theme.Material3.DynamicColors.Dark.NoActionBar">
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:fontFamily">sans-serif</item>
        <item name="materialCardViewStyle">@style/Widget.BaiZe.GlassCard</item>
        <item name="materialButtonStyle">@style/Widget.BaiZe.Button</item>
    </style>

    <style name="Widget.BaiZe.GlassCard" parent="Widget.Material3.CardView.Filled">
        <item name="cardBackgroundColor">?attr/colorSurfaceVariant</item>
        <item name="cardCornerRadius">24dp</item>
        <item name="cardElevation">0dp</item>
        <item name="strokeColor">?attr/colorOutline</item>
        <item name="strokeWidth">0.7dp</item>
    </style>

    <style name="Widget.BaiZe.Button" parent="Widget.Material3.Button">
        <item name="cornerRadius">17dp</item>
        <item name="android:minHeight">52dp</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:textStyle">bold</item>
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:letterSpacing">0.01</item>
    </style>

    <style name="Widget.Material3.Button.FilledTonalButton" parent="Widget.Material3.Button">
        <item name="backgroundTint">?attr/colorPrimaryContainer</item>
        <item name="android:textColor">?attr/colorOnPrimaryContainer</item>
        <item name="cornerRadius">17dp</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:textStyle">bold</item>
        <item name="android:stateListAnimator">@null</item>
    </style>

    <style name="Widget.BaiZe.Button.Outlined" parent="Widget.Material3.Button.OutlinedButton">
        <item name="cornerRadius">17dp</item>
        <item name="android:minHeight">50dp</item>
        <item name="android:textAllCaps">false</item>
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="strokeColor">?attr/colorOutline</item>
        <item name="strokeWidth">1dp</item>
    </style>

    <style name="TextAppearance.BaiZe.PageTitle" parent="TextAppearance.Material3.HeadlineMedium">
        <item name="android:textColor">?attr/colorOnSurface</item>
        <item name="android:textStyle">bold</item>
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:textSize">25sp</item>
        <item name="android:letterSpacing">-0.01</item>
    </style>

    <style name="TextAppearance.BaiZe.SectionTitle" parent="TextAppearance.Material3.TitleMedium">
        <item name="android:textColor">?attr/colorOnSurface</item>
        <item name="android:textStyle">bold</item>
        <item name="android:fontFamily">sans-serif-medium</item>
        <item name="android:textSize">17sp</item>
    </style>

    <style name="TextAppearance.BaiZe.Nav.Active" parent="TextAppearance.Material3.LabelMedium">
        <item name="android:textSize">12sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:fontFamily">sans-serif-medium</item>
    </style>

    <style name="TextAppearance.BaiZe.Nav.Inactive" parent="TextAppearance.Material3.LabelMedium">
        <item name="android:textSize">11sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:fontFamily">sans-serif-medium</item>
    </style>
</resources>
''', encoding="utf-8")

night_dir = root / "app/src/main/res/values-night"
night_dir.mkdir(parents=True, exist_ok=True)
(night_dir / "themes.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.BaiZe.Base" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorOnPrimary">#FFFFFF</item>
        <item name="colorOnPrimaryContainer">#EAF0FF</item>
        <item name="colorOnSecondary">#071F21</item>
        <item name="colorOnSecondaryContainer">#D8F5F4</item>
        <item name="colorError">#FFB4AB</item>
        <item name="colorOnError">#690005</item>
        <item name="colorOnSurface">#F0F2F7</item>
        <item name="colorOnSurfaceVariant">#ADB3C0</item>
        <item name="colorOutline">#424854</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:fontFamily">sans-serif</item>
        <item name="android:windowBackground">#0D0F14</item>
        <item name="android:statusBarColor">#0D0F14</item>
        <item name="android:navigationBarColor">#0D0F14</item>
        <item name="materialCardViewStyle">@style/Widget.BaiZe.GlassCard</item>
        <item name="materialButtonStyle">@style/Widget.BaiZe.Button</item>
    </style>

    <style name="Theme.BaiZe.Blue" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#8DB3FF</item>
        <item name="colorPrimaryContainer">#243A68</item>
        <item name="colorSecondary">#73CFD2</item>
        <item name="colorSecondaryContainer">#24494C</item>
        <item name="colorTertiary">#C2B4FF</item>
        <item name="colorSurface">#0F1218</item>
        <item name="colorSurfaceVariant">#1A1E27</item>
    </style>

    <style name="Theme.BaiZe.Aurora" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#C5B4FF</item>
        <item name="colorPrimaryContainer">#45356F</item>
        <item name="colorSecondary">#94C7EA</item>
        <item name="colorSecondaryContainer">#294A60</item>
        <item name="colorTertiary">#F2B3D8</item>
        <item name="colorSurface">#121017</item>
        <item name="colorSurfaceVariant">#211D29</item>
    </style>

    <style name="Theme.BaiZe.Jade" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#78D8BE</item>
        <item name="colorPrimaryContainer">#1F5147</item>
        <item name="colorSecondary">#8BC7E4</item>
        <item name="colorSecondaryContainer">#285064</item>
        <item name="colorTertiary">#CED58B</item>
        <item name="colorSurface">#0D1412</item>
        <item name="colorSurfaceVariant">#18241F</item>
    </style>

    <style name="Theme.BaiZe.Sunset" parent="Theme.BaiZe.Base">
        <item name="colorPrimary">#FFB783</item>
        <item name="colorPrimaryContainer">#633A21</item>
        <item name="colorSecondary">#E6C46A</item>
        <item name="colorSecondaryContainer">#554619</item>
        <item name="colorTertiary">#F3B0BC</item>
        <item name="colorSurface">#15110E</item>
        <item name="colorSurfaceVariant">#251E19</item>
    </style>
</resources>
''', encoding="utf-8")

replace_once(
    theme_manager,
    '''        Palette("monet", "Monet 动态取色", "跟随壁纸与系统颜色", R.style.Theme_BaiZe_Monet, monet = true),
        Palette("blue", "白泽蓝", "蓝青渐变与深海玻璃", R.style.Theme_BaiZe_Blue),
        Palette("aurora", "极光紫", "蓝紫渐变与柔和高光", R.style.Theme_BaiZe_Aurora),
        Palette("jade", "翡翠绿", "青绿强调色与低饱和玻璃", R.style.Theme_BaiZe_Jade),
        Palette("sunset", "暖阳橙", "橙金强调色与温暖高光", R.style.Theme_BaiZe_Sunset),
        Palette("amoled", "纯黑 AMOLED", "纯黑背景与高对比蓝光", R.style.Theme_BaiZe_Amoled)
''',
    '''        Palette("blue", "澄澈蓝", "跟随系统明暗的克制蓝灰", R.style.Theme_BaiZe_Blue),
        Palette("aurora", "暮光紫", "低饱和紫与雾蓝", R.style.Theme_BaiZe_Aurora),
        Palette("jade", "青岚", "青绿与冷灰的清爽组合", R.style.Theme_BaiZe_Jade),
        Palette("sunset", "暖砂", "柔和橙棕与暖灰", R.style.Theme_BaiZe_Sunset),
        Palette("amoled", "极夜黑", "纯黑背景与低亮度高对比", R.style.Theme_BaiZe_Amoled),
        Palette("monet", "系统取色", "Android 12+ 壁纸动态配色", R.style.Theme_BaiZe_Monet, monet = true)
''',
    "palette descriptions",
)
replace_once(
    theme_manager,
    '        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "monet" else "blue"\n',
    '        val fallback = "blue"\n',
    "stable default palette",
)

# Replace noisy background and glass effects with a calmer, faster surface model.
(root / "app/src/main/java/io/github/xgl34222220/baize/ui/LiquidBackdropDrawable.kt").write_text(r'''package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** Low-cost ambient background: neutral surface with two restrained palette glows. */
class LiquidBackdropDrawable(context: Context) : Drawable() {
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(246, 247, 251))
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(53, 109, 243))
    private val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, Color.rgb(23, 122, 131))
    private val light = ColorUtils.calculateLuminance(surface) > 0.45
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.isEmpty) return
        paint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(
                mix(surface, if (light) Color.WHITE else Color.BLACK, if (light) 0.18f else 0.08f),
                mix(surface, primary, if (light) 0.035f else 0.055f),
                surface
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        drawGlow(canvas, rect.right * 0.92f, rect.top + rect.height() * 0.07f, primary, if (light) 26 else 38, 0.58f)
        drawGlow(canvas, rect.left + rect.width() * 0.03f, rect.bottom - rect.height() * 0.05f, secondary, if (light) 18 else 25, 0.54f)
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, color: Int, alpha: Int, scale: Float) {
        paint.shader = RadialGradient(
            x,
            y,
            max(rect.width(), rect.height()) * scale,
            intArrayOf(withAlpha(color, alpha), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

    private fun withAlpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val b = 1f - a
        return Color.rgb(
            (Color.red(first) * b + Color.red(second) * a).toInt(),
            (Color.green(first) * b + Color.green(second) * a).toInt(),
            (Color.blue(first) * b + Color.blue(second) * a).toInt()
        )
    }
}
''', encoding="utf-8")

(root / "app/src/main/java/io/github/xgl34222220/baize/ui/LiquidGlassDrawable.kt").write_text(r'''package io.github.xgl34222220.baize.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** Calm, theme-aware translucent surface without realtime blur or decorative waves. */
class LiquidGlassDrawable(
    context: Context,
    private val variant: Variant = Variant.CARD
) : Drawable() {
    enum class Variant { CARD, HERO, STRIP, DOCK, ACTIVE, BUTTON }

    private val density = context.resources.displayMetrics.density
    private val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.rgb(53, 109, 243))
    private val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(246, 247, 251))
    private val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, Color.rgb(236, 239, 246))
    private val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.rgb(197, 201, 211))
    private val light = ColorUtils.calculateLuminance(surface) > 0.45
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(if (variant == Variant.DOCK || variant == Variant.HERO) 1.1f else 0.8f)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val rect = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val radius = dp(
            when (variant) {
                Variant.DOCK -> 36f
                Variant.HERO -> 29f
                Variant.ACTIVE -> 27f
                Variant.STRIP -> 23f
                Variant.BUTTON -> 20f
                Variant.CARD -> 24f
            }
        )
        val accent = when (variant) {
            Variant.ACTIVE, Variant.BUTTON, Variant.HERO -> if (light) 0.16f else 0.22f
            Variant.DOCK -> if (light) 0.07f else 0.12f
            else -> if (light) 0.025f else 0.055f
        }
        val pressedBoost = if (pressed) 0.04f else 0f
        val top = mix(surfaceVariant, primary, (accent + pressedBoost).coerceAtMost(0.30f))
        val bottom = mix(surface, primary, if (variant == Variant.BUTTON) accent * 0.82f else accent * 0.25f)
        fill.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(top, mix(surfaceVariant, surface, 0.50f), bottom),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fill)

        if (variant == Variant.HERO || variant == Variant.DOCK || variant == Variant.ACTIVE) {
            glow.shader = RadialGradient(
                rect.left + rect.width() * 0.12f,
                rect.top,
                max(rect.width(), rect.height()) * 0.72f,
                intArrayOf(withAlpha(primary, if (light) 34 else 48), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, glow)
        }

        border.color = mix(outline, primary, if (variant == Variant.ACTIVE) 0.48f else 0.12f)
        border.alpha = if (light) 135 else 155
        val inset = border.strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            radius, radius, border
        )

        highlight.color = Color.WHITE
        highlight.alpha = if (light) 112 else 72
        val y = rect.top + dp(1.4f)
        canvas.drawLine(rect.left + radius * 0.75f, y, rect.right - radius * 0.75f, y, highlight)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = state.contains(android.R.attr.state_pressed)
        if (pressed == next) return false
        pressed = next
        invalidateSelf()
        return true
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, dp(if (variant == Variant.DOCK) 36f else 24f))
        outline.alpha = if (variant == Variant.ACTIVE || variant == Variant.HERO) 0.72f else 0.56f
    }

    private fun dp(value: Float): Float = value * density
    private fun withAlpha(color: Int, value: Int): Int = Color.argb(
        value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
    )
    private fun mix(first: Int, second: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val b = 1f - a
        return Color.rgb(
            (Color.red(first) * b + Color.red(second) * a).toInt(),
            (Color.green(first) * b + Color.green(second) * a).toInt(),
            (Color.blue(first) * b + Color.blue(second) * a).toInt()
        )
    }
}
''', encoding="utf-8")

replace_once(polish, '            text = "Alpha 12.1"\n', '            text = "Alpha 13"\n', "polish version")
replace_once(polish, '            view.elevation = dp(activity, if (variant == LiquidGlassDrawable.Variant.HERO) 18 else 10).toFloat()\n', '            view.elevation = dp(activity, if (variant == LiquidGlassDrawable.Variant.HERO) 10 else 4).toFloat()\n', "lower card elevation")
replace_once(polish, ') dp(activity, 10).toFloat() else dp(activity, 2).toFloat()\n', ') dp(activity, 6).toFloat() else 0f\n', "lower button elevation")

# Version/package metadata.
replace_once(
    build_gradle,
    '''        versionCode = 20013
        versionName = "2.0.0-alpha12.1"
''',
    '''        versionCode = 20020
        versionName = "2.0.0-alpha13"
''',
    "app version",
)
module_prop.write_text('''id=baize_v2
name=白泽 v2
version=v2.0.0-alpha13
versionCode=20020
author=惜故里丶
description=白泽 v2 Alpha 13：重做系统明暗主题与低饱和玻璃 UI，新增真正写入清理引擎的应用白名单管理，并完善保护设置与诊断入口。
''', encoding="utf-8")
replace_once(customize, 'ui_print "- 安装白泽 v2 Alpha 12.1 设置热修复版"\n', 'ui_print "- 安装白泽 v2 Alpha 13 UI 与白名单完善版"\n', "installer version")
replace_once(package_script, 'OUTPUT="$OUT/BaiZe-v2-Alpha12-Module.zip"\n', 'OUTPUT="$OUT/BaiZe-v2-Alpha13-Module.zip"\n', "module output")
replace_once(package_script, 'echo "已生成液态玻璃 UI、一键清理、真实调度器、内置 App 与完整规则库的一体化模块：$OUTPUT"\n', 'echo "已生成 Alpha 13 主题 UI、应用白名单、一键清理、真实调度器与完整规则库模块：$OUTPUT"\n', "package summary")
