package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import io.github.xgl34222220.baize.BuildConfig
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModuleCleanupSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun oldUnknownAndOtherModulesCannotStartCleanupAfterAnAppOnlyUpgrade() {
        val directory = temporary.newFolder()
        assertNotNull(ModuleCleanupSafety.rejection(directory))
        val prop = File(directory, "module.prop")
        for (code in 30008 until BuildConfig.VERSION_CODE) {
            prop.writeText("id=baize_v2\nversion=v2.0.0\nversionCode=$code\n")
            assertNotNull(ModuleCleanupSafety.rejection(directory))
        }
        prop.writeText("id=baize_v2\nversion=v2.0.0\nversionCode=${BuildConfig.VERSION_CODE}\n")
        assertNull(ModuleCleanupSafety.rejection(directory))
        prop.writeText("id=other\nversion=v2.0.0\nversionCode=${BuildConfig.VERSION_CODE}\n")
        assertNotNull(ModuleCleanupSafety.rejection(directory))
    }
    @Test fun releaseVersionNameOfThePairedModuleIsAccepted() {
        // 3.3.3 regression: module.prop carries version=v3.3.3; the old "2.0.0" pin rejected
        // every scheduler save with module_safety_update_required, shown as "保存失败：未知错误".
        val directory = temporary.newFolder()
        val prop = File(directory, "module.prop")
        prop.writeText("id=baize_v2\nname=白泽\nversion=v${BuildConfig.VERSION_NAME}\nversionCode=${BuildConfig.VERSION_CODE}\n")
        assertNull(ModuleCleanupSafety.rejection(directory))
        prop.writeText("id=baize_v2\nversion=v3.3.3\nversionCode=${BuildConfig.VERSION_CODE - 1}\n")
        val rejected = org.json.JSONObject(requireNotNull(ModuleCleanupSafety.rejection(directory)))
        assertEquals("module_safety_update_required", rejected.getString("error"))
        assertTrue(rejected.getString("message").contains("${BuildConfig.VERSION_CODE - 1}"))
    }
    @Test fun repositoryModulePropIsAcceptedByTheAppBuiltFromTheSameTree() {
        val candidates = listOf(File("../module/module.prop"), File("v2/module/module.prop"), File("../../v2/module/module.prop"))
        val source = candidates.firstOrNull { it.isFile } ?: return
        val directory = temporary.newFolder()
        source.copyTo(File(directory, "module.prop"))
        assertNull(ModuleCleanupSafety.rejection(directory))
    }
    @Test fun disablingOldTasksIsAllowedButEnablingAnyAutomaticGroupNeedsNewModule() {
        for (key in listOf("clean_apk_packages", "daily_schedule_enabled", "schedule_cache_enabled",
            "schedule_empty_enabled", "schedule_rules_enabled", "schedule_fragment_enabled", "schedule_deep_enabled", "schedule_organize_enabled")) {
            assertFalse(ModuleCleanupSafety.enablesAutomaticWork(mapOf(key to "0")))
            assertTrue(ModuleCleanupSafety.enablesAutomaticWork(mapOf(key to "1")))
        }
        assertFalse(ModuleCleanupSafety.enablesAutomaticWork(mapOf("schedule_cache_minutes" to "60")))
    }
}
