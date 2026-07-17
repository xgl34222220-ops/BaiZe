package io.github.xgl34222220.baize

import android.app.Application
import com.topjohnwu.superuser.Shell

class BaiZeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
        Alpha7UiPolish.install(this)
    }
}
