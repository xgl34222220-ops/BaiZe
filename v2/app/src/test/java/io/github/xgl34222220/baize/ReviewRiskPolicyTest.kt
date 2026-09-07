package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class ReviewRiskPolicyTest {
    @Test fun explicitChoiceDoesNotBecomeDefaultSelection() {
        assertTrue(ReviewRiskPolicy.selectable("high", ""))
        assertFalse(ReviewRiskPolicy.defaultSelected("high", "", true))
        assertFalse(ReviewRiskPolicy.selectable("critical", ""))
        assertFalse(ReviewRiskPolicy.selectable("low", "白名单保护"))
        assertFalse(ReviewRiskPolicy.defaultSelected("medium", "", false))
        assertTrue(ReviewRiskPolicy.defaultSelected("medium", "", true))
    }

    @Test fun attributesPrivateAndSharedPathsWithoutInventingAnOwner() {
        assertEquals("com.example.app", ReviewRiskPolicy.appPackage("/data/user/10/com.example.app/files/logs"))
        assertEquals("com.example.app", ReviewRiskPolicy.appPackage("/data/media/0/Android/data/com.example.app/cache"))
        assertEquals("com.example.app", ReviewRiskPolicy.appPackage("/data/data/com.example.app/app_bugly"))
        assertEquals("", ReviewRiskPolicy.appPackage("/data/tombstones/tombstone_01"))
    }
}
