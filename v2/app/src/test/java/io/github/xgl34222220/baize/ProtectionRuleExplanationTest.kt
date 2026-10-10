package io.github.xgl34222220.baize

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 3.0.1：大文件结果行命中保护时，说明具体规则、可「临时允许本次」，结果文案准确。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectionRuleExplanationTest {
    private val zip = "/storage/emulated/0/Download/LuoShu-engine-25060RK16C_-20261005-000107.zip"
    private val uri = "content://media/external/file/42"
    private val stamp = ApkFileIdentity(zip, 4, 90, 133, 30, 31, 10, 20)
    private val files = object : ApkFileAccess {
        override fun canonical(path: String) = path
        override fun isDirectoryWithoutLink(path: String) = true
        override fun identity(path: String) = stamp
    }
    private val guard = ApkDeletionGuard(setOf("/storage/emulated/0"), "/storage/emulated/0", files)
    private fun rules(paths: Set<String> = emptySet(), packages: Set<String> = emptySet()) =
        ApkProtectionState.KnownRoot(ApkProtectionRules(packages, paths))

    @Test fun userPathRuleIsNamedInTheRowAndCanBeOverriddenOrRemoved() {
        val details = guard.protectionDetails(zip, rules(setOf("/data/media/0/Download")))
        val explanation = ProtectionRuleExplanation.of(details)
        assertEquals("已保留 · 命中保护规则：路径 /data/media/0/Download", explanation.rowReason)
        assertTrue(explanation.overridable)
        assertTrue(explanation.removable)
        assertFalse(explanation.systemLocked)
        assertNull(explanation.lockedText)
        assertTrue(explanation.lines.single().description.contains("父目录"))
    }

    @Test fun broadRootRuleExplainsWhyEveryFileIsProtected() {
        val explanation = ProtectionRuleExplanation.of(guard.protectionDetails(zip, rules(setOf("/sdcard"))))
        assertEquals("已保留 · 命中保护规则：路径 /sdcard", explanation.rowReason)
        val both = ProtectionRuleExplanation.of(guard.protectionDetails(zip, rules(setOf("/sdcard", "/storage/emulated/0/Download"))))
        assertTrue(both.rowReason.endsWith("（共 2 条）"))
    }

    @Test fun appRuleIsUserDefined() {
        val path = "/storage/emulated/0/Android/data/com.example.app/files/a.mp4"
        val explanation = ProtectionRuleExplanation.of(guard.protectionDetails(path, rules(packages = setOf("com.example.app"))))
        assertEquals("已保留 · 命中保护规则：应用 com.example.app", explanation.rowReason)
        assertTrue(explanation.overridable)
    }

    @Test fun internalTrashIsSystemLevelAndNeverOverridable() {
        val path = "/storage/emulated/0/${OrdinaryFileTrash.DIRECTORY}/io.github.xgl34222220.baize/abc"
        val explanation = ProtectionRuleExplanation.of(guard.protectionDetails(path, rules()))
        assertTrue(explanation.systemLocked)
        assertFalse(explanation.overridable)
        assertFalse(explanation.removable)
        assertTrue(explanation.lockedText!!.startsWith("系统级保护，无法解除"))
        assertEquals(ApkIndexedDeleteResult.PROTECTED,
            guard.validate(uri, path, 133, 30, stamp, rules(), allowUserRuleOverride = true))
    }

    @Test fun unverifiedProtectionIsNotARuleHitAndCannotBeOverridden() {
        val explanation = ProtectionRuleExplanation.of(guard.protectionDetails(zip, ApkProtectionState.Unknown("Root 未连接")))
        assertFalse(explanation.overridable)
        assertEquals("Root 未连接", explanation.unavailableReason)
        assertTrue(explanation.rowReason.contains("尚未核对"))
        assertEquals(ApkIndexedDeleteResult.PROTECTION_UNAVAILABLE,
            guard.validate(uri, zip, 133, 30, stamp, ApkProtectionState.Unknown("Root 未连接"), allowUserRuleOverride = true))
    }

    @Test fun oneOffOverrideOnlyPassesUserRulesAndStillChecksIdentity() {
        val protected = rules(setOf("/storage/emulated/0/Download"))
        assertEquals(ApkIndexedDeleteResult.PROTECTED, guard.validate(uri, zip, 133, 30, stamp, protected))
        assertNull(guard.validate(uri, zip, 133, 30, stamp, protected, allowUserRuleOverride = true))
        // 放行保护不放行身份核对：文件大小变化仍保留。
        assertEquals(ApkIndexedDeleteResult.CHANGED, guard.validate(uri, zip, 134, 30, stamp, protected, allowUserRuleOverride = true))
        assertEquals(ApkIndexedDeleteResult.UNVERIFIED, guard.validate(uri, zip, 133, 30, null, protected, allowUserRuleOverride = true))
    }

    @Test fun outcomeReasonUsesTheMatchedRule() {
        val details = guard.protectionDetails(zip, rules(setOf("/storage/emulated/0/Download")))
        val outcome = StorageDeleteOutcome(ApkIndexedDeleteResult.PROTECTED, protection = details)
        assertEquals("已保留 · 命中保护规则：路径 /storage/emulated/0/Download", outcome.reason)
        assertNotNull(outcome.protectionExplanation)
        // 旧结果没有规则细节时保留原文案，不伪造规则。
        assertEquals("已保留 · 命中保护名单", StorageDeleteOutcome(ApkIndexedDeleteResult.PROTECTED).reason)
        assertNull(StorageDeleteOutcome(ApkIndexedDeleteResult.CHANGED, protection = details).protectionExplanation)
    }

    @Test fun allProtectedSelectionReportsNothingMovableInsteadOfUnconfirmed() {
        assertEquals("没有可移动的文件：2 项受保护，点文件查看命中的规则",
            StorageTrashSummary.status(2, 0, "", 2, 0, 0, 0, false))
        assertEquals("没有可移动的文件：保护名单尚未核对，请重连 Root 后重试",
            StorageTrashSummary.status(1, 0, "", 0, 1, 0, 0, false))
        assertEquals("已移入回收站 1 个文件，占用 133 MB，尚未释放空间 · 1 项受保护",
            StorageTrashSummary.status(2, 1, "133 MB", 1, 0, 0, 0, false))
        assertEquals("移入回收站未确认，请重新扫描核对", StorageTrashSummary.status(1, 0, "", 0, 0, 0, 1, false))
        assertEquals("已停止 · 没有文件被移动 · 1 项受保护 · 1 项保留",
            StorageTrashSummary.status(3, 0, "", 1, 0, 1, 1, true).substringBefore(" · 1 项结果未确认"))
        assertFalse(StorageTrashSummary.status(2, 0, "", 2, 0, 0, 0, false).contains("未确认"))
    }

    @Test fun subTenthSecondElapsedIsNotShownAsZero() {
        assertEquals("", StorageTrashSummary.elapsedLabel(0))
        assertEquals("用时不到 0.1 秒（读取系统文件索引）", StorageTrashSummary.elapsedLabel(30))
        assertEquals("用时 1.5 秒", StorageTrashSummary.elapsedLabel(1500))
    }

    @Test fun systemProtectionsCoverCameraRecordingsChatDatabaseAndTrash() {
        val titles = SystemProtections.all.map { it.title }
        assertTrue(titles.containsAll(listOf("相机与相册原件", "录屏", "聊天数据库与配置", "应用私有数据", "白泽回收站")))
    }
}
