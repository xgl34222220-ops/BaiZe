package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeProfilePathSafetyTest {
    private val engine by lazy { NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false)) }

    @Test fun bindMountAliasesAreDeduplicatedWithoutRewritingTheOperationPath() {
        val out = linkedMapOf<String, Any>()
        val external = "/storage/emulated/10/Android/data/com.example/files/logs"
        val backing = "/data/media/10/Android/data/com.example/files/logs"
        for (path in listOf(external, backing, "/data/data/com.example/files/logs", "/data/user/0/com.example/files/logs")) {
            method("add").invoke(engine, out, candidate(path), options(), true)
        }
        assertEquals(2, out.size)
        val kept = json(out.values.first())
        assertEquals(external, kept.getString("path"))
        assertEquals("rules:$external", kept.getString("id"))
        assertEquals("com.example", kept.getString("packageName"))

        method("add").invoke(engine, out, candidate("/data/media/0/Android/data/com.example/files/logs"), options(), true)
        method("add").invoke(engine, out, candidate("/data/user_de/0/com.example/files/logs"), options(), true)
        assertEquals("Other users and device-encrypted data are separate targets", 4, out.size)
    }

    @Test fun whitelistCannotBeBypassedViaAFuseAliasOrAContainingDirectory() {
        val visible = "/storage/emulated/10/Android/data/com.example/files"
        val backing = "/data/media/10/Android/data/com.example/files"
        assertTrue(whitelisted("$backing/logs/old.log", visible))
        assertTrue(whitelisted("$visible/logs/old.log", backing))
        assertTrue(whitelisted(backing, "$visible/keep.txt"))
        assertTrue(whitelisted(visible, "$backing/keep.txt"))
        assertFalse(whitelisted("/data/media/0/Android/data/com.example/files/logs", visible))
        assertFalse(whitelisted("/data/media/10/Android/data/com.example.other/files/logs", visible))
        assertTrue(whitelisted(backing, "/"))

        val out = linkedMapOf<String, Any>()
        method("add").invoke(engine, out, candidate("$backing/logs"), options(visible), true)
        assertTrue(json(out.values.single()).getString("blockedReason").contains("白名单保护"))
    }

    @Test fun privateOwnerAliasSharesWhitelistButOtherUsersAndDeviceEncryptedFilesDoNot() {
        val owner = "/data/data/com.example/files"
        val credential = "/data/user/0/com.example/files"
        assertTrue(whitelisted("$owner/logs", credential))
        assertTrue(whitelisted("$credential/logs", owner))
        assertFalse(whitelisted("/data/user/10/com.example/files/logs", owner))
        assertFalse(whitelisted("/data/user_de/0/com.example/files/logs", owner))
    }

    @Test fun exactSystemLogRootsPermitContentsOnlyWithoutAuthorizingTheirSystemParents() {
        val allowed = method("ruleMutationAllowed")
        val roots = listOf("/data/anr", "/data/tombstones", "/data/system/dropbox", "/data/system/heapdump",
            "/data/misc/logd", "/data/vendor/log", "/data/log")
        for (root in roots) {
            assertEquals("Contents can be cleaned for $root", true, allowed.invoke(engine, root, false, true))
            assertEquals("The root must remain for $root", false, allowed.invoke(engine, root, true, true))
            assertEquals("The known root must be a directory", false, allowed.invoke(engine, root, false, false))
            assertEquals(true, allowed.invoke(engine, "$root/old.log", true, false))
        }
        for (path in listOf("/data", "/data/system", "/data/misc", "/data/vendor", "/data/logging", "/data/system/dropbox_backup")) {
            assertEquals("No broader root may be cleaned: $path", false, allowed.invoke(engine, path, false, true))
        }
    }

    private fun whitelisted(candidatePath: String, protectedPath: String) =
        method("whitelisted").invoke(engine, candidate(candidatePath), options(protectedPath)) as Boolean

    private fun options(vararg paths: String) = method("parseOptions").invoke(engine,
        JSONObject().put("whitelistPaths", JSONArray(paths.toList())).toString())

    private fun candidate(path: String) = method("candidate").invoke(engine,
        "rules", "rule_trash", "缓存", "medium", File(path), "", "", false, "", null, 0)

    private fun json(candidate: Any): JSONObject = candidate.javaClass.getDeclaredMethod("json")
        .apply { isAccessible = true }.invoke(candidate) as JSONObject

    private fun method(name: String) = NativeProfileEngine::class.java.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }
}
