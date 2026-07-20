#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
V2 = ROOT / "v2"
APP = V2 / "app"
JAVA = APP / "src/main/java/io/github/xgl34222220/baize"
AIDL = APP / "src/main/aidl/io/github/xgl34222220/baize/root"
MODULE = V2 / "module"


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    normalized = content.strip("\n") + "\n"
    if not path.exists() or path.read_text() != normalized:
        path.write_text(normalized)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"anchor missing in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


def replace_all(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if old not in text:
        return
    path.write_text(text.replace(old, new))


def find_kt(fragment: str) -> Path:
    matches = []
    for path in JAVA.rglob("*.kt"):
        try:
            if fragment in path.read_text():
                matches.append(path)
        except UnicodeDecodeError:
            pass
    if len(matches) != 1:
        raise RuntimeError(f"expected one Kotlin file containing {fragment!r}, got {matches}")
    return matches[0]


# ---------------------------------------------------------------------------
# Version and release packaging
# ---------------------------------------------------------------------------
app_gradle = APP / "build.gradle.kts"
text = app_gradle.read_text()
text = text.replace('versionCode = 22501', 'versionCode = 22600')
text = text.replace('versionName = "2.1.1"', 'versionName = "2.2.0"')
if 'BAIZE_KEYSTORE_PATH' not in text:
    text = text.replace(
        'android {\n',
        '''val releaseKeystorePath = providers.environmentVariable("BAIZE_KEYSTORE_PATH")\nval releaseKeystorePassword = providers.environmentVariable("BAIZE_KEYSTORE_PASSWORD")\nval releaseKeyAlias = providers.environmentVariable("BAIZE_KEY_ALIAS")\nval releaseKeyPassword = providers.environmentVariable("BAIZE_KEY_PASSWORD")\n\nandroid {\n''',
        1,
    )
    text = text.replace(
        '    buildTypes {\n',
        '''    signingConfigs {\n        create("release") {\n            if (releaseKeystorePath.isPresent) {\n                storeFile = file(releaseKeystorePath.get())\n                storePassword = releaseKeystorePassword.orNull\n                keyAlias = releaseKeyAlias.orNull\n                keyPassword = releaseKeyPassword.orNull\n                enableV1Signing = true\n                enableV2Signing = true\n                enableV3Signing = true\n                enableV4Signing = true\n            }\n        }\n    }\n\n    buildTypes {\n''',
        1,
    )
    text = text.replace(
        '        release {\n            isMinifyEnabled = true',
        '''        release {\n            signingConfig = signingConfigs.getByName(if (releaseKeystorePath.isPresent) "release" else "debug")\n            isMinifyEnabled = true''',
        1,
    )
if 'profileinstaller' not in text:
    text = text.replace(
        '    implementation("androidx.work:work-runtime-ktx:2.10.2")\n',
        '    implementation("androidx.work:work-runtime-ktx:2.10.2")\n    implementation("androidx.profileinstaller:profileinstaller:1.4.1")\n',
        1,
    )
app_gradle.write_text(text)

module_prop = MODULE / "module.prop"
text = module_prop.read_text()
text = re.sub(r'^version=.*$', 'version=v2.2.0', text, flags=re.M)
text = re.sub(r'^versionCode=.*$', 'versionCode=22600', text, flags=re.M)
text = re.sub(
    r'^description=.*$',
    'description=白泽 v2.2.0：统一存储索引、全应用下载覆盖、AIDL 进度推送、高刷与自适应流畅模式。',
    text,
    flags=re.M,
)
module_prop.write_text(text)

package_script = V2 / "scripts/package-module.sh"
text = package_script.read_text()
text = text.replace('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/release/app-release.apk')
text = text.replace('BaiZe-v2.1.1-Module.zip', 'BaiZe-v2.2.0-Module.zip')
text = text.replace('version=v2.1.1', 'version=v2.2.0')
text = text.replace('versionCode=22501', 'versionCode=22600')
text = text.replace('白泽 v2.1.1', '白泽 v2.2.0')
if 'storage-index.sh' not in text:
    text = text.replace(
        'chmod 0755 "$STAGE/cleaner.sh"',
        'chmod 0755 "$STAGE/storage-index.sh"\nchmod 0755 "$STAGE/cleaner.sh"',
        1,
    )
    text = text.replace(
        "unzip -l \"$OUTPUT\" | grep -q 'one-pass-scan.sh'\n",
        "unzip -l \"$OUTPUT\" | grep -q 'one-pass-scan.sh'\nunzip -l \"$OUTPUT\" | grep -q 'storage-index.sh'\n",
        1,
    )
    text = text.replace(
        "unzip -p \"$OUTPUT\" one-pass-scan.sh | grep -q 'scan-external-one-pass'\n",
        "unzip -p \"$OUTPUT\" one-pass-scan.sh | grep -q 'scan-external-one-pass'\nunzip -p \"$OUTPUT\" storage-index.sh | grep -q 'baize-storage-index-v2.2'\n",
        1,
    )
package_script.write_text(text)

# ---------------------------------------------------------------------------
# High-refresh and adaptive smooth-mode runtime
# ---------------------------------------------------------------------------
write(JAVA / "performance/DisplayPerformanceController.kt", r'''
package io.github.xgl34222220.baize.performance

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Choreographer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.xgl34222220.baize.ui.appearance.RefreshRateMode
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

object PerformanceRuntime {
    val actualFps = mutableFloatStateOf(0f)
    val requestedRefreshRate = mutableFloatStateOf(60f)
    val degraded = mutableStateOf(false)
    val reason = mutableStateOf("高刷准备中")

    fun statusLine(): String {
        val requested = requestedRefreshRate.floatValue.roundToInt()
        val actual = actualFps.floatValue.roundToInt()
        val suffix = if (degraded.value) " · 流畅降级" else ""
        return if (actual > 0) "${requested}Hz 请求 · ${actual}fps$suffix" else "${requested}Hz 请求$suffix"
    }
}

object DisplayPerformanceController : Application.ActivityLifecycleCallbacks, Choreographer.FrameCallback {
    private const val PREFS = "baize_performance"
    private const val KEY_REFRESH = "refresh_rate_mode"
    private const val KEY_SMOOTH = "adaptive_smooth_mode"

    private lateinit var application: Application
    private var resumed = WeakReference<Activity>(null)
    private var monitoring = false
    private var lastFrameNanos = 0L
    private var sampleStartNanos = 0L
    private var frameCount = 0
    private var jankFrames = 0
    private var stableSamples = 0
    private var thermalStatus = 0

    fun install(app: Application) {
        if (::application.isInitialized) return
        application = app
        app.registerActivityLifecycleCallbacks(this)
        if (Build.VERSION.SDK_INT >= 29) {
            val power = app.getSystemService(PowerManager::class.java)
            power.addThermalStatusListener(app.mainExecutor) { status ->
                thermalStatus = status
                updateDegradeState()
            }
        }
    }

    fun setRefreshRateMode(mode: RefreshRateMode) {
        application.getSharedPreferences(PREFS, 0).edit().putString(KEY_REFRESH, mode.name).apply()
        resumed.get()?.let(::applyPolicy)
    }

    fun setAdaptiveSmoothMode(enabled: Boolean) {
        application.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_SMOOTH, enabled).apply()
        updateDegradeState()
    }

    fun currentMode(): RefreshRateMode = RefreshRateMode.fromStorage(
        application.getSharedPreferences(PREFS, 0).getString(KEY_REFRESH, RefreshRateMode.HIGH.name)
    )

    fun adaptiveSmoothEnabled(): Boolean =
        application.getSharedPreferences(PREFS, 0).getBoolean(KEY_SMOOTH, true)

    private fun applyPolicy(activity: Activity) {
        val display = activity.windowManager.defaultDisplay
        val modes = if (Build.VERSION.SDK_INT >= 23) display.supportedModes.toList() else emptyList()
        val selected = when (currentMode()) {
            RefreshRateMode.SYSTEM -> null
            RefreshRateMode.HIGH -> modes.maxWithOrNull(compareBy<android.view.Display.Mode> { it.refreshRate }.thenBy { it.physicalWidth * it.physicalHeight })
            RefreshRateMode.STANDARD -> modes.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        }
        val attributes = activity.window.attributes
        if (selected == null) {
            attributes.preferredRefreshRate = 0f
            if (Build.VERSION.SDK_INT >= 23) attributes.preferredDisplayModeId = 0
            PerformanceRuntime.requestedRefreshRate.floatValue = display.refreshRate
        } else {
            attributes.preferredRefreshRate = selected.refreshRate
            if (Build.VERSION.SDK_INT >= 23) attributes.preferredDisplayModeId = selected.modeId
            PerformanceRuntime.requestedRefreshRate.floatValue = selected.refreshRate
        }
        activity.window.attributes = attributes
        updateDegradeState()
    }

    private fun updateDegradeState(jankRatio: Float? = null) {
        if (!::application.isInitialized) return
        val power = application.getSystemService(PowerManager::class.java)
        val powerSave = power.isPowerSaveMode
        val hot = Build.VERSION.SDK_INT >= 29 && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val janky = jankRatio != null && jankRatio >= .22f
        val enabled = adaptiveSmoothEnabled()
        val degraded = enabled && (powerSave || hot || janky)
        if (!degraded && jankRatio != null && jankRatio < .08f) stableSamples += 1 else if (degraded) stableSamples = 0
        val shouldRecover = !powerSave && !hot && stableSamples >= 2
        PerformanceRuntime.degraded.value = when {
            degraded -> true
            shouldRecover -> false
            else -> PerformanceRuntime.degraded.value
        }
        PerformanceRuntime.reason.value = when {
            powerSave -> "系统省电模式"
            hot -> "设备温度较高"
            janky -> "检测到连续掉帧"
            PerformanceRuntime.degraded.value -> "等待帧率稳定"
            else -> "高刷流畅模式"
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!monitoring) return
        if (sampleStartNanos == 0L) sampleStartNanos = frameTimeNanos
        if (lastFrameNanos > 0L) {
            val interval = frameTimeNanos - lastFrameNanos
            val target = (1_000_000_000f / PerformanceRuntime.requestedRefreshRate.floatValue.coerceAtLeast(60f)).toLong()
            if (interval > target * 2L) jankFrames += 1
        }
        lastFrameNanos = frameTimeNanos
        frameCount += 1
        val elapsed = frameTimeNanos - sampleStartNanos
        if (elapsed >= 1_000_000_000L) {
            PerformanceRuntime.actualFps.floatValue = frameCount * 1_000_000_000f / elapsed
            updateDegradeState(if (frameCount > 0) jankFrames.toFloat() / frameCount else 0f)
            sampleStartNanos = frameTimeNanos
            frameCount = 0
            jankFrames = 0
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startMonitor() {
        if (monitoring) return
        monitoring = true
        lastFrameNanos = 0L
        sampleStartNanos = 0L
        frameCount = 0
        jankFrames = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopMonitor() {
        monitoring = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        applyPolicy(activity)
        startMonitor()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) resumed.clear()
        stopMonitor()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
''')

application = JAVA / "BaiZeApplication.kt"
replace_once(
    application,
    'import com.topjohnwu.superuser.Shell\n',
    'import com.topjohnwu.superuser.Shell\nimport io.github.xgl34222220.baize.performance.DisplayPerformanceController\n',
)
replace_once(
    application,
    '        NativeNotifier.ensureChannel(this)\n',
    '        NativeNotifier.ensureChannel(this)\n        DisplayPerformanceController.install(this)\n',
)

# Appearance settings now own refresh-rate and smooth-mode preferences.
appearance_settings = JAVA / "ui/appearance/AppearanceSettings.kt"
text = appearance_settings.read_text()
if 'enum class RefreshRateMode' not in text:
    text = text.replace(
        'enum class KolorStyle',
        '''enum class RefreshRateMode(val label: String) {\n    SYSTEM("跟随系统"),\n    HIGH("高刷优先"),\n    STANDARD("60Hz 省电");\n\n    companion object {\n        fun fromStorage(value: String?): RefreshRateMode =\n            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: HIGH\n    }\n}\n\nenum class KolorStyle''',
        1,
    )
    text = text.replace(
        '    val floatingDock: Boolean = true\n',
        '    val floatingDock: Boolean = true,\n    val refreshRateMode: RefreshRateMode = RefreshRateMode.HIGH,\n    val adaptiveSmoothMode: Boolean = true\n',
        1,
    )
appearance_settings.write_text(text)

appearance_repo = JAVA / "ui/appearance/AppearanceRepository.kt"
text = appearance_repo.read_text()
if 'DisplayPerformanceController' not in text:
    text = text.replace(
        'import io.github.xgl34222220.baize.ThemeManager\n',
        'import io.github.xgl34222220.baize.ThemeManager\nimport io.github.xgl34222220.baize.performance.DisplayPerformanceController\n',
        1,
    )
    text = text.replace(
        '        val floatingDock = booleanPreferencesKey(ThemeManager.KEY_FLOATING_DOCK)\n',
        '        val floatingDock = booleanPreferencesKey(ThemeManager.KEY_FLOATING_DOCK)\n        val refreshRateMode = stringPreferencesKey("refresh_rate_mode")\n        val adaptiveSmoothMode = booleanPreferencesKey("adaptive_smooth_mode")\n',
        1,
    )
    text = text.replace(
        '                floatingDock = preferences[Keys.floatingDock] ?: true\n',
        '                floatingDock = preferences[Keys.floatingDock] ?: true,\n                refreshRateMode = RefreshRateMode.fromStorage(preferences[Keys.refreshRateMode]),\n                adaptiveSmoothMode = preferences[Keys.adaptiveSmoothMode] ?: true\n',
        1,
    )
    text = text.replace(
        '    private suspend inline fun edit',
        '''    suspend fun setRefreshRateMode(value: RefreshRateMode) {\n        edit { it[Keys.refreshRateMode] = value.name }\n        DisplayPerformanceController.setRefreshRateMode(value)\n    }\n\n    suspend fun setAdaptiveSmoothMode(enabled: Boolean) {\n        edit { it[Keys.adaptiveSmoothMode] = enabled }\n        DisplayPerformanceController.setAdaptiveSmoothMode(enabled)\n    }\n\n    private suspend inline fun edit''',
        1,
    )
appearance_repo.write_text(text)

appearance_vm = JAVA / "ui/appearance/AppearanceViewModel.kt"
text = appearance_vm.read_text()
if 'setRefreshRateMode' not in text:
    text = text.replace(
        '    fun setFloatingDock(enabled: Boolean) = launch { repository.setFloatingDock(enabled) }\n',
        '''    fun setFloatingDock(enabled: Boolean) = launch { repository.setFloatingDock(enabled) }\n\n    fun setRefreshRateMode(value: RefreshRateMode) = launch { repository.setRefreshRateMode(value) }\n\n    fun setAdaptiveSmoothMode(enabled: Boolean) = launch { repository.setAdaptiveSmoothMode(enabled) }\n''',
        1,
    )
appearance_vm.write_text(text)

appearance_route = JAVA / "ui/appearance/AppearanceRoute.kt"
text = appearance_route.read_text()
if 'onRefreshRateMode' not in text:
    text = text.replace(
        '    val onFloatingDock: (Boolean) -> Unit\n',
        '    val onFloatingDock: (Boolean) -> Unit,\n    val onRefreshRateMode: (RefreshRateMode) -> Unit,\n    val onAdaptiveSmoothMode: (Boolean) -> Unit\n',
        1,
    )
appearance_route.write_text(text)

theme_activity = JAVA / "ThemeSettingsActivity.kt"
text = theme_activity.read_text()
if 'onRefreshRateMode' not in text:
    text = text.replace(
        '                            onFloatingDock = appearanceViewModel::setFloatingDock\n',
        '                            onFloatingDock = appearanceViewModel::setFloatingDock,\n                            onRefreshRateMode = appearanceViewModel::setRefreshRateMode,\n                            onAdaptiveSmoothMode = appearanceViewModel::setAdaptiveSmoothMode\n',
        1,
    )
theme_activity.write_text(text)

material_screen = JAVA / "ui/appearance/material/AppearanceScreenMaterial.kt"
text = material_screen.read_text()
if 'RefreshRateMode' not in text:
    text = text.replace(
        'import io.github.xgl34222220.baize.ui.appearance.KolorStyle\n',
        'import io.github.xgl34222220.baize.ui.appearance.KolorStyle\nimport io.github.xgl34222220.baize.ui.appearance.RefreshRateMode\n',
        1,
    )
    anchor = '            item { MaterialSectionTitle("EFFECTS", "显示效果") }\n'
    block = '''            item { MaterialSectionTitle("PERFORMANCE", "刷新率与流畅度") }\n            item {\n                MaterialChoiceCard {\n                    Text("刷新率策略", style = MaterialTheme.typography.titleMedium)\n                    Spacer(Modifier.height(8.dp))\n                    MaterialSegmentRow(\n                        values = RefreshRateMode.entries,\n                        selected = settings.refreshRateMode,\n                        label = { it.label },\n                        onSelected = actions.onRefreshRateMode\n                    )\n                    HorizontalDivider(Modifier.padding(top = 14.dp))\n                    MaterialSwitchRow(\n                        icon = Icons.Rounded.PhoneAndroid,\n                        title = "自适应流畅模式",\n                        description = "掉帧、发热或省电时自动停用模糊与重动画",\n                        checked = settings.adaptiveSmoothMode,\n                        onCheckedChange = actions.onAdaptiveSmoothMode\n                    )\n                }\n            }\n'''
    text = text.replace(anchor, block + anchor, 1)
    text = text.replace(
        '                    if (settings.floatingDock) "悬浮底栏" else "贴底底栏"\n',
        '                    if (settings.floatingDock) "悬浮底栏" else "贴底底栏",\n                    settings.refreshRateMode.label,\n                    if (settings.adaptiveSmoothMode) "自适应流畅" else null\n',
        1,
    )
material_screen.write_text(text)

miuix_screen = JAVA / "ui/appearance/miuix/AppearanceScreenMiuix.kt"
text = miuix_screen.read_text()
if 'RefreshRateMode' not in text:
    text = text.replace(
        'import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\n',
        'import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings\nimport io.github.xgl34222220.baize.ui.appearance.RefreshRateMode\n',
        1,
    )
    anchor = '        item { MiuixSectionTitle("EFFECTS", "显示效果", "玻璃、Haze 模糊与底栏形态") }\n'
    block = '''        item { MiuixSectionTitle("PERFORMANCE", "刷新率与流畅度", "高刷优先，并在掉帧、发热和省电时自动降级") }\n        item {\n            MiuixGroup {\n                Column(Modifier.padding(vertical = 14.dp)) {\n                    Text("刷新率策略", fontSize = 16.sp, fontWeight = FontWeight.Black)\n                    Text("当前：${settings.refreshRateMode.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)\n                    Spacer(Modifier.height(10.dp))\n                    MiuixSegmentRow(\n                        values = RefreshRateMode.entries,\n                        selected = settings.refreshRateMode,\n                        label = { it.label },\n                        onSelected = actions.onRefreshRateMode\n                    )\n                }\n                MiuixDivider()\n                MiuixSwitchRow(\n                    icon = Icons.Rounded.PhoneAndroid,\n                    title = "自适应流畅模式",\n                    description = "滚动掉帧、设备发热或省电时暂停 Haze 与重动画",\n                    checked = settings.adaptiveSmoothMode,\n                    onCheckedChange = actions.onAdaptiveSmoothMode\n                )\n            }\n        }\n'''
    text = text.replace(anchor, block + anchor, 1)
    text = text.replace(
        '"${if (settings.glassEnabled) "玻璃开启" else "实心材质"} · ${if (settings.blurEnabled && settings.glassEnabled) "Haze 模糊" else "无模糊"} · ${if (settings.floatingDock) "悬浮底栏" else "贴底底栏"}"',
        '"${if (settings.glassEnabled) "玻璃开启" else "实心材质"} · ${if (settings.blurEnabled && settings.glassEnabled) "Haze 模糊" else "无模糊"} · ${settings.refreshRateMode.label} · ${if (settings.adaptiveSmoothMode) "自适应流畅" else "固定特效"}"',
        1,
    )
miuix_screen.write_text(text)

# Adaptive Haze and shorter motion when the runtime detects jank/heat/power-save.
miuix_app = find_kt('fun BaiZeMiuixApp(')
text = miuix_app.read_text()
if 'PerformanceRuntime.degraded' not in text:
    text = text.replace(
        '            val hazeState = rememberHazeState(\n',
        '            val runtimeDegraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value\n            val hazeState = rememberHazeState(\n',
        1,
    )
    text = text.replace(
        '                    !amoled\n',
        '                    !amoled && !(appearance.adaptiveSmoothMode && runtimeDegraded)\n',
        1,
    )
    text = text.replace(
        '            val enterDuration = if (style == UiStyle.MIUIX) 300 else 250\n            val exitDuration = if (style == UiStyle.MIUIX) 210 else 170\n            val enterDivisor = if (style == UiStyle.MIUIX) 8 else 11\n            val exitDivisor = if (style == UiStyle.MIUIX) 13 else 16\n',
        '''            val degraded = io.github.xgl34222220.baize.performance.PerformanceRuntime.degraded.value\n            val enterDuration = if (degraded) 90 else if (style == UiStyle.MIUIX) 210 else 180\n            val exitDuration = if (degraded) 70 else if (style == UiStyle.MIUIX) 140 else 120\n            val enterDivisor = if (degraded) Int.MAX_VALUE else if (style == UiStyle.MIUIX) 14 else 18\n            val exitDivisor = if (degraded) Int.MAX_VALUE else if (style == UiStyle.MIUIX) 20 else 24\n''',
        1,
    )
    text = text.replace(
        '"Miuix × Haze Glass · v${BuildConfig.VERSION_NAME}"',
        '"Miuix · ${io.github.xgl34222220.baize.performance.PerformanceRuntime.statusLine()} · v${BuildConfig.VERSION_NAME}"',
    )
miuix_app.write_text(text)

# ---------------------------------------------------------------------------
# Disk-backed application icon cache
# ---------------------------------------------------------------------------
instant_cache = JAVA / "InstantCacheActivity.kt"
text = instant_cache.read_text()
for imp in [
    'import android.graphics.BitmapFactory\n',
    'import java.io.File\n',
    'import java.security.MessageDigest\n',
]:
    if imp not in text:
        if imp.startswith('import android.graphics'):
            text = text.replace('import android.graphics.Bitmap\n', 'import android.graphics.Bitmap\n' + imp, 1)
        else:
            text = text.replace('import org.json.JSONObject\n', 'import org.json.JSONObject\n' + imp, 1)
text = text.replace('PackageIcon(packageName = app.packageName)', 'PackageIcon(packageName = app.packageName, label = app.label)')
text = text.replace('private fun PackageIcon(packageName: String) {', 'private fun PackageIcon(packageName: String, label: String) {')
text = text.replace(
    '            Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.primary)\n',
    '            Text(label.trim().firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)\n',
    1,
)
start = text.index('private object AppIconCache {')
replacement = r'''private object AppIconCache {
    private const val ICON_PX = 96
    private const val MAX_DISK_FILES = 220
    private val cache = object : LruCache<String, Bitmap>(96) {}

    @Synchronized
    fun get(packageName: String): Bitmap? = cache.snapshot().entries
        .firstOrNull { it.key.startsWith("$packageName:") }
        ?.value

    fun load(context: Context, packageName: String): Bitmap? {
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val packageInfo = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val key = "$packageName:${packageInfo?.lastUpdateTime ?: info.sourceDir.hashCode()}"
        synchronized(this) { cache.get(key) }?.let { return it }

        val directory = File(context.cacheDir, "app-icons-v2").apply { mkdirs() }
        val disk = File(directory, sha256(key) + ".png")
        val fromDisk = runCatching { if (disk.isFile) BitmapFactory.decodeFile(disk.path) else null }.getOrNull()
        if (fromDisk != null) {
            synchronized(this) { cache.put(key, fromDisk) }
            disk.setLastModified(System.currentTimeMillis())
            return fromDisk
        }

        val bitmap = runCatching {
            context.packageManager.getApplicationIcon(info)
                .toBitmap(width = ICON_PX, height = ICON_PX, config = Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        synchronized(this) { cache.put(key, bitmap) }
        runCatching {
            val temp = File(directory, disk.name + ".tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temp.renameTo(disk)) {
                temp.copyTo(disk, overwrite = true)
                temp.delete()
            }
            directory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified)
                ?.drop(MAX_DISK_FILES)?.forEach(File::delete)
        }
        return bitmap
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
'''
text = text[:start] + replacement
instant_cache.write_text(text)

# ---------------------------------------------------------------------------
# Shared storage index and coverage report
# ---------------------------------------------------------------------------
write(MODULE / "storage-index.sh", r'''
#!/system/bin/sh
# baize-storage-index-v2.2
set -u

MODE=${1:-ensure}
TRIGGER=${2:-manual}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
MEDIA_ROOT=${BAIZE_MEDIA_ROOT:-/data/media}
INDEX_DIR="$STATE_DIR/index"
INDEX_FILE="$INDEX_DIR/storage-files.nul"
COVERAGE_FILE="$INDEX_DIR/coverage.tsv"
META_FILE="$INDEX_DIR/meta.env"
LOCK_DIR="$STATE_DIR/index.lock"
STOP_FILE="$STATE_DIR/stop"
TTL=${BAIZE_INDEX_TTL_SECONDS:-300}

mkdir -p "$INDEX_DIR"
now=$(date +%s)
old_epoch=$(sed -n 's/^epoch=//p' "$META_FILE" 2>/dev/null | tail -n 1)
case "$old_epoch" in ''|*[!0-9]*) old_epoch=0 ;; esac
if [ "$MODE" = ensure ] && [ -s "$INDEX_FILE" ] && [ -s "$COVERAGE_FILE" ] && [ $((now - old_epoch)) -lt "$TTL" ]; then
  echo "共享存储索引仍然有效"
  exit 0
fi

waited=0
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
  if [ -s "$INDEX_FILE" ] && [ $waited -ge 20 ]; then exit 0; fi
  sleep 1
  waited=$((waited + 1))
  [ $waited -lt 60 ] || { echo "等待共享索引锁超时" >&2; exit 3; }
done
cleanup() { rm -rf -- "$LOCK_DIR" 2>/dev/null; }
trap cleanup EXIT INT TERM

TMP="$LOCK_DIR/tmp"
mkdir -p "$TMP"
RECORDS_TMP="$TMP/storage-files.nul"
COVERAGE_TMP="$TMP/coverage.tsv"
ROOTS="$TMP/roots.tsv"
: >"$RECORDS_TMP"
printf 'status\tgroup\tfiles\tbytes\tpath\treason\n' >"$COVERAGE_TMP"
: >"$ROOTS"

safe_field() { printf '%s' "$1" | tr '\t\r\n' '   '; }
add_root() {
  group=$1 depth=$2 root=${3%/}
  [ -d "$root" ] || return 0
  grep -Fq "$(printf '\t%s\n' "$root")" "$ROOTS" 2>/dev/null && return 0
  printf '%s\t%s\t%s\n' "$(safe_field "$group")" "$depth" "$root" >>"$ROOTS"
}

for userdir in "$MEDIA_ROOT"/[0-9]*; do
  [ -d "$userdir" ] || continue
  add_root "内部存储根目录" 1 "$userdir"
  add_root "QQ接收:公共目录" 12 "$userdir/Tencent/QQfile_recv"
  add_root "TIM接收:公共目录" 12 "$userdir/Tencent/Timfile_recv"
  for top in "$userdir"/*; do
    [ -d "$top" ] || continue
    name=${top##*/}
    case "$name" in
      Android|Tencent|DCIM|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|BaiZe归类|LOST.DIR) continue ;;
    esac
    add_root "共享下载目录:$name" 12 "$top"
  done
  for pkg in "$userdir"/Android/media/*; do
    [ -d "$pkg" ] && add_root "应用媒体:${pkg##*/}" 14 "$pkg"
  done
  for pkg in "$userdir"/Android/data/*; do
    [ -d "$pkg" ] || continue
    package=${pkg##*/}
    add_root "应用文件:$package" 12 "$pkg/files"
    add_root "应用下载:$package" 10 "$pkg/Download"
    add_root "应用下载:$package" 10 "$pkg/Downloads"
    add_root "应用文档:$package" 10 "$pkg/Documents"
    add_root "Telegram:$package" 12 "$pkg/Telegram"
    add_root "QQ接收:$package" 12 "$pkg/Tencent/QQfile_recv"
    add_root "TIM接收:$package" 12 "$pkg/Tencent/Timfile_recv"
  done
done

root_total=$(wc -l <"$ROOTS" 2>/dev/null | tr -d ' ')
case "$root_total" in ''|*[!0-9]*) root_total=0 ;; esac
root_current=0
total_files=0
total_bytes=0

TAB=$(printf '\t')
while IFS="$TAB" read -r group depth root || [ -n "${root:-}" ]; do
  [ -d "${root:-}" ] || continue
  [ -f "$STOP_FILE" ] && { echo "索引任务已停止" >&2; exit 9; }
  root_current=$((root_current + 1))
  LIST="$TMP/root.$root_current.nul"
  : >"$LIST"
  find "$root" -xdev -mindepth 1 -maxdepth "$depth" \
    \( -type d \( -iname cache -o -iname code_cache -o -iname no_backup -o -iname databases -o -iname shared_prefs -o -iname lib -o -iname tmp -o -iname temp -o -iname thumbnails -o -iname .thumbnails -o -iname stickers -o -iname emoji -o -iname crash -o -iname crashes \) -prune \) -o \
    \( -type f -print0 \) 2>/dev/null >"$LIST"
  code=$?
  files=0
  bytes=0
  while IFS= read -r -d '' file; do
    case "$file" in *.part|*.partial|*.download|*.tmp|*.temp|*.crdownload) continue ;; esac
    printf '%s\0' "$file" >>"$RECORDS_TMP"
    size=$(stat -c %s "$file" 2>/dev/null)
    case "$size" in ''|*[!0-9]*) size=0 ;; esac
    files=$((files + 1))
    bytes=$((bytes + size))
  done <"$LIST"
  total_files=$((total_files + files))
  total_bytes=$((total_bytes + bytes))
  status=scanned; reason=""
  [ "$code" -eq 0 ] || { status=partial; reason="部分目录无法读取"; }
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$status" "$(safe_field "$group")" "$files" "$bytes" "$(safe_field "$root")" "$reason" >>"$COVERAGE_TMP"
done <"$ROOTS"

mv -f "$RECORDS_TMP" "$INDEX_FILE"
mv -f "$COVERAGE_TMP" "$COVERAGE_FILE"
{
  echo "epoch=$(date +%s)"
  echo "trigger=$TRIGGER"
  echo "roots=$root_total"
  echo "files=$total_files"
  echo "bytes=$total_bytes"
  echo "engine=baize-storage-index-v2.2"
} >"$META_FILE"
chmod 0600 "$INDEX_FILE" "$COVERAGE_FILE" "$META_FILE" 2>/dev/null

echo "共享存储索引完成：$root_total 个来源，$total_files 个文件"
exit 0
''')

# APK scan consumes the shared index instead of traversing storage independently.
apk_scan = MODULE / "apk-snapshot-scan.sh"
text = apk_scan.read_text()
start_marker = 'ROOTS_FILE="$TMP_DIR/apk-roots"\n'
end_marker = '\nfiles=0\nbytes=0\nsample_path=""\n'
if 'INDEX_FILE="$STATE_DIR/index/storage-files.nul"' not in text:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    block = r'''INDEX_FILE="$STATE_DIR/index/storage-files.nul"
COVERAGE_FILE="$STATE_DIR/index/coverage.tsv"
set_phase "正在建立全应用共享存储索引" 0 0 "$MEDIA_ROOT"
if ! BAIZE_STATE_DIR="$STATE_DIR" BAIZE_MEDIA_ROOT="$MEDIA_ROOT" /system/bin/sh "$MODDIR/storage-index.sh" refresh "$TRIGGER"; then
  echo "共享存储索引失败" >&2
  exit 5
fi
[ -s "$INDEX_FILE" ] || { echo "共享存储索引为空" >&2; exit 5; }

root_total=$(awk -F '\t' 'NR>1 && ($1=="scanned" || $1=="partial"){n++} END{print n+0}' "$COVERAGE_FILE" 2>/dev/null)
root_current=$root_total
protected=0
errors=0
cutoff=$((START_EPOCH - DAYS * 86400))
set_phase "正在从共享索引筛选安装包" 0 "$root_total" "$INDEX_FILE"
while IFS= read -r -d '' candidate; do
  should_stop && handle_signal
  [ -f "$candidate" ] || continue
  ext=$(printf '%s' "${candidate##*.}" | tr '[:upper:]' '[:lower:]')
  case "$ext" in apk|apks|xapk|apkm) ;; *) continue ;; esac
  size=$(file_size "$candidate")
  [ "$size" -le "$MAX_FILE_BYTES" ] || continue
  if [ "$DAYS" -gt 0 ]; then
    modified=$(stat -c %Y "$candidate" 2>/dev/null)
    case "$modified" in ''|*[!0-9]*) modified=$START_EPOCH ;; esac
    [ "$modified" -lt "$cutoff" ] || continue
  fi
  if [ -L "$candidate" ] || path_conflicts_whitelist "$candidate"; then
    protected=$((protected + 1))
    continue
  fi
  printf '%s\0' "$candidate" >>"$TARGETS_TMP"
done <"$INDEX_FILE"
'''
    text = text[:start] + block + text[end:]
text = text.replace('engine=apk-snapshot-v2.1.1-generic-roots', 'engine=apk-snapshot-v2.2-shared-index')
text = text.replace('engine=apk-snapshot-v42.8', 'engine=apk-snapshot-v2.2-shared-index')
if '扫描覆盖来源' not in text:
    text = text.replace(
        '  echo "白名单或异常保护: $protected | 失败: $errors | 耗时: ${elapsed}s"\n',
        '  echo "白名单或异常保护: $protected | 失败: $errors | 耗时: ${elapsed}s"\n  echo "扫描覆盖来源: $root_total（详情见 $COVERAGE_FILE）"\n',
        1,
    )
apk_scan.write_text(text)

# File organizer reads the same NUL index, falling back to legacy discovery only if index creation fails.
organizer = JAVA / "root/FileOrganizerEngine.kt"
text = organizer.read_text()
for imp in ['import java.io.ByteArrayOutputStream\n', 'import java.util.concurrent.TimeUnit\n']:
    if imp not in text:
        text = text.replace('import java.io.FileOutputStream\n', 'import java.io.FileOutputStream\n' + (imp if 'ByteArray' in imp else ''), 1) if 'ByteArray' in imp else text.replace('import java.util.UUID\n', 'import java.util.UUID\n' + imp, 1)
old = '''        val sources = discoverSourceRoots(started, progress)\n        if (cancelled.get()) {\n            return JSONObject().put("cancelled", true).put("message", "文件归类扫描已停止").toString()\n        }\n\n        val items = LinkedHashMap<String, PlannedMove>()\n        sources.forEachIndexed { index, source ->\n            if (cancelled.get() || items.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS) {\n                return@forEachIndexed\n            }\n            progress(\n                Progress(\n                    phase = when (source.policy) {\n                        SourcePolicy.TOP_LEVEL_ONLY -> "正在读取内部存储根目录"\n                        SourcePolicy.FULL_DOWNLOAD_TREE -> "正在读取下载与接收目录"\n                        SourcePolicy.APP_USER_FILES -> "正在读取应用用户文件目录"\n                    },\n                    current = index + 1,\n                    total = sources.size,\n                    path = displayPath(source.directory.path)\n                )\n            )\n            collectSource(source, started, items, progress)\n        }\n'''
new = '''        val items = LinkedHashMap<String, PlannedMove>()\n        val indexed = collectSharedIndex(started, items, progress)\n        val sourceCount: Int\n        val coverage: JSONArray\n        if (indexed != null) {\n            sourceCount = indexed.first\n            coverage = indexed.second\n        } else {\n            val sources = discoverSourceRoots(started, progress)\n            if (cancelled.get()) {\n                return JSONObject().put("cancelled", true).put("message", "文件归类扫描已停止").toString()\n            }\n            sourceCount = sources.size\n            coverage = JSONArray()\n            sources.forEachIndexed { index, source ->\n                if (cancelled.get() || items.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS) return@forEachIndexed\n                progress(Progress("正在读取兼容来源目录", index + 1, sources.size, displayPath(source.directory.path)))\n                collectSource(source, started, items, progress)\n            }\n        }\n'''
if 'collectSharedIndex(started' not in text:
    if old not in text:
        raise RuntimeError('FileOrganizer scan anchor changed')
    text = text.replace(old, new, 1)
    text = text.replace('Snapshot(id, System.currentTimeMillis(), sources.size, truncated, immutable)', 'Snapshot(id, System.currentTimeMillis(), sourceCount, truncated, immutable)', 1)
    text = text.replace('.put("roots", sources.size)', '.put("roots", sourceCount)\n            .put("coverage", coverage)', 1)
    helper_anchor = '    private fun discoverSourceRoots(started: Long, progress: (Progress) -> Unit): List<SourceRoot> {'
    helper = r'''    private fun collectSharedIndex(
        started: Long,
        out: MutableMap<String, PlannedMove>,
        progress: (Progress) -> Unit
    ): Pair<Int, JSONArray>? {
        val script = File("/data/adb/modules/baize_v2/storage-index.sh")
        if (!script.isFile) return null
        progress(Progress("正在建立全应用共享存储索引", 0, 0, displayPath(script.path)))
        val process = runCatching {
            ProcessBuilder("/system/bin/sh", script.path, "refresh", "organizer")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null
        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.get() || elapsed(started) >= SCAN_BUDGET_MS) {
                process.destroy()
                return null
            }
        }
        if (process.exitValue() != 0) return null
        val index = File(stateDir, "index/storage-files.nul")
        if (!index.isFile) return null
        val roots = LinkedHashSet<String>()
        var visited = 0
        forEachNulPath(index) { rawPath ->
            if (cancelled.get() || out.size >= MAX_ITEMS || elapsed(started) >= SCAN_BUDGET_MS) return@forEachNulPath false
            val file = File(rawPath)
            if (!file.isFile || isSymlink(file) || skipFile(file)) return@forEachNulPath true
            val descriptor = indexedSource(file.path) ?: return@forEachNulPath true
            if (descriptor.policy == SourcePolicy.APP_USER_FILES && !isAppUserFile(file, canonical(file))) return@forEachNulPath true
            roots += canonical(descriptor.directory)
            val userId = userIdForPath(file.path) ?: return@forEachNulPath true
            addPlannedMove(file, canonical(descriptor.directory), descriptor.group, userId, out)
            visited += 1
            if (visited % 250 == 0) progress(Progress("正在复用共享索引生成归类计划", visited, 0, displayPath(file.path)))
            true
        }
        return roots.size to coverageJson()
    }

    private fun indexedSource(path: String): SourceRoot? {
        val mediaRoot = mediaUserRoot(path) ?: return null
        val relative = canonical(File(path)).removePrefix(canonical(mediaRoot) + "/")
        if (!relative.contains('/')) return SourceRoot(mediaRoot, "内部存储根目录", SourcePolicy.TOP_LEVEL_ONLY)
        val parts = relative.split('/')
        if (parts.size >= 3 && parts[0] == "Android" && parts[1] == "media") {
            val root = File(mediaRoot, "Android/media/${parts[2]}")
            return SourceRoot(root, "${parts[2]} · 应用媒体", SourcePolicy.APP_USER_FILES)
        }
        if (parts.size >= 4 && parts[0] == "Android" && parts[1] == "data") {
            val packageName = parts[2]
            val filesIndex = parts.indexOf("files")
            val root = if (filesIndex == 3) File(mediaRoot, "Android/data/$packageName/files")
            else File(mediaRoot, "Android/data/$packageName")
            return SourceRoot(root, "$packageName · 应用文件", SourcePolicy.APP_USER_FILES)
        }
        val root = File(mediaRoot, parts.first())
        return SourceRoot(root, sourceGroup(root.path), SourcePolicy.FULL_DOWNLOAD_TREE)
    }

    private fun forEachNulPath(file: File, block: (String) -> Boolean) {
        FileInputStream(file).use { input ->
            val buffer = ByteArrayOutputStream(256)
            while (true) {
                val value = input.read()
                if (value < 0) {
                    if (buffer.size() > 0) block(buffer.toString(Charsets.UTF_8.name()))
                    break
                }
                if (value == 0) {
                    val keepGoing = block(buffer.toString(Charsets.UTF_8.name()))
                    buffer.reset()
                    if (!keepGoing) break
                } else if (buffer.size() < 16_384) {
                    buffer.write(value)
                }
            }
        }
    }

    private fun coverageJson(): JSONArray {
        val result = JSONArray()
        val file = File(stateDir, "index/coverage.tsv")
        if (!file.isFile) return result
        file.useLines { lines ->
            lines.drop(1).take(300).forEach { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 5) return@forEach
                result.put(JSONObject()
                    .put("status", columns[0])
                    .put("group", columns[1])
                    .put("files", columns[2].toLongOrNull() ?: 0L)
                    .put("bytes", columns[3].toLongOrNull() ?: 0L)
                    .put("path", columns[4])
                    .put("reason", columns.getOrNull(5).orEmpty()))
            }
        }
        return result
    }

'''
    text = text.replace(helper_anchor, helper + helper_anchor, 1)
organizer.write_text(text)

# ---------------------------------------------------------------------------
# AIDL progress callbacks, server-side pagination and coverage API
# ---------------------------------------------------------------------------
write(AIDL / "ITaskProgressCallback.aidl", r'''
package io.github.xgl34222220.baize.root;

oneway interface ITaskProgressCallback {
    void onTaskProgress(String stateJson);
}
''')

profile_aidl = AIDL / "IProfileRootService.aidl"
text = profile_aidl.read_text()
if 'registerTaskProgressCallback' not in text:
    text = text.replace(
        '    String getTaskHistory(int limit);\n',
        '    String getTaskHistory(int limit);\n    String getTaskHistoryPage(int offset, int limit);\n    String getScanCoverage();\n',
        1,
    )
    text = text.replace(
        '    String getTaskState();\n',
        '    String getTaskState();\n    void registerTaskProgressCallback(ITaskProgressCallback callback);\n    void unregisterTaskProgressCallback(ITaskProgressCallback callback);\n',
        1,
    )
profile_aidl.write_text(text)

service = JAVA / "root/BaiZeProfileRootService.kt"
text = service.read_text()
if 'RemoteCallbackList' not in text:
    text = text.replace('import android.os.Process\n', 'import android.os.Process\nimport android.os.RemoteCallbackList\n', 1)
    text = text.replace(
        '    private val taskRunning = AtomicBoolean(false)\n',
        '    private val taskRunning = AtomicBoolean(false)\n    private val progressCallbacks = RemoteCallbackList<ITaskProgressCallback>()\n    @Volatile private var lastCallbackAt = 0L\n',
        1,
    )
    text = text.replace(
        '        override fun getTaskHistory(limit: Int): String = taskHistoryJson(limit)\n',
        '        override fun getTaskHistory(limit: Int): String = taskHistoryJson(limit)\n\n        override fun getTaskHistoryPage(offset: Int, limit: Int): String = taskHistoryPageJson(offset, limit)\n\n        override fun getScanCoverage(): String = scanCoverageJson().toString()\n',
        1,
    )
    text = text.replace(
        '        override fun cancelCurrentTask() {\n',
        '''        override fun registerTaskProgressCallback(callback: ITaskProgressCallback?) {\n            if (callback == null) return\n            progressCallbacks.register(callback)\n            runCatching { callback.onTaskProgress(getTaskState()) }\n        }\n\n        override fun unregisterTaskProgressCallback(callback: ITaskProgressCallback?) {\n            if (callback != null) progressCallbacks.unregister(callback)\n        }\n\n        override fun cancelCurrentTask() {\n''',
        1,
    )
    text = text.replace('.put("otherDetails", otherDetailsJson(latestReport))\n', '.put("otherDetails", otherDetailsJson(latestReport))\n            .put("coverage", scanCoverageJson())\n', 1)
    text = text.replace('.put("otherDetails", otherDetailsJson(File(stateDir, "reports/latest.tsv")))\n', '.put("otherDetails", otherDetailsJson(File(stateDir, "reports/latest.tsv")))\n            .put("coverage", scanCoverageJson())\n', 1)
    text = text.replace(
        '    private fun parseHistoryCategoryDetails(raw: String): JSONArray {\n',
        '''    private fun taskHistoryPageJson(offset: Int, requestedLimit: Int): String {\n        val source = JSONObject(taskHistoryJson(100))\n        val entries = source.optJSONArray("entries") ?: JSONArray()\n        val safeOffset = offset.coerceIn(0, entries.length())\n        val safeLimit = requestedLimit.coerceIn(1, 30)\n        val end = (safeOffset + safeLimit).coerceAtMost(entries.length())\n        val page = JSONArray()\n        for (index in safeOffset until end) page.put(entries.optJSONObject(index))\n        return source.put("entries", page)\n            .put("offset", safeOffset)\n            .put("nextOffset", end)\n            .put("total", entries.length())\n            .put("hasMore", end < entries.length())\n            .put("count", page.length())\n            .toString()\n    }\n\n    private fun scanCoverageJson(): JSONArray {\n        val result = JSONArray()\n        val file = File(STATE_DIR, "index/coverage.tsv")\n        if (!file.isFile) return result\n        file.useLines { lines ->\n            lines.drop(1).take(300).forEach { raw ->\n                val columns = raw.split('\t', limit = 6)\n                if (columns.size < 5) return@forEach\n                result.put(JSONObject()\n                    .put("status", columns[0])\n                    .put("group", columns[1])\n                    .put("files", columns[2].toLongOrNull() ?: 0L)\n                    .put("bytes", columns[3].toLongOrNull() ?: 0L)\n                    .put("path", columns[4])\n                    .put("reason", columns.getOrNull(5).orEmpty()))\n            }\n        }\n        return result\n    }\n\n    private fun parseHistoryCategoryDetails(raw: String): JSONArray {\n''',
        1,
    )
    text = text.replace(
        '            taskStateJson = running\n                .put("running", true)\n                .put("operation", "module-$mode")\n                .put("phase", phase)\n                .put("elapsedMs", SystemClock.elapsedRealtime() - started)\n                .toString()\n',
        '            taskStateJson = running\n                .put("running", true)\n                .put("operation", "module-$mode")\n                .put("phase", phase)\n                .put("elapsedMs", SystemClock.elapsedRealtime() - started)\n                .toString()\n            publishTaskState()\n',
        1,
    )
    text = text.replace(
        '            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))\n            .toString()\n    }\n\n    private fun updateOrganizerState',
        '            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))\n            .toString()\n        publishTaskState()\n    }\n\n    private fun updateOrganizerState',
        1,
    )
    text = text.replace(
        '            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))\n            .toString()\n    }\n\n    private fun failure',
        '''            .put("elapsedMs", (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))\n            .toString()\n        publishTaskState()\n    }\n\n    private fun publishTaskState(force: Boolean = false) {\n        val now = SystemClock.elapsedRealtime()\n        if (!force && now - lastCallbackAt < 220L) return\n        lastCallbackAt = now\n        val state = taskStateJson\n        val count = progressCallbacks.beginBroadcast()\n        try {\n            for (index in 0 until count) runCatching { progressCallbacks.getBroadcastItem(index).onTaskProgress(state) }\n        } finally {\n            progressCallbacks.finishBroadcast()\n        }\n    }\n\n    private fun failure''',
        1,
    )
    text = text.replace('                taskStateJson = idleState()\n', '                taskStateJson = idleState()\n                publishTaskState(true)\n')
service.write_text(text)

# Main dashboard and APK screen register callbacks; polling remains a slow fallback only.
dashboard_activity = JAVA / "MiuixDashboardActivity.kt"
text = dashboard_activity.read_text()
if 'ITaskProgressCallback' not in text:
    text = text.replace('import io.github.xgl34222220.baize.root.IProfileRootService\n', 'import io.github.xgl34222220.baize.root.IProfileRootService\nimport io.github.xgl34222220.baize.root.ITaskProgressCallback\n', 1)
    text = text.replace(
        '    private var pollJob: Job? = null\n',
        '''    private var pollJob: Job? = null\n    private var taskCallbackRegistered = false\n    private val taskProgressCallback = object : ITaskProgressCallback.Stub() {\n        override fun onTaskProgress(stateJson: String?) {\n            val json = runCatching { JSONObject(stateJson.orEmpty()) }.getOrNull() ?: return\n            runOnUiThread { renderTaskState(json) }\n        }\n    }\n''',
        1,
    )
    text = text.replace(
        '            rootService = IProfileRootService.Stub.asInterface(binder)\n            profileBound = true\n',
        '            rootService = IProfileRootService.Stub.asInterface(binder)\n            profileBound = true\n            taskCallbackRegistered = runCatching { rootService?.registerTaskProgressCallback(taskProgressCallback); true }.getOrDefault(false)\n',
        1,
    )
    text = text.replace(
        '            rootService = null\n            profileBound = false\n',
        '            rootService = null\n            profileBound = false\n            taskCallbackRegistered = false\n',
        1,
    )
    text = text.replace('getTaskHistory(100)', 'getTaskHistoryPage(0, 30)')
    text = text.replace('delay(250)', 'delay(if (taskCallbackRegistered) 1800 else 350)')
    destroy_anchor = '    override fun onDestroy() {'
    if destroy_anchor in text:
        text = text.replace(destroy_anchor, '    override fun onDestroy() {\n        if (taskCallbackRegistered) runCatching { rootService?.unregisterTaskProgressCallback(taskProgressCallback) }\n', 1)
dashboard_activity.write_text(text)

apk_activity = JAVA / "ApkScanActivity.kt"
text = apk_activity.read_text()
if 'ITaskProgressCallback' not in text:
    text = text.replace('import io.github.xgl34222220.baize.root.IProfileRootService\n', 'import io.github.xgl34222220.baize.root.IProfileRootService\nimport io.github.xgl34222220.baize.root.ITaskProgressCallback\n', 1)
    text = text.replace(
        '    private var pollJob: Job? = null\n',
        '''    private var pollJob: Job? = null\n    private var taskCallbackRegistered = false\n    private val taskProgressCallback = object : ITaskProgressCallback.Stub() {\n        override fun onTaskProgress(stateJson: String?) {\n            val state = runCatching { JSONObject(stateJson.orEmpty()) }.getOrNull() ?: return\n            runOnUiThread { if (state.optBoolean("running")) renderTaskState(state) }\n        }\n    }\n''',
        1,
    )
    text = text.replace(
        '            service = IProfileRootService.Stub.asInterface(binder)\n            serviceBound = true\n',
        '            service = IProfileRootService.Stub.asInterface(binder)\n            serviceBound = true\n            taskCallbackRegistered = runCatching { service?.registerTaskProgressCallback(taskProgressCallback); true }.getOrDefault(false)\n',
        1,
    )
    text = text.replace('            service = null\n            serviceBound = false\n', '            service = null\n            serviceBound = false\n            taskCallbackRegistered = false\n', 1)
    text = text.replace('delay(350)', 'delay(if (taskCallbackRegistered) 1800 else 350)')
    text = text.replace('        if (serviceBound) runCatching { RootService.unbind(connection) }', '        if (taskCallbackRegistered) runCatching { service?.unregisterTaskProgressCallback(taskProgressCallback) }\n        if (serviceBound) runCatching { RootService.unbind(connection) }', 1)
    text = text.replace(
        '            val parsedItems = parseItems(json.optJSONArray("otherDetails"))\n',
        '            val parsedItems = parseItems(json.optJSONArray("otherDetails"))\n            val coverage = parseCoverage(json.optJSONArray("coverage"))\n',
        1,
    )
    text = text.replace('                items = parsedItems,\n', '                items = parsedItems,\n                coverage = coverage,\n', 1)
    text = text.replace(
        '    private fun parseItems(array: JSONArray?): List<ApkScanItem> = buildList {\n',
        '''    private fun parseCoverage(array: JSONArray?): List<ScanCoverageItem> = buildList {\n        if (array == null) return@buildList\n        for (index in 0 until array.length()) {\n            val item = array.optJSONObject(index) ?: continue\n            add(ScanCoverageItem(\n                status = item.optString("status"),\n                group = item.optString("group"),\n                files = item.optLong("files", 0L),\n                bytes = item.optLong("bytes", 0L),\n                path = item.optString("path"),\n                reason = item.optString("reason")\n            ))\n        }\n    }\n\n    private fun parseItems(array: JSONArray?): List<ApkScanItem> = buildList {\n''',
        1,
    )
    text = text.replace('    val items: List<ApkScanItem> = emptyList(),\n', '    val items: List<ApkScanItem> = emptyList(),\n    val coverage: List<ScanCoverageItem> = emptyList(),\n', 1)
    text = text.replace(
        'private data class ApkScanItem(\n',
        '''private data class ScanCoverageItem(\n    val status: String,\n    val group: String,\n    val files: Long,\n    val bytes: Long,\n    val path: String,\n    val reason: String\n)\n\nprivate data class ApkScanItem(\n''',
        1,
    )
    ui_anchor = '        item {\n            Column(modifier = Modifier.padding(horizontal = 20.dp)) {\n                Text(\n                    "SCAN RESULTS",'
    coverage_ui = '''        if (state.coverage.isNotEmpty()) {\n            item {\n                Column(modifier = Modifier.padding(horizontal = 20.dp)) {\n                    Text("SCAN COVERAGE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)\n                    Text("扫描覆盖报告", fontSize = 26.sp, fontWeight = FontWeight.Black)\n                    Text("已扫描 ${state.coverage.count { it.status == \"scanned\" || it.status == \"partial\" }} 个来源；可直接查看未读取原因", color = MaterialTheme.colorScheme.onSurfaceVariant)\n                }\n            }\n            items(state.coverage.take(40), key = { "${it.group}|${it.path}" }) { item ->\n                Card(\n                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),\n                    shape = RoundedCornerShape(20.dp),\n                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)\n                ) {\n                    Column(Modifier.padding(15.dp)) {\n                        Text("${if (item.status == \"scanned\") \"✓\" else \"!\"} ${item.group}", fontWeight = FontWeight.Bold)\n                        Text("${item.files} 个文件 · ${Formatter.formatFileSize(context, item.bytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)\n                        Text(item.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n                        if (item.reason.isNotBlank()) Text(item.reason, color = MaterialTheme.colorScheme.error, fontSize = 10.sp)\n                    }\n                }\n            }\n        }\n\n'''
    if ui_anchor not in text:
        raise RuntimeError('APK UI anchor changed')
    text = text.replace(ui_anchor, coverage_ui + ui_anchor, 1)
apk_activity.write_text(text)

# ---------------------------------------------------------------------------
# Compose stability and page-local state slices
# ---------------------------------------------------------------------------
dashboard_ui_file = find_kt('data class DashboardUiState(')
text = dashboard_ui_file.read_text()
if 'import androidx.compose.runtime.Immutable' not in text:
    text = text.replace('import androidx.compose.runtime.Composable\n', 'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.Immutable\n', 1)
for name in ['ScanPerformanceUiState', 'DashboardUiState', 'AppJunkUiItem', 'AppJunkCategoryUiItem', 'GeneralJunkUiItem', 'HistoryUiItem', 'HistoryCategoryUiItem', 'HistoryAppUiItem', 'SchedulerUiState']:
    text = text.replace(f'data class {name}(', f'@Immutable\ndata class {name}(', 1)
if 'forHistoryPage' not in text:
    text += r'''

private fun DashboardUiState.forHomePage(): DashboardUiState = copy(
    rawLogName = "", rawLog = "", history = emptyList()
)

private fun DashboardUiState.forCleanPage(): DashboardUiState = copy(
    rawLogName = "", rawLog = "", history = emptyList(), lifetimeRuns = 0,
    lifetimeReleased = 0, lifetimeFiles = 0, lifetimeEmptyFiles = 0,
    lifetimeEmptyDirs = 0, lifetimeFragments = 0, lifetimeElapsed = 0
)

private fun DashboardUiState.forHistoryPage(): DashboardUiState = DashboardUiState(
    lastReleased = lastReleased,
    scanCompleted = scanCompleted,
    scanBytes = scanBytes,
    scanFiles = scanFiles,
    scanEmptyFiles = scanEmptyFiles,
    scanEmptyDirs = scanEmptyDirs,
    scanFragments = scanFragments,
    scanErrors = scanErrors,
    scanElapsed = scanElapsed,
    lifetimeRuns = lifetimeRuns,
    lifetimeReleased = lifetimeReleased,
    lifetimeFiles = lifetimeFiles,
    lifetimeEmptyFiles = lifetimeEmptyFiles,
    lifetimeEmptyDirs = lifetimeEmptyDirs,
    lifetimeFragments = lifetimeFragments,
    lifetimeElapsed = lifetimeElapsed,
    recentApps = recentApps,
    recentJunk = recentJunk,
    history = history
)

private fun DashboardUiState.forLogsPage(): DashboardUiState = DashboardUiState(
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, rawLogName = rawLogName, rawLog = rawLog
)

private fun DashboardUiState.forSettingsPage(): DashboardUiState = DashboardUiState(
    connected = connected, ready = ready, running = running, serviceText = serviceText,
    taskPhase = taskPhase, whitelistCount = whitelistCount, scanPerformance = scanPerformance
)
'''
    text = text.replace('HomeRoute(UiStyle.MATERIAL, state, actions)', 'HomeRoute(UiStyle.MATERIAL, state.forHomePage(), actions)')
    text = text.replace('CleanRoute(UiStyle.MATERIAL, state, scheduler, actions)', 'CleanRoute(UiStyle.MATERIAL, state.forCleanPage(), scheduler, actions)')
    text = text.replace('HistoryRoute(UiStyle.MATERIAL, state, actions)', 'HistoryRoute(UiStyle.MATERIAL, state.forHistoryPage(), actions)')
    text = text.replace('LogsRoute(UiStyle.MATERIAL, state, actions)', 'LogsRoute(UiStyle.MATERIAL, state.forLogsPage(), actions)')
    text = text.replace('SettingsRoute(UiStyle.MATERIAL, state, scheduler, appearance, actions)', 'SettingsRoute(UiStyle.MATERIAL, state.forSettingsPage(), scheduler, appearance, actions)')
    text = text.replace('HomeRoute(UiStyle.MIUIX, state, actions)', 'HomeRoute(UiStyle.MIUIX, state.forHomePage(), actions)')
    text = text.replace('CleanRoute(UiStyle.MIUIX, state, scheduler, actions)', 'CleanRoute(UiStyle.MIUIX, state.forCleanPage(), scheduler, actions)')
    text = text.replace('HistoryRoute(UiStyle.MIUIX, state, actions)', 'HistoryRoute(UiStyle.MIUIX, state.forHistoryPage(), actions)')
    text = text.replace('LogsRoute(UiStyle.MIUIX, state, actions)', 'LogsRoute(UiStyle.MIUIX, state.forLogsPage(), actions)')
    text = text.replace('SettingsRoute(UiStyle.MIUIX, state, scheduler, appearance, actions)', 'SettingsRoute(UiStyle.MIUIX, state.forSettingsPage(), scheduler, appearance, actions)')
dashboard_ui_file.write_text(text)

history_miuix = JAVA / "ui/history/miuix/HistoryScreenMiuix.kt"
text = history_miuix.read_text().replace('最多保留最近 100 次任务明细', '服务端分页读取；当前显示最近 30 次任务')
history_miuix.write_text(text)

# ---------------------------------------------------------------------------
# Baseline profile and macrobenchmark compile target
# ---------------------------------------------------------------------------
write(APP / "src/main/baseline-prof.txt", r'''
HSPLio/github/xgl34222220/baize/MiuixDashboardActivity;->onCreate(Landroid/os/Bundle;)V
HSPLio/github/xgl34222220/baize/BaiZeApplication;->onCreate()V
HSPLio/github/xgl34222220/baize/performance/DisplayPerformanceController;->onActivityResumed(Landroid/app/Activity;)V
HSPLio/github/xgl34222220/baize/ui/home/HomeRouteKt;->HomeRoute
HSPLio/github/xgl34222220/baize/ui/history/HistoryRouteKt;->HistoryRoute
HSPLio/github/xgl34222220/baize/ui/clean/CleanRouteKt;->CleanRoute
Lio/github/xgl34222220/baize/root/BaiZeProfileRootService;
Lio/github/xgl34222220/baize/InstantCacheActivity;
Lio/github/xgl34222220/baize/ApkScanActivity;
''')

settings = V2 / "settings.gradle.kts"
text = settings.read_text()
if 'include(":macrobenchmark")' not in text:
    text = text.replace('include(":app")', 'include(":app")\ninclude(":macrobenchmark")')
settings.write_text(text)

root_gradle = V2 / "build.gradle.kts"
text = root_gradle.read_text()
if 'com.android.test' not in text:
    text = text.replace('    id("com.android.application") version "8.12.2" apply false\n', '    id("com.android.application") version "8.12.2" apply false\n    id("com.android.test") version "8.12.2" apply false\n', 1)
root_gradle.write_text(text)

write(V2 / "macrobenchmark/build.gradle.kts", r'''
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.xgl34222220.baize.macrobenchmark"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}
''')
write(V2 / "macrobenchmark/src/main/AndroidManifest.xml", r'''
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <instrumentation
        android:name="androidx.test.runner.AndroidJUnitRunner"
        android:targetPackage="io.github.xgl34222220.baize" />
</manifest>
''')
write(V2 / "macrobenchmark/src/main/java/io/github/xgl34222220/baize/macrobenchmark/BaiZeBenchmark.kt", r'''
package io.github.xgl34222220.baize.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaiZeBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartupAndScrollFrames() = rule.measureRepeated(
        packageName = "io.github.xgl34222220.baize",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
    }
}
''')

# Fix duplicate wrong import if generated by IDE templates.
bench = V2 / "macrobenchmark/src/main/java/io/github/xgl34222220/baize/macrobenchmark/BaiZeBenchmark.kt"
bench.write_text(bench.read_text().replace('import androidx.benchmark.macro.MacrobenchmarkRule\n', ''))

# Manifest hardware acceleration and larger heap are explicit for Compose/Haze; no fixed refresh is hard-coded here.
manifest = APP / "src/main/AndroidManifest.xml"
text = manifest.read_text()
if 'android:hardwareAccelerated' not in text:
    text = text.replace('        android:allowBackup="false"\n', '        android:allowBackup="false"\n        android:hardwareAccelerated="true"\n        android:largeHeap="false"\n', 1)
manifest.write_text(text)

print('v2.2.0 core rework applied')
