package io.github.xgl34222220.baize

import android.app.Application
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.compose.runtime.MutableState
import com.topjohnwu.superuser.ipc.RootService
import io.github.xgl34222220.baize.root.IProfileRootService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowToast
import java.lang.reflect.Proxy

/** Calls the real Activity callbacks. Root launch is replaced only in this host test. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [DashboardRootBindingShadow::class])
class DashboardConnectionCallbacksTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher); DashboardRootBindingShadow.binds = 0; ShadowToast.reset() }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun repeatedBindAndConnectedCallbacksDoNotRestartTheHandshakeOrToast() {
        val controller = Robolectric.buildActivity(MiuixDashboardActivity::class.java)
        val activity = controller.get()
        activity.call("connectPrimaryService")
        activity.call("connectPrimaryService")
        assertEquals(1, DashboardRootBindingShadow.binds)
        val binder = Binder()
        var registrations = 0
        val service = Proxy.newProxyInstance(IProfileRootService::class.java.classLoader,
            arrayOf(IProfileRootService::class.java)) { _, method, _ -> when (method.name) {
                "asBinder" -> binder
                "registerTaskProgressCallback" -> { registrations++; null }
                "ping" -> "{}"
                else -> if (method.returnType == String::class.java) "{}" else null
            }
        } as IProfileRootService
        binder.attachInterface(service, "io.github.xgl34222220.baize.root.IProfileRootService")
        val connection = activity.field("profileConnection") as RootService.Connection
        connection.onServiceConnected(null, binder)
        connection.onServiceConnected(null, binder)
        assertEquals(1, registrations)
        assertEquals(0, ShadowToast.shownToastCount())
        activity.call("releaseConnections")
        activity.lifecycleScope.cancel()
    }

    @Test fun failurePersistsThroughOtherConnectionRefreshAndRetryHasNoToastOrDuplicateBind() {
        val controller = Robolectric.buildActivity(MiuixDashboardActivity::class.java)
        val activity = controller.get()
        activity.call("connectPrimaryService")
        val connection = activity.field("profileConnection") as RootService.Connection
        connection.onBindingFailed(null, RootService.BindingFailure.ROOT_UNAVAILABLE)
        activity.call("updateConnectionState")
        assertTrue(activity.state().connectionFailed)
        assertTrue(activity.state().serviceText.contains("未取得 Root shell"))
        assertEquals(0, ShadowToast.shownToastCount())
        activity.call("reconnectService")
        activity.call("reconnectService")
        assertEquals(2, DashboardRootBindingShadow.binds)
        assertFalse(activity.state().connectionFailed)
        assertTrue(activity.state().connecting)
        assertEquals(0, ShadowToast.shownToastCount())
        activity.call("releaseConnections")
        activity.lifecycleScope.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun MiuixDashboardActivity.state() = (field("dashboardState") as MutableState<DashboardUiState>).value
    private fun MiuixDashboardActivity.field(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
    private fun MiuixDashboardActivity.call(name: String) = javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)
}

@Implements(RootService::class)
class DashboardRootBindingShadow {
    companion object {
        var binds = 0
        @JvmStatic @Implementation fun bind(intent: Intent, connection: ServiceConnection) { binds++ }
        @JvmStatic @Implementation fun unbind(connection: ServiceConnection) = Unit
    }
}
