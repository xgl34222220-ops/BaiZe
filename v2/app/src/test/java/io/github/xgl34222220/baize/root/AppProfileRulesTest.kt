package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppProfileRulesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun shipped(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "config/app-profiles.rules") }.first { it.isFile }

    private val roots = ReviewRuleCatalog.Roots("/d", listOf("/m"))

    private fun relatives(profile: ReviewRuleCatalog.AppProfile) =
        ReviewRuleCatalog.profileRules(shipped(), profile, roots).map { it.packageRelative }.toSet()

    @Test fun tiersAreCumulativeAndMediaNeedsEnhancedPlusOptIn() {
        val conservative = relatives(ReviewRuleCatalog.AppProfile(tier = 0, userMedia = true))
        val standard = relatives(ReviewRuleCatalog.AppProfile(tier = 1, userMedia = true))
        val enhanced = relatives(ReviewRuleCatalog.AppProfile(tier = 2))
        val enhancedMedia = relatives(ReviewRuleCatalog.AppProfile(tier = 2, userMedia = true))
        assertTrue(standard.containsAll(conservative) && standard.size > conservative.size)
        assertTrue(enhanced.containsAll(standard) && enhanced.size > standard.size)
        for (set in listOf(conservative, standard, enhanced)) {
            assertFalse(set.toString(), set.any { ReviewRuleCatalog.profileTouchesUserData(it) })
        }
        assertTrue("Tencent/MobileQQ/chatpic" in enhancedMedia)
        val media = ReviewRuleCatalog.profileRules(shipped(), ReviewRuleCatalog.AppProfile(tier = 2, userMedia = true), roots)
            .filter { it.packageRelative.endsWith("chatpic") }
        assertTrue(media.isNotEmpty() && media.all { it.risk == "high" && it.days >= 7 })
        assertTrue(ReviewRuleCatalog.profileRules(shipped(), ReviewRuleCatalog.AppProfile(enabled = false, tier = 2, userMedia = true), roots).isEmpty())
    }

    @Test fun hostileLinesAreIgnoredAndShortestRetentionWins() {
        val rules = folder.newFile("p.rules").apply {
            writeText("""
                standard|data|com.x|databases|0
                standard|ext|com.x|MicroMsg/0123456789abcdef0123456789abcdef/image2|0
                standard|data|com.x|../escape|0
                standard|data|com.x|cache/*|0
                enhanced-media|ext|com.x|chatpic|3
                turbo|data|com.x|cache|0
                standard|ext|com.x|cache|5
                enhanced|ext|com.x|cache|0
            """.trimIndent())
        }
        val targets = ReviewRuleCatalog.profileRules(rules, ReviewRuleCatalog.AppProfile(tier = 2, userMedia = true), roots)
        assertEquals(listOf("/m/com.x/cache"), targets.map { it.pattern })
        assertEquals(0, targets.single().days)
        assertEquals(5, ReviewRuleCatalog.profileRules(rules, ReviewRuleCatalog.AppProfile(tier = 1), roots).single().days)
    }

    @Test fun configDefaultsAndParsing() {
        assertEquals(ReviewRuleCatalog.AppProfile(), ReviewRuleCatalog.AppProfile.read(File(folder.root, "missing.conf")))
        val config = folder.newFile("config.conf").apply {
            writeText("app_profile_enabled=1\napp_profile_tier=9\napp_profile_user_media=1\n")
        }
        val parsed = ReviewRuleCatalog.AppProfile.read(config)
        assertEquals(2, parsed.effectiveTier)
        assertTrue(parsed.effectiveUserMedia)
        assertFalse(ReviewRuleCatalog.AppProfile(tier = 1, userMedia = true).effectiveUserMedia)
    }

    @Test fun workbenchScanFindsProfileTargetsButNeverDatabases() {
        val rules = folder.newFolder("rules")
        File(rules, "app-profiles.rules").writeText(
            "conservative|data|com.example.chat|files/xlog|0\n" +
                "standard|data|com.example.chat|databases|0\n" +
                "enhanced-media|ext|com.example.chat|chatpic|7\n"
        )
        val data = folder.newFolder("data")
        val log = File(data, "user/0/com.example.chat/files/xlog/entry").apply { parentFile!!.mkdirs(); writeText("x") }
        val db = File(data, "user/0/com.example.chat/databases/msg.db").apply { parentFile!!.mkdirs(); writeText("x") }
        val pic = File(data, "media/0/Android/data/com.example.chat/chatpic/a.jpg").apply {
            parentFile!!.mkdirs(); writeText("x"); setLastModified(System.currentTimeMillis() - 30L * 86_400_000L)
        }
        fun scan(profile: ReviewRuleCatalog.AppProfile): Set<String> {
            val engine = NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
                ruleDirectory = rules,
                ruleRoots = ReviewRuleCatalog.Roots(data.path, listOf("${data.path}/media/*/Android/data")),
                sharedRootOverride = emptyList(), appProfile = { profile })
            val result = JSONObject(engine.scan("rules", JSONObject().toString()) {})
            assertTrue(result.toString(), result.getBoolean("success"))
            val paths = mutableSetOf<String>()
            for (offset in 0 until result.getInt("totalCandidates") step 50) {
                val page = JSONObject(engine.page(result.getString("snapshotId"), offset, 50)).getJSONArray("items")
                for (index in 0 until page.length()) paths += page.getJSONObject(index).getString("path")
            }
            return paths
        }
        val standard = scan(ReviewRuleCatalog.AppProfile(tier = 1))
        assertTrue(standard.toString(), standard.any { it.startsWith(log.parentFile!!.canonicalPath) })
        assertFalse(standard.any { it.contains("databases") || it.contains("chatpic") })
        val media = scan(ReviewRuleCatalog.AppProfile(tier = 2, userMedia = true))
        assertTrue(media.toString(), media.any { it.contains(pic.parentFile!!.name) })
        assertFalse(media.any { it.startsWith(db.parentFile!!.canonicalPath) })
    }
}
