package io.github.xgl34222220.baize.root

import android.app.Application
import io.github.xgl34222220.baize.ReviewRiskPolicy
import io.github.xgl34222220.baize.WorkbenchItem
import io.github.xgl34222220.baize.reviewRiskSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ / 微信 / TIM 收到的安装包可能是用户想留的应用：
 * 默认不勾选、不进任何批量选择，逐项确认后只移入回收站（隔离区），从不永久删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatApkRecoverableCleanupTest {
    @get:Rule val folder = TemporaryFolder()

    private val quarantineState by lazy { folder.newFolder("state") }
    private val engine by lazy {
        NativeProfileEngine(RuntimeEnvironment.getApplication(), AtomicBoolean(false),
            quarantineRepository = QuarantineRepository(quarantineState, File(quarantineState, "missing.conf")),
            ruleDirectory = folder.newFolder("rules"))
    }

    @Test fun chatApkIsNeverBulkSelectableEvenWhenMediumIsAutomatic() {
        assertFalse(NativeProfileEngine.bulkSelectable(NativeProfileEngine.CHAT_APK_CATEGORY, "medium", "medium"))
        assertFalse(NativeProfileEngine.bulkSelectable(NativeProfileEngine.CHAT_APK_CATEGORY, "low", "medium"))
        assertTrue(NativeProfileEngine.bulkSelectable("rule_trash", "medium", "medium"))
        assertFalse(NativeProfileEngine.bulkSelectable("rule_trash", "medium", "low"))
        assertTrue(NativeProfileEngine.recoverableOnly(NativeProfileEngine.CHAT_APK_CATEGORY))
        assertFalse(NativeProfileEngine.recoverableOnly("empty_file"))
    }

    @Test fun oneTapAllSafeNeverTouchesChatApk() {
        val apk = folder.newFile("QQ.apk.1").apply { writeText("installer") }
        val snapshot = snapshot(apk, NativeProfileEngine.CHAT_APK_CATEGORY, "medium")
        val result = JSONObject(engine.clean(snapshot, JSONObject().put("__all_safe__", true).toString(), "{}") {})
        assertEquals("empty_selection", result.getString("error"))
        assertTrue(apk.exists())
        assertEquals("installer", apk.readText())
    }

    @Test fun explicitChatApkSelectionIsNeverPermanentlyDeleted() {
        val apk = folder.newFile("wechat.apk").apply { writeText("installer") }
        val snapshot = snapshot(apk, NativeProfileEngine.CHAT_APK_CATEGORY, "medium")
        val result = JSONObject(engine.clean(snapshot, JSONObject().put("apk:${apk.canonicalPath}", true).toString(), "{}") {})
        assertEquals(1, result.getInt("selected"))
        assertEquals(0L, result.getLong("deletedFiles"))
        assertEquals(0L, result.getLong("deletedBytes"))
        val detail = result.getJSONArray("details").getJSONObject(0)
        assertNotEquals("cleaned", detail.getString("action"))
        // 在真机上它会进入隔离区；测试目录不在聊天接收目录内，因此原样保留。两种情况都不是永久删除。
        val stored = File(quarantineState, "quarantine/items").listFiles().orEmpty()
        assertTrue(apk.exists() || stored.any { it.readText() == "installer" })
    }

    @Test fun quarantineAcceptsMediumChatApkButStillRejectsOtherMediumItems() {
        val repository = QuarantineRepository(quarantineState, File(quarantineState, "missing.conf"))
        val other = folder.newFile("other.log").apply { writeText("log") }
        val rejected = repository.quarantine("snap", "rules:${other.path}", other.canonicalPath, "rules", "rule_trash", "规则垃圾", "medium")
        assertFalse(rejected.success)
        assertTrue(other.exists())

        val apk = folder.newFile("tim.apk").apply { writeText("installer") }
        val moved = repository.quarantine("snap", "apk:${apk.path}", apk.canonicalPath, "apk",
            NativeProfileEngine.CHAT_APK_CATEGORY, "聊天收到的安装包", "medium")
        assertTrue(moved.message, moved.success)
        assertFalse(apk.exists())
        val restored = JSONObject(repository.restore(moved.id))
        assertTrue(restored.toString(), restored.optBoolean("success"))
        assertEquals("installer", apk.readText())
    }

    @Test fun reviewListDefaultsChatApkToUncheckedAndKeepsItOutOfBulkSelection() {
        assertFalse(ReviewRiskPolicy.defaultSelected("medium", "", true, NativeProfileEngine.CHAT_APK_CATEGORY))
        assertTrue(ReviewRiskPolicy.defaultSelected("medium", "", true, "rule_trash"))
        assertTrue(ReviewRiskPolicy.selectable("medium", ""))
        fun item(id: String, category: String) = WorkbenchItem(id, "profile", "apk", "", "QQ", category, "category:apk",
            "聊天收到的安装包", id, "medium", "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/$id",
            100, 1, 0, "", true)
        val items = listOf(item("chat", NativeProfileEngine.CHAT_APK_CATEGORY), item("log", "rule_trash"))
        assertEquals(setOf("log"), reviewRiskSelection(items, setOf("low", "medium")))
    }

    private fun snapshot(file: File, category: String, risk: String): String {
        fun method(name: String) = NativeProfileEngine::class.java.declaredMethods
            .single { it.name == name }.apply { isAccessible = true }
        val options = method("parseOptions").invoke(engine, JSONObject().put("maxAutoRisk", "medium").toString())
        val candidate = method("candidate").invoke(engine, "apk", category, "聊天收到的安装包", risk, file,
            "", "", true, "", null, 0).also { candidate ->
            candidate.javaClass.getDeclaredField("frozenTree").apply { isAccessible = true }
                .set(candidate, FrozenReviewTree.capture(file.toPath(), AtomicBoolean(false), 5_000))
        }
        val id = UUID.randomUUID().toString()
        val snapshotClass = NativeProfileEngine::class.java.declaredClasses.single { it.simpleName == "Snapshot" }
        val constructor = snapshotClass.declaredConstructors.single { it.parameterCount == 7 }.apply { isAccessible = true }
        val snapshot = constructor.newInstance(id, "apk", System.currentTimeMillis(), "", options, mutableListOf(candidate), 0L)
        val field = NativeProfileEngine::class.java.getDeclaredField("snapshots").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(engine) as MutableMap<String, Any>)[id] = snapshot
        return id
    }
}
