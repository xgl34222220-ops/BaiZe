package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class DashboardConnectionStateTest {
    @Test fun versionWarningSurvivesConnectionAndTaskStatusRefreshes() {
        val state = DashboardUiState(versionWarning = "Root 版本不一致")
        val refreshed = state.copy(connected = true, ready = true, serviceText = "组件已就绪")
            .copy(running = true, taskPhase = "后台任务已恢复")
        assertEquals("Root 版本不一致", refreshed.versionWarning)
    }

    @Test fun connectionLabelsSeparatePendingFailureAndModuleReadiness() {
        assertEquals("未连接", DashboardUiState().connectionLabel)
        assertEquals("连接中", DashboardUiState(connecting = true).connectionLabel)
        assertEquals("未就绪", DashboardUiState(connected = true).connectionLabel)
        assertEquals("已就绪", DashboardUiState(connected = true, ready = true).connectionLabel)
        assertEquals("连接失败", DashboardUiState(connected = true, ready = true, connectionFailed = true).connectionLabel)
        assertEquals("执行中", DashboardUiState(running = true, connectionFailed = true).connectionLabel)
    }
}
