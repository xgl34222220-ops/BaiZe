package io.github.xgl34222220.baize.root

import android.app.Application
import io.github.xgl34222220.baize.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RootCrashRecorderTest {
    @Test fun rootFailureIsReadableByAppAndOriginalHandlerStillRuns() {
        val context = RuntimeEnvironment.getApplication()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val error = IllegalStateException("media queue failure")
        var delegated: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, failure -> delegated = failure }
            RootCrashRecorder.install(context)
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            RootCrashRecorder.install(context)
            assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(Thread.currentThread(), error)
            assertSame(error, delegated)
            val report = RootCrashRecorder.read(context)!!
            assertTrue(report.contains(BuildConfig.VERSION_NAME))
            assertTrue(report.contains("Root 崩溃记录"))
            assertTrue(report.contains("media queue failure"))
            RootCrashRecorder.clear(context)
            assertNull(RootCrashRecorder.read(context))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
