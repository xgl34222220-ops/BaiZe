package io.github.xgl34222220.baize.root

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.xgl34222220.baize.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Record uncaught Root exceptions without suppressing Android's process termination. */
internal object RootCrashRecorder {
    private const val FILE_NAME = "last-root-crash.txt"
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val directory = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                directory.mkdirs()
                val report = buildString {
                    append("白泽 ").append(BuildConfig.VERSION_NAME).append(" Root 崩溃记录\n")
                    append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())).append('\n')
                    append("线程：").append(thread.name).append(" · PID ").append(Process.myPid()).append('\n')
                    append("Android：").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
                    append(error.stackTraceToString())
                }.take(24_000)
                File(directory, FILE_NAME).apply {
                    writeText(report)
                    // Root owns the file; the App UID must be able to read it in its private directory.
                    setReadable(true, false)
                }
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
        installed = true
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.take(24_000)
    }.getOrNull()

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
