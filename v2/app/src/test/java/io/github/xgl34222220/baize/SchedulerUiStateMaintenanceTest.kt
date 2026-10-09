package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.SchedulerRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerUiStateMaintenanceTest {
    @Test fun everySavedKeyIsAcceptedByTheRootConfigWriter() {
        val json = SchedulerUiState(appProfileTier = 2, appProfileUserMedia = true, maintenanceEnabled = false).toJson()
        for (key in json.keys()) {
            val range = SchedulerRepository.ALLOWED_CONFIG[key]
            assertTrue("$key is not writable", range != null)
            assertTrue("$key=${json.getInt(key)} out of range", json.getInt(key) in range!!)
        }
        assertEquals(2, json.getInt("app_profile_tier"))
        assertEquals(1, json.getInt("app_profile_user_media"))
        assertEquals(0, json.getInt("maintenance_enabled"))
    }

    @Test fun userMediaIsNeverSavedBelowEnhancedTier() {
        for (tier in 0..1) {
            assertEquals(0, SchedulerUiState(appProfileTier = tier, appProfileUserMedia = true).toJson().getInt("app_profile_user_media"))
        }
        val parsed = SchedulerUiState.fromJson(JSONObject().put("app_profile_tier", 1).put("app_profile_user_media", 1))
        assertFalse(parsed.appProfileUserMedia)
    }

    @Test fun defaultsMatchModuleDefaults() {
        val parsed = SchedulerUiState.fromJson(JSONObject())
        assertEquals(1, parsed.appProfileTier)
        assertFalse(parsed.appProfileUserMedia)
        assertTrue(parsed.maintenanceEnabled)
        assertEquals("", parsed.maintenanceSummary)
        assertEquals(30, parsed.appProfileMediaDays)
    }

    @Test fun mediaDaysAreClampedAndSaved() {
        assertEquals(90, SchedulerUiState(appProfileMediaDays = 90).toJson().getInt("app_profile_media_days"))
        assertEquals(7, SchedulerUiState(appProfileMediaDays = 1).toJson().getInt("app_profile_media_days"))
        assertEquals(7, SchedulerUiState.fromJson(JSONObject().put("app_profile_media_days", 7)).appProfileMediaDays)
    }

    @Test fun wechatUsageParsesModuleOutput() {
        val usage = WechatUsage.parse(
            "image|聊天图片|5368709120|media\nvideo|聊天视频|0|media\nreceived|收到的文件（不清理）|1024|protected\n" +
                "bad line\nevil|x|notanumber|media\ntotal|微信总占用|6442450944|total\naccounts|2\n"
        )
        assertEquals(listOf("image", "received"), usage.entries.map { it.key })
        assertEquals(6442450944L, usage.totalBytes)
        assertEquals(2, usage.accounts)
        assertTrue(usage.installed)
        assertEquals("5.0 GB", formatUsageBytes(5368709120L))
        assertEquals("不清理", wechatUsageTierHint("protected"))
        assertFalse(WechatUsage.parse("").installed)
    }

    @Test fun maintenanceSummaryIsCompactChinese() {
        val runtime = JSONObject().put("maintenance", JSONObject()
            .put("lastEpoch", 1_760_000_000L).put("result", "ok").put("dirtyBefore", "900").put("dirtyAfter", "80"))
        val summary = SchedulerUiState.fromJson(JSONObject().put("runtime", runtime)).maintenanceSummary
        assertTrue(summary, summary.contains("已完成") && summary.contains("900→80"))
        assertEquals("", maintenanceSummary(JSONObject().put("lastEpoch", 0L)))
    }
}
