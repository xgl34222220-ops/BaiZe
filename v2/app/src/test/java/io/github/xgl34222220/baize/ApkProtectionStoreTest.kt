package io.github.xgl34222220.baize

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.xgl34222220.baize.root.WhitelistRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ApkProtectionStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @get:Rule val folder = TemporaryFolder()
    private val rules = ApkProtectionRules(setOf("keep.app"), setOf("/storage/emulated/10/Keep"))
    @Before fun clear() {
        context.getSharedPreferences("apk-protection-v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun response(packages: JSONArray = JSONArray(), paths: JSONArray = JSONArray()) = JSONObject()
        .put("version", 1).put("root", true).put("uid", 0).put("module", false)
        .put("packages", packages).put("paths", paths).toString()
    private fun source(raw: String) = object : ApkProtectionSource { override fun snapshot() = raw }

    @Test fun genuineEmptyRootRulesAreKnownWithoutAnInstalledModule() {
        val state = ApkProtectionStore.refresh(context, source(response()))
        assertTrue(state is ApkProtectionState.KnownRoot)
        assertEquals(ApkProtectionRules(emptySet(), emptySet()), state.rules)
    }
    @Test fun firstUseWithoutRootIsUnknownUntilExplicitLocalMode() {
        assertTrue(ApkProtectionStore.refresh(context, null) is ApkProtectionState.Unknown)
        assertTrue(ApkProtectionStore.enableLocalOnly(context))
        assertTrue(ApkProtectionStore.refresh(context, null) is ApkProtectionState.LocalOnly)
    }
    @Test fun historicalRootUserCannotDowngradeOnDisconnect() {
        ApkProtectionStore.rememberRoot(context, rules)
        assertFalse(ApkProtectionStore.enableLocalOnly(context))
        val state = ApkProtectionStore.refresh(context, null)
        assertTrue(state is ApkProtectionState.Unknown)
        assertEquals(rules, state.rules)
    }
    @Test fun localModeCannotMaskNewRootProtection() {
        assertTrue(ApkProtectionStore.enableLocalOnly(context))
        val state = ApkProtectionStore.refresh(context, source(response(JSONArray(rules.packages.toList()), JSONArray(rules.paths.toList()))))
        assertTrue(state is ApkProtectionState.KnownRoot)
        assertEquals(rules, state.rules)
        assertTrue(ApkProtectionStore.refresh(context, null) is ApkProtectionState.Unknown)
    }
    @Test fun failedReadPreservesOldTupleAndCannotPermitDeletion() {
        ApkProtectionStore.rememberRoot(context, rules)
        val broken = object : ApkProtectionSource { override fun snapshot(): String = error("unreadable") }
        assertTrue(ApkProtectionStore.refresh(context, broken) is ApkProtectionState.Unknown)
        assertEquals(rules, ApkProtectionStore.cached(context))
    }
    @Test fun malformedOrPartialResponsesNeverReplaceVerifiedRules() {
        ApkProtectionStore.rememberRoot(context, rules)
        for (bad in listOf("{}", "[]", "null", "{bad", response().replace("\"paths\":[]", "\"paths\":null"),
            response(JSONArray().put(123)), response(paths = JSONArray().put("/Keep/../private")),
            response().replace("\"uid\":0", "\"uid\":10000"), response().replace("\"version\":1", "\"version\":0"))) {
            assertTrue(bad, ApkProtectionStore.refresh(context, source(bad)) is ApkProtectionState.Unknown)
            assertEquals(rules, ApkProtectionStore.cached(context))
        }
    }
    @Test fun oldDaemonProtocolDoesNotSilentlyBecomeStandaloneMode() {
        val old = object : ApkProtectionSource { override fun snapshot(): String = error("unsupported operation") }
        assertTrue(ApkProtectionStore.refresh(context, old) is ApkProtectionState.Unknown)
        assertTrue(ApkProtectionStore.rootWasUsed(context))
        assertFalse(ApkProtectionStore.enableLocalOnly(context))
    }
    @Test fun localLegacyRulesAreConservativeAdditionsNotAuthoritativeEmptyRootState() {
        context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE).edit()
            .putStringSet("path_whitelist", setOf("/storage/emulated/10/Legacy")).commit()
        val state = ApkProtectionStore.refresh(context, source(response()))
        assertEquals(setOf("/storage/emulated/10/Legacy"), state.rules?.paths)
    }
    @Test fun explicitLegacyRemovalIsVisibleToTheNextCleanupProtectionRead() {
        val path = "/storage/emulated/0/Download"
        context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE).edit()
            .putStringSet("path_whitelist", setOf(path, "$path/Keep")).commit()
        assertTrue(path in ApkProtectionStore.refresh(context, source(response())).rules!!.paths)
        ApkProtectionStore.removeLegacyRules(context, paths = setOf(path))
        assertEquals(setOf("$path/Keep"), ApkProtectionStore.refresh(context, source(response())).rules!!.paths)
    }
    @Test fun failedLegacyCommitRestoresInMemoryProtectionAndReportsFailure() {
        val original = setOf("/storage/emulated/0/Download", "/storage/emulated/0/Documents")
        val real = context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE)
        real.edit().putStringSet("path_whitelist", original).commit()
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "baize_v2") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = real.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun commit(): Boolean { delegate.commit(); return false }
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { ApkProtectionStore.removeLegacyRules(wrapped, paths = setOf(original.first())) }.isFailure)
        assertEquals(original, ApkProtectionStore.legacyRules(context).paths)
    }
    @Test fun localModeRefusesMalformedProtectionRecords() {
        context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE).edit()
            .putStringSet("path_whitelist", setOf("relative/path")).commit()
        assertTrue(runCatching { ApkProtectionStore.enableLocalOnly(context) }.isFailure)
        assertTrue(ApkProtectionStore.fallback(context) is ApkProtectionState.Unknown)
    }
    private fun repository() = WhitelistRepository(File(folder.root, "whitelist.conf"),
        File(folder.root, "whitelist.packages"), emptyList())
    @Test fun confirmedAbsentRootFilesProduceOneValidEmptyTuple() {
        val json = JSONObject(repository().apkProtectionJson())
        assertEquals(0, json.getJSONArray("packages").length())
        assertEquals(0, json.getJSONArray("paths").length())
    }
    @Test fun nonRegularRuleFileIsAnErrorNotEmptyProtection() {
        File(folder.root, "whitelist.conf").mkdir()
        assertTrue(runCatching { repository().apkProtectionJson() }.isFailure)
    }
    @Test fun missingFileUnderNonDirectoryIsAnErrorNotEmptyProtection() {
        val parent = File(folder.root, "not-a-directory").apply { writeText("synthetic") }
        val repo = WhitelistRepository(File(parent, "rules"), File(folder.root, "packages"), emptyList())
        assertTrue(runCatching { repo.apkProtectionJson() }.isFailure)
    }
    @Test fun symlinkedProtectionFileIsRejectedWithoutFollowingIt() {
        val target = File(folder.root, "target").apply { writeText("/storage/emulated/10/Keep\n") }
        Files.createSymbolicLink(File(folder.root, "whitelist.conf").toPath(), target.toPath())
        assertTrue(runCatching { repository().apkProtectionJson() }.isFailure)
        assertTrue(target.readText().contains("Keep"))
    }
    @Test fun malformedPackageFileIsNotSilentlyFilteredToEmpty() {
        File(folder.root, "whitelist.packages").writeText("invalid/package\n")
        assertTrue(runCatching { repository().apkProtectionJson() }.isFailure)
    }
    @Test fun unterminatedGeneratedSectionCannotHideLaterManualProtection() {
        File(folder.root, "whitelist.conf").writeText("# BEGIN BAIZE APP WHITELIST\n/storage/emulated/10/Keep\n")
        assertTrue(runCatching { repository().apkProtectionJson() }.isFailure)
    }
    @Test fun malformedGeneratedSectionCannotTurnAProtectedPathIntoKnownEmpty() {
        for (path in listOf("/storage/emulated/10/Keep", "/data/media/10/Android/data/keep.app/../private")) {
            File(folder.root, "whitelist.conf").writeText("# BEGIN BAIZE APP WHITELIST\n$path\n# END BAIZE APP WHITELIST\n")
            assertTrue(path, runCatching { repository().apkProtectionJson() }.isFailure)
        }
    }
    @Test fun rootSnapshotReadsBothRuleKindsAndSurvivesRepositoryRecreation() {
        val repo = repository()
        repo.savePackages("[\"keep.app\"]")
        repo.addPath("/storage/emulated/10/Keep")
        val json = JSONObject(repository().apkProtectionJson())
        assertEquals(rules, ApkProtectionStore.parse(json.getJSONArray("packages").toString(), json.getJSONArray("paths").toString()))
    }
}
