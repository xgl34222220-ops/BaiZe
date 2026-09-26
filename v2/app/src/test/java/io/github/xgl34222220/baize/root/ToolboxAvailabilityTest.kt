package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolboxAvailabilityTest {
    private fun config() = ToolboxConfig.normalize(JSONObject())
    @Test fun missingOrProtectedAppsCannotBePresentedAsReady() {
        val environment = JSONObject()
        assertFalse(ToolboxAvailability.check("wechat", config(), environment).getBoolean("available"))
        environment.put("installedPackages", JSONArray().put("com.tencent.mm"))
        environment.put("protectedPackages", JSONArray().put("com.tencent.mm"))
        assertTrue(ToolboxAvailability.check("wechat", config(), environment).getString("message").contains("保护"))
        // A preinstalled/system app can still have disposable caches; only maintenance is third-party-only.
        environment.put("protectedPackages", JSONArray())
        assertTrue(ToolboxAvailability.check("wechat", config(), environment).getBoolean("available"))
    }
    @Test fun redirectPrerequisiteIsCheckedBeforeAnyFileMovement() {
        val config = config().put("downloadRules", "/sdcard/Download/a+/sdcard/Documents/a").put("bindRedirect", true)
        val result = ToolboxAvailability.check("mounter", config, JSONObject())
        assertFalse(result.getBoolean("available")); assertTrue(result.getBoolean("unsupported"))
        config.put("bindRedirect", false)
        assertTrue(ToolboxAvailability.check("mounter", config, JSONObject()).getBoolean("available"))
    }
    @Test fun protectedProcessSelectionAndUnsupportedFreezerAreBlocked() {
        val config = config().put("processPackages", "com.test.app").put("processWhitelist", "com.test.app")
        val environment = JSONObject().put("eligiblePackages", JSONArray().put("com.test.app")).put("processSupported", true)
        assertFalse(ToolboxAvailability.check("process", config, environment).getBoolean("available"))
        config.put("processWhitelist", "").put("processMode", "freeze")
        assertTrue(ToolboxAvailability.check("process", config, environment).getBoolean("unsupported"))
        environment.put("freezeSupported", true)
        assertTrue(ToolboxAvailability.check("process", config, environment).getBoolean("available"))
    }
    @Test fun existingTasksAreKeptOutOfTheExtensionExecutor() {
        ToolboxCatalog.existingIds.forEach { assertFalse(ToolboxAvailability.check(it, config(), JSONObject()).getBoolean("available")) }
    }
    @Test fun requestSkipFailureAndObservedCompletionHaveDifferentLabels() {
        assertEquals("已提交请求", ToolboxAvailability.status(JSONObject().put("success", true).put("requestedOnly", true)))
        assertEquals("已跳过", ToolboxAvailability.status(JSONObject().put("success", true).put("skipped", true)))
        assertEquals("未完成", ToolboxAvailability.status(JSONObject().put("success", false)))
        assertEquals("不支持", ToolboxAvailability.status(JSONObject().put("unsupported", true)))
        assertEquals("已完成", ToolboxAvailability.status(JSONObject().put("success", true)))
    }
    @Test fun catalogErrorsAreExplainedWithoutDisablingUnrelatedSystemTools() {
        val env = JSONObject().put("applicationError", "应用列表读取失败").put("logcatSupported", true)
        assertEquals("应用列表读取失败", ToolboxAvailability.check("wechat", config(), env).getString("message"))
        assertTrue(ToolboxAvailability.check("logcat", config(), env).getBoolean("available"))
    }
}
