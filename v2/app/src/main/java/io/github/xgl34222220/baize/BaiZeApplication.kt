package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import io.github.xgl34222220.baize.performance.DisplayPerformanceController

class BaiZeApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        LegacyPreferencesAccess.initialize(this)
    }

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        ThemeManager.install(this)
        NativeNotifier.ensureChannel(this)
        DisplayPerformanceController.install(this)
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
    }
}

