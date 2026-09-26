package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Test

class CachePathPolicyTest {
    private val pkg = "com.example.app"

    @Test fun coversMultiUserDeviceEncryptedExternalAndWebViewCaches() {
        for (base in listOf("/data/user/0", "/data/user/10", "/data/user_de/10", "/data/data")) {
            for (suffix in listOf("cache", "code_cache", "app_webview/Cache",
                "app_webview/Default/Code Cache/js", "app_x5webview/profile/GPUCache")) {
                assertTrue("$base/$pkg/$suffix", CachePathPolicy.allows("$base/$pkg/$suffix", pkg))
            }
        }
        assertTrue(CachePathPolicy.allows("/data/media/10/Android/data/$pkg/cache", pkg))
    }

    @Test fun rejectsOtherOwnersAndUserDataAndTraversal() {
        for (path in listOf("/data/user/0/$pkg/files", "/data/user/0/$pkg/databases",
            "/data/user/0/$pkg/shared_prefs", "/data/user/0/$pkg/cache/../files",
            "/data/user/0/$pkg/cache/./keep", "/data/user/0/$pkg/app_webview/Default/Cookies",
            "/data/user/0/$pkg/app_webview_backup/Cache", "/data/user/0/other.app/cache",
            "/data/media/0/Android/data/$pkg/files", "/data/media/0/DCIM/cache")) {
            assertFalse(path, CachePathPolicy.allows(path, pkg))
        }
    }
}
