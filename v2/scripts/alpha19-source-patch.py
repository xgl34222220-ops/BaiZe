from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch target: {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


dashboard = Path("v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt")
polish = Path("v2/app/src/main/java/io/github/xgl34222220/baize/Alpha7UiPolish.kt")
build_gradle = Path("v2/app/build.gradle.kts")
module_prop = Path("v2/module/module.prop")
customize = Path("v2/module/customize.sh")
package_script = Path("v2/scripts/package-module.sh")

replace_once(
    dashboard,
    "    private var loadingConfig = false\n",
    "    private var loadingConfig = false\n    private var pendingSmartClean = false\n",
    "pending smart clean state",
)
replace_once(
    dashboard,
    "            refreshWhitelist()\n        }\n",
    "            refreshWhitelist()\n            consumePendingSmartClean()\n        }\n",
    "consume smart clean after root connect",
)
replace_once(
    dashboard,
    '''        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.versionText.text = "Alpha 18"
''',
    '''        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingSmartClean = intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)

        binding.versionText.text = "Alpha 19"
''',
    "dashboard Alpha 19 startup",
)
replace_once(
    dashboard,
    '''    override fun onResume() {
''',
    '''    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RUN_SMART_CLEAN, false)) {
            pendingSmartClean = true
            consumePendingSmartClean()
        }
    }

    override fun onResume() {
''',
    "dashboard new intent",
)
replace_once(
    dashboard,
    '''    private fun setupNavigation() {
''',
    '''    private fun consumePendingSmartClean() {
        if (!pendingSmartClean || profileService == null || taskRunning) return
        pendingSmartClean = false
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        runSmartClean()
    }

    private fun setupNavigation() {
''',
    "consume pending smart clean function",
)
replace_once(
    dashboard,
    '''    override fun onDestroy() {
        taskPollJob?.cancel()
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }
}
''',
    '''    override fun onDestroy() {
        taskPollJob?.cancel()
        if (serviceBound) runCatching { RootService.unbind(connection) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RUN_SMART_CLEAN = "io.github.xgl34222220.baize.RUN_SMART_CLEAN"
    }
}
''',
    "dashboard companion extra",
)

replace_once(
    polish,
    '''    private fun polish(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
''',
    '''    private fun polish(activity: Activity) {
        // Alpha 19 Clean Center owns its complete WebUI-derived surface and inset system.
        if (activity is CleanCenterActivity) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
''',
    "exclude clean center from legacy polish",
)
replace_once(polish, '            text = "Alpha 18"\n', '            text = "Alpha 19"\n', "polish version")

replace_once(
    build_gradle,
    '''        versionCode = 20070
        versionName = "2.0.0-alpha18"
''',
    '''        versionCode = 20080
        versionName = "2.0.0-alpha19"
''',
    "app version",
)
replace_once(
    module_prop,
    '''version=v2.0.0-alpha18
versionCode=20070
''',
    '''version=v2.0.0-alpha19
versionCode=20080
''',
    "module version",
)
replace_once(
    module_prop,
    "description=白泽 v2 Alpha 18：修复 Monet 只刷新主题页的问题，所有页面统一动态配色；自动清理改为智能安全模式，危险项目仅手动二次确认。\n",
    "description=白泽 v2 Alpha 19：按 WebUI 设计系统重做清理明细，统一动态主题、分组卡片、安全区、立即清理与危险项二次确认。\n",
    "module description",
)
replace_once(
    customize,
    'ui_print "- 安装白泽 v2 Alpha 18 全局 Monet 智能清理版"\n',
    'ui_print "- 安装白泽 v2 Alpha 19 WebUI 原生重构版"\n',
    "installer title",
)
replace_once(
    package_script,
    'OUTPUT="$OUT/BaiZe-v2-Alpha18-Module.zip"\n',
    'OUTPUT="$OUT/BaiZe-v2-Alpha19-Module.zip"\n',
    "package output",
)
replace_once(
    package_script,
    'echo "已生成 Alpha 18 全局 Monet、智能自动清理、危险项二次确认与完整规则库模块：$OUTPUT"\n',
    'echo "已生成 Alpha 19 WebUI 原生清理中心、全局 Monet、智能自动清理与完整规则库模块：$OUTPUT"\n',
    "package message",
)
