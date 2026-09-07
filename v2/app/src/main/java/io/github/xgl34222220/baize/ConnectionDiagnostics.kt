package io.github.xgl34222220.baize

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection failures must remain inspectable even when the Root service cannot start. */
internal object ConnectionDiagnostics {
    @Synchronized
    fun record(context: Context, event: String) {
        val prefs = context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val entries = (prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList() +
            "$time $event").takeLast(30)
        prefs.edit().putString("events", entries.joinToString("\n")).apply()
    }

    fun read(context: Context): String =
        "白泽 ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}\n连接记录\n" +
            context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)
                .getString("events", "暂无连接记录").orEmpty()

    fun clear(context: Context) {
        context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
