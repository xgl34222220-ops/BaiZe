package io.github.xgl34222220.baize

import com.topjohnwu.superuser.ipc.RootService
import org.junit.Assert.*
import org.junit.Test

class RootConnectionMessagesTest {
    @Test fun failureMessagesDescribeKnownPhaseAndAction() {
        val expected = mapOf(
            RootService.BindingFailure.ROOT_UNAVAILABLE to "未取得 Root shell",
            RootService.BindingFailure.STARTUP_FAILED to "启动",
            RootService.BindingFailure.STARTUP_TIMEOUT to "启动超时",
            RootService.BindingFailure.BIND_FAILED to "绑定握手失败",
            RootService.BindingFailure.NULL_BINDING to "空 Binder"
        )
        assertEquals(RootService.BindingFailure.values().toSet(), expected.keys)
        expected.forEach { (reason, phase) ->
            val message = RootConnectionMessages.bindingFailure(reason)
            assertTrue(message.contains(phase))
            assertTrue(message.contains("手动重连"))
            if (reason != RootService.BindingFailure.ROOT_UNAVAILABLE) {
                assertFalse(message.contains("检查 Root 授权"))
            }
        }
    }

    @Test fun rootUnavailableDoesNotClaimUserDeniedAuthorization() {
        assertTrue(RootConnectionMessages.bindingFailure(RootService.BindingFailure.ROOT_UNAVAILABLE)
            .contains("不代表用户拒绝授权"))
    }

    @Test fun disconnectAndRetryExhaustionAreNotCrashEvidence() {
        assertTrue(RootConnectionMessages.DISCONNECTED.contains("不等于已确认服务崩溃"))
        assertTrue(RootConnectionMessages.RECOVERY_EXHAUSTED.contains("原因未确认"))
    }
}
