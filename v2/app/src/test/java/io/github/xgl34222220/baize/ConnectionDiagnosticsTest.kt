package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ConnectionDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() = ConnectionDiagnostics.clear(context)

    @Test fun disconnectedAndNewAppSessionsUseTimestampedHistoricalVersionCache() {
        val app = ComponentVersion.parse(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        ConnectionDiagnostics.observeVersions(context, RuntimeVersions(app, app, "白泽 v2"))
        assertTrue(ConnectionDiagnostics.read(context, currentVersions = true).contains("本次连接最近观测"))
        ConnectionDiagnostics.startSession(context)
        val offline = ConnectionDiagnostics.read(context)
        assertTrue(offline.contains("历史版本缓存"))
        assertTrue(offline.contains("观测时间："))
        assertTrue(offline.contains("观测时 App：${app.label}"))
        assertTrue(offline.contains("运行 Root：${app.label}"))
        assertTrue(offline.contains("白泽 v2 ${app.label}"))
        assertTrue(offline.contains("当前运行版本未验证"))
    }

    @Test fun oldServiceObservationReplacesPreviousSuccessWithUnknown() {
        val app = ComponentVersion.parse(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        ConnectionDiagnostics.observeVersions(context, RuntimeVersions(app, app))
        ConnectionDiagnostics.observeVersions(context, RuntimeVersions.fromPing(JSONObject()))
        val report = ConnectionDiagnostics.read(context, currentVersions = true)
        assertTrue(report.contains("版本未知"))
        assertFalse(report.contains("最近观测版本与当前 App 一致"))
    }

    @Test fun latestSessionEventsAreSeparatedFromEarlierConnectionAttempts() {
        ConnectionDiagnostics.startSession(context)
        ConnectionDiagnostics.record(context, "old failure")
        ConnectionDiagnostics.startSession(context)
        ConnectionDiagnostics.record(context, "new attempt")
        val report = ConnectionDiagnostics.read(context)
        val historyStart = report.indexOf("历史连接记录")
        assertTrue(report.indexOf("new attempt") < historyStart)
        assertTrue(report.indexOf("old failure") > historyStart)
    }

    @Test fun corruptedOrClearedCacheCannotReportVersionSuccess() {
        context.getSharedPreferences("connection_diagnostics", Context.MODE_PRIVATE)
            .edit().putString("versions", "not json").apply()
        assertNull(ConnectionDiagnostics.lastVersions(context))
        assertTrue(ConnectionDiagnostics.read(context).contains("不能确认版本匹配"))
        ConnectionDiagnostics.clear(context)
        assertNull(ConnectionDiagnostics.lastVersions(context))
    }
}
