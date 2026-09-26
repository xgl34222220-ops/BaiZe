package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Test

class TargetedRulePathsTest {
    @Test fun specializesThePackageSlotWithoutExpandingOtherApps() {
        val pkg = "com.tencent.mm"
        for (base in listOf("/data/user/*", "/data/user_de/*", "/data/data", "/data/media/*/Android/data", "/storage/emulated/*/Android/media")) {
            assertEquals(listOf("$base/$pkg/cache/*"), TargetedRulePaths.select("$base/*/cache/*", setOf(pkg)))
            assertTrue(TargetedRulePaths.select("$base/com.other.app/cache/*", setOf(pkg)).isEmpty())
        }
        assertTrue(TargetedRulePaths.select("/data/system/cache", setOf(pkg)).isEmpty())
        assertTrue(TargetedRulePaths.select("/sdcard/Download/*", setOf(pkg)).isEmpty())
    }
    @Test fun ordinaryScansKeepOriginalRulesAndPackageWildcardsMatchExactly() {
        val pattern = "/data/user/*/com.tencent.?m/cache"
        assertEquals(listOf(pattern), TargetedRulePaths.select(pattern, emptySet()))
        assertEquals(listOf("/data/user/*/com.tencent.mm/cache"),
            TargetedRulePaths.select(pattern, setOf("com.tencent.mm", "com.tencent.mm.lite", "com.other.app")))
    }
}
