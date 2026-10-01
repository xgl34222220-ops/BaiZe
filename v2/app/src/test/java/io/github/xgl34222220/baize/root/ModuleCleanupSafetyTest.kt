package io.github.xgl34222220.baize.root

import org.junit.Assert.*
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
        for (code in 30008..30015) {
            prop.writeText("id=baize_v2\nversion=v2.0.0\nversionCode=$code\n")
            assertNotNull(ModuleCleanupSafety.rejection(directory))
        }
        prop.writeText("id=baize_v2\nversion=v2.0.0\nversionCode=30016\n")
        assertNull(ModuleCleanupSafety.rejection(directory))
        prop.writeText("id=other\nversion=v2.0.0\nversionCode=30016\n")
        assertNotNull(ModuleCleanupSafety.rejection(directory))
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
