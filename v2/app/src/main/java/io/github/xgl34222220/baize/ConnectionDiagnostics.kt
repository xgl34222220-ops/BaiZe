package io.github.xgl34222220.baize

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection failures must remain inspectable even when the Root service cannot start. */
internal object ConnectionDiagnostics {
    private fun preferences(context: Context) =
        context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)

    private fun timestamp(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(time))

    @Synchronized
    fun startSession(context: Context) {
        val prefs = preferences(context)
        val history = listOf(prefs.getString("history", ""), prefs.getString("events", ""))
            .joinToString("\n").lineSequence().filter { it.isNotBlank() }.toList().takeLast(60)
        prefs.edit().putString("history", history.joinToString("\n"))
            .putString("events", "").putLong("session_started", System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun record(context: Context, event: String) {
        val prefs = preferences(context)
        val entries = (prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList() +
            "${timestamp(System.currentTimeMillis())} $event").takeLast(30)
        prefs.edit().putString("events", entries.joinToString("\n")).apply()
    }

    fun observeVersions(context: Context, versions: RuntimeVersions) {
        preferences(context).edit()
            .putString("versions", versions.toJson().toString())
            .putString("observed_app_name", BuildConfig.VERSION_NAME)
            .putLong("observed_app_code", BuildConfig.VERSION_CODE.toLong())
            .putLong("versions_observed_at", System.currentTimeMillis()).apply()
    }

    fun lastVersions(context: Context): RuntimeVersions? = runCatching {
        preferences(context).getString("versions", null)?.let { RuntimeVersions.fromPing(JSONObject(it)) }
    }.getOrNull()

    fun read(context: Context, currentVersions: Boolean = false): String {
        val prefs = preferences(context)
        val app = ComponentVersion.parse(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        val versions = lastVersions(context)
        val observedAt = prefs.getLong("versions_observed_at", 0L)
        val sessionStarted = prefs.getLong("session_started", 0L)
        return buildString {
            append("当前 App ${app.label} · Android ${android.os.Build.VERSION.RELEASE}\n")
            append("版本信息：").append(DiagnosticLabels.versionObservation(currentVersions)).append('\n')
            if (versions == null || observedAt <= 0) {
                append("尚无可用 Root/模块版本观测，不能确认版本匹配\n")
            } else {
                append("观测时间：").append(timestamp(observedAt)).append('\n')
                append("观测时 App：").append(ComponentVersion.parse(
                    prefs.getString("observed_app_name", null), prefs.getLong("observed_app_code", 0)
                ).label).append('\n')
                append("运行 Root：").append(versions.root.label).append('\n')
                append("已安装模块（module.prop）：").append(versions.moduleName ?: "名称未知")
                    .append(' ').append(versions.module.label).append('\n')
                append(versions.warning(app).ifBlank {
                    if (currentVersions) "最近观测版本与当前 App 一致" else "缓存版本与当前 App 一致，当前运行版本未验证"
                }).append('\n')
            }
            append("\n最近连接会话（不代表已连接）")
            if (sessionStarted > 0) append(" · 开始于 ").append(timestamp(sessionStarted))
            append('\n').append(prefs.getString("events", "").orEmpty().ifBlank { "暂无连接记录" })
            append("\n\n历史连接记录（非本次会话）\n")
            append(prefs.getString("history", "").orEmpty().ifBlank { "暂无历史连接记录" })
        }
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }
}
