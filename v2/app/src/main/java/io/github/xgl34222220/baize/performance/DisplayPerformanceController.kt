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
