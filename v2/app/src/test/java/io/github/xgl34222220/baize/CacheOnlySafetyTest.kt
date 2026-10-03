package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.root.CacheOnlyCommandPolicy
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class CacheOnlySafetyTest {
    @Test fun commandAlwaysIncludesCacheOnlyAndExplicitCurrentUser() {
        assertEquals(listOf("/system/bin/cmd", "package", "clear", "--cache-only", "--user", "10", "test.synthetic.app"),
            CacheOnlyCommandPolicy.command("test.synthetic.app", 10))
    }
    @Test fun shellInjectionCorePackagesAndInvalidUsersAreRejected() {
        for (pkg in listOf("test.app;rm -rf /", "--user", "android", "com.android.systemui", "io.github.xgl34222220.baize"))
            assertTrue(runCatching { CacheOnlyCommandPolicy.command(pkg, 0) }.isFailure)
        for (user in listOf(-1, 1000)) assertTrue(runCatching { CacheOnlyCommandPolicy.command("test.app", user) }.isFailure)
    }
    @Test fun protectedPackagesPathsAndParentsBlockWholePackageCacheRequests() {
        val pkg = "test.synthetic.app"
        for (path in listOf("/data/user/10/$pkg/files/keep", "/data/user_de/10/$pkg", "/storage/emulated/10/Android/data/$pkg/cache/keep", "/storage/emulated/10/Android"))
            assertTrue(path, cachePackageProtected(pkg, 10, ApkProtectionRules(emptySet(), setOf(path))))
        assertTrue(cachePackageProtected(pkg, 10, ApkProtectionRules(setOf(pkg), emptySet())))
        assertFalse(cachePackageProtected(pkg, 10, ApkProtectionRules(emptySet(), setOf("/storage/emulated/10/Pictures", "/data/user/0/$pkg"))))
    }
    @Test fun cacheResultShowsTheActualSystemFailureInsteadOfOnlyCounts() {
        val reply = JSONObject().put("message", "任务完成：成功 0 个，失败 1 个")
            .put("results", JSONArray().put(JSONObject().put("success", false).put("output", "Permission denied")))
        assertTrue(shizukuCacheResultMessage(reply).contains("Permission denied"))
        reply.getJSONArray("results").getJSONObject(0).put("timeout", true)
        assertTrue(shizukuCacheResultMessage(reply).contains("等待系统结果超时"))
    }
    @Test fun cacheResultPreservesExplicitCapabilityFailuresAndSuccessfulResults() {
        assertEquals("权限不可用", shizukuCacheResultMessage(JSONObject().put("message", "权限不可用")))
        val success = JSONObject().put("message", "成功 1 个")
            .put("results", JSONArray().put(JSONObject().put("success", true).put("output", "Success")))
        assertEquals("成功 1 个", shizukuCacheResultMessage(success))
    }
}
