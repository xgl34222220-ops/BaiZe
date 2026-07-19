package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the last uncaught App exception before delegating to Android's normal crash handler.
 * The settings panel can display the record on the next launch, so ROM-only failures are no longer
 * diagnosed by guessing.
 */
object CrashRecorder {
    private const val FILE_NAME = "last-app-crash.txt"
    private const val MAX_CHARS = 12_000

    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = buildString {
                    append("白泽 v2 App 崩溃记录\n")
                    append("时间：").append(timestamp).append('\n')
                    append("线程：").append(thread.name).append('\n')
                    append("Android：").append(android.os.Build.VERSION.RELEASE)
                        .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n')
                    append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append("\n\n")
                    append(writer.toString())
                }.take(MAX_CHARS)
                File(application.filesDir, FILE_NAME).writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText()?.take(MAX_CHARS)
    }.getOrNull()

    fun summary(context: Context): String = read(context)
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("时间：") || it.contains("Exception") || it.contains("Error") }
        ?.ifBlank { "已记录一次 App 异常" }
        ?: "暂无 App 崩溃记录"

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
