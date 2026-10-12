package io.github.xgl34222220.baize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootFailureMessageTest {
    @Test fun rootRejectionMessageIsShownInsteadOfUnknownError() {
        val json = JSONObject().put("success", false).put("error", "module_safety_update_required")
            .put("message", "此清理任务需要配套 30030 模块")
        val text = RootFailureMessage.of(json, null)
        assertEquals("此清理任务需要配套 30030 模块", text)
        assertFalse(text.contains("未知错误"))
    }

    @Test fun validatorKeyIsNamed() {
        val json = JSONObject().put("success", false).put("error", "invalid_value")
            .put("message", "配置值超出范围").put("key", "apk_package_days")
        assertEquals("配置值超出范围（apk_package_days）", RootFailureMessage.of(json, null))
    }

    @Test fun errorCodeWithoutMessageIsDescribed() {
        val json = JSONObject().put("success", false).put("error", "invalid_value")
        assertEquals("配置值超出范围", RootFailureMessage.of(json, null))
    }

    @Test fun exceptionsFallBackToTheirMessageOrType() {
        assertEquals("binder died", RootFailureMessage.of(null, IllegalStateException("binder died")))
        assertEquals("IllegalStateException", RootFailureMessage.of(null, IllegalStateException()))
        assertFalse(RootFailureMessage.of(null, null).contains("未知错误"))
    }
}
