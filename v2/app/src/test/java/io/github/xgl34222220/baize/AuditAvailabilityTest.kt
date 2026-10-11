package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.clean.miuix.automationUnavailableText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 3.0.1 功能审计：「不可用」状态必须准确并说明如何解决。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AuditAvailabilityTest {
    @Test fun disabledOrPendingModuleIsNotReportedAsActive() {
        val active = ModuleAvailability.of(JSONObject().put("module", true))
        assertTrue(active.active)
        val disabled = ModuleAvailability.of(JSONObject().put("module", true).put("moduleDisabled", true))
        assertFalse(disabled.active)
        assertTrue(disabled.inactiveText.contains("停用"))
        val pending = ModuleAvailability.of(JSONObject().put("module", false).put("modulePendingReboot", true))
        assertFalse(pending.active)
        assertTrue(pending.inactiveText.contains("重启"))
        // 旧版服务没有新字段：按旧语义处理，不误报停用。
        assertEquals("未安装自动清理模块", ModuleAvailability.of(JSONObject()).inactiveText)
    }

    @Test fun automationHeroExplainsWhyTasksWillNotRun() {
        val text = automationUnavailableText("未安装自动清理模块")
        assertTrue(text.startsWith("未安装自动清理模块"))
        assertTrue(text.contains("不会执行"))
        assertTrue(automationUnavailableText("").contains("模块未就绪"))
    }

    @Test fun photoSlimmingEntryOnlyForJpeg() {
        fun record(name: String, mime: String) = StorageFileRecord(1, "content://media/external/file/1", "/storage/emulated/0/DCIM/$name",
            name, 10, 10, mime)
        assertTrue(isJpegRecord(record("a.jpg", "image/jpeg")))
        assertTrue(isJpegRecord(record("b.JPEG", "")))
        assertFalse(isJpegRecord(record("c.heic", "image/heic")))
        assertFalse(isJpegRecord(record("d.png", "image/png")))
    }
}
