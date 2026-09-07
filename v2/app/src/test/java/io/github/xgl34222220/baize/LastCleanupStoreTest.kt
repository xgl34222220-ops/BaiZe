package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class LastCleanupStoreTest {
    @Test fun reopeningRetainsApplicationIdentityAndActualCategoryCounts() {
        val apps = listOf(AppJunkUiItem("com.example.app", "测试应用", "日志", 3, 1048576, 1,
            listOf(AppJunkCategoryUiItem("日志", 3, 1048576, 1, "/data/user/0/com.example.app/files/logs"))))
        val junk = listOf(GeneralJunkUiItem("系统日志", 2, 128, 0, "/data/anr/example"))
        val restored = LastCleanupStore.decode(JSONObject(LastCleanupStore.encode(apps, junk).toString()))
        assertEquals(apps, restored.first)
        assertEquals(junk, restored.second)
        assertEquals(emptyList<AppJunkUiItem>(), LastCleanupStore.decode(JSONObject()).first)
    }
}
