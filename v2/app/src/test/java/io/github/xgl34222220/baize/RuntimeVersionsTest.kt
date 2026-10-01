package io.github.xgl34222220.baize

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeVersionsTest {
    private val app = ComponentVersion.parse("2.8.2", 28002)

    @Test fun readDiagnosticsUpdateAcceptsUnchangedSchedulingModules() {
        val update = ComponentVersion("2.0.0", 30015L)
        for (code in 30008L..30014L) {
            val versions = RuntimeVersions(update, ComponentVersion("2.0.0", code))
            assertEquals("", versions.warning(update))
            assertTrue(versions.compatibilityNote(update).contains("当前模块可继续使用"))
        }
        assertTrue(RuntimeVersions(update, ComponentVersion("2.0.0", 30007L)).warning(update).isNotBlank())
    }

    @Test fun guardedApkUpdateKeepsOldSchedulingModuleButRequiresCurrentAppRootService() {
        val update = ComponentVersion("2.0.0", 30011L)
        for (code in listOf(30008L, 30009L, 30010L)) {
            val versions = RuntimeVersions(update, ComponentVersion("2.0.0", code))
            assertEquals("", versions.warning(update))
            assertTrue(versions.compatibilityNote(update).contains("当前模块可继续使用"))
            val staleRoot = RuntimeVersions(ComponentVersion("2.0.0", code), versions.module)
            assertTrue(staleRoot.warning(update).contains("Root"))
            assertFalse(staleRoot.compatibilityNote(update).contains("当前 Root 组件可继续使用"))
        }
    }

    private fun ping(rootName: Any = "2.8.2", rootCode: Any = 28002,
                     moduleName: Any = "v2.8.2", moduleCode: Any = "28002") = JSONObject()
        .put("rootVersionName", rootName).put("rootVersionCode", rootCode)
        .put("moduleVersionName", moduleName).put("moduleVersionCode", moduleCode)

    @Test fun matchingVersionsNeedBothNameAndCode() {
        val versions = RuntimeVersions.fromPing(ping())
        assertEquals(VersionComparison.MATCH, versions.root.compareWith(app))
        assertEquals(VersionComparison.MATCH, versions.module.compareWith(app))
        assertEquals("", versions.warning(app))
    }

    @Test fun archivePreviewUpdateKeepsTheVerified30008ModuleWithoutClaimingEqualVersions() {
        val update = ComponentVersion("2.0.0", 30009L)
        val old = ComponentVersion("2.0.0", 30008L)
        assertEquals(VersionComparison.MISMATCH, old.compareWith(update))
        for (root in listOf(old, update)) {
            val versions = RuntimeVersions(root, old)
            assertEquals("", versions.warning(update))
            val message = versions.compatibilityNote(update)
            assertTrue(message.contains("已验证兼容"))
            assertTrue(message.contains("当前模块可继续使用"))
            assertFalse(message.contains("重启"))
            assertFalse(message.contains("版本不一致"))
        }
    }

    @Test fun compatibilityExceptionDoesNotAcceptOtherOrUnknownEngines() {
        val update = ComponentVersion("2.0.0", 30009L)
        for (code in listOf(30007L, 30010L)) {
            val message = RuntimeVersions(update, ComponentVersion("2.0.0", code)).warning(update)
            assertTrue(message.contains("版本不一致"))
            assertFalse(message.contains("当前模块可继续使用"))
        }
        val unknown = RuntimeVersions(ComponentVersion("2.0.0", 30008L), ComponentVersion(null, null)).warning(update)
        assertTrue(unknown.contains("版本未知"))
        assertFalse(unknown.contains("当前模块可继续使用"))
        assertTrue(RuntimeVersions(update, ComponentVersion("2.1.0", 30008L)).warning(update).contains("版本不一致"))
    }

    @Test fun normalCompatibilityAndCachedVersionsOnlyAppearInDetails() {
        val update = ComponentVersion("2.0.0", 30010L)
        for (code in listOf(30008L, 30009L)) {
            val versions = RuntimeVersions(update, ComponentVersion("2.0.0", code))
            for (current in listOf(false, true)) {
                val visible = versions.presentation(update, current)
                assertEquals("", visible.warning)
                assertTrue(visible.details.contains("已验证兼容"))
                assertTrue(visible.details.contains(code.toString()))
                assertEquals(!current, visible.details.contains("历史版本缓存"))
            }
        }
        val mismatched = RuntimeVersions(update, ComponentVersion("2.0.0", 30007L))
        assertEquals("", mismatched.presentation(update, false).warning)
        assertTrue(mismatched.presentation(update, true).warning.contains("版本不一致"))
    }

    @Test fun missingOptionalModuleIsNotAnEngineFailureButUnreportedModuleStillNeedsVerification() {
        val noModule = RuntimeVersions.fromPing(ping().put("module", false)
            .put("moduleVersionName", JSONObject.NULL).put("moduleVersionCode", JSONObject.NULL))
        assertEquals("", noModule.warning(app))
        assertTrue(noModule.presentation(app, true).details.contains("未安装自动清理模块"))
        assertEquals(noModule, RuntimeVersions.fromPing(noModule.toJson()))
        assertTrue(RuntimeVersions(app, ComponentVersion(null, null)).warning(app).contains("版本未知"))
        assertTrue(RuntimeVersions(ComponentVersion(null, null), app, moduleInstalled = false).warning(app).contains("Root版本未知"))
    }

    @Test fun normalizesOneVersionPrefixAndWhitespace() {
        listOf("v2.8.2", "V2.8.2", " 2.8.2 ").forEach {
            assertEquals(app, ComponentVersion.parse(it, " 28002 "))
        }
        assertNull(ComponentVersion.parse("vV2.8.2", 28002).name)
    }

    @Test fun oldServiceMissingFieldsIsUnknownNotMatch() {
        val versions = RuntimeVersions.fromPing(JSONObject("{\"root\":true,\"module\":true}"))
        assertEquals(VersionComparison.UNKNOWN, versions.root.compareWith(app))
        assertEquals(VersionComparison.UNKNOWN, versions.module.compareWith(app))
        assertTrue(versions.warning(app).contains("版本未知"))
        assertEquals(VersionComparison.UNKNOWN, ComponentVersion.parse("2.8.2", null).compareWith(app))
    }

    @Test fun malformedFieldsAreNotCoercedIntoValidVersions() {
        listOf(null, JSONObject.NULL, true, JSONObject(), "bad", "", "null", "v").forEach {
            assertNull(ComponentVersion.parse(it, 28002).name)
        }
        listOf(null, JSONObject.NULL, true, 28002.0, "28002.0", "-1", 0, -1,
            "99999999999999999999999999", JSONObject()).forEach {
            assertNull(ComponentVersion.parse("2.8.2", it).code)
        }
        val versions = RuntimeVersions.fromPing(ping(JSONObject(), true, "null", "NaN"))
        assertEquals(VersionComparison.UNKNOWN, versions.root.compareWith(app))
        assertEquals(VersionComparison.UNKNOWN, versions.module.compareWith(app))
    }

    @Test fun differentNameOrCodeWarnsEvenWhenOtherFieldMissing() {
        assertEquals(VersionComparison.MISMATCH, ComponentVersion.parse("2.8.1", 28002).compareWith(app))
        assertEquals(VersionComparison.MISMATCH, ComponentVersion.parse("2.8.2", 28001).compareWith(app))
        assertEquals(VersionComparison.MISMATCH, ComponentVersion.parse(null, 28001).compareWith(app))
        val warning = RuntimeVersions.fromPing(ping("2.8.1", 28001, "v2.8.0", 28000)).warning(app)
        assertTrue(warning.contains("Root 2.8.1 (28001)"))
        assertTrue(warning.contains("模块 2.8.0 (28000)"))
        assertTrue(warning.contains("App 2.8.2 (28002)"))
        assertTrue(warning.contains("任务结束后"))
    }

    @Test fun mismatchAndUnknownAreReportedIndependently() {
        val warning = RuntimeVersions.fromPing(ping("2.8.1", 28001, JSONObject.NULL, JSONObject.NULL)).warning(app)
        assertTrue(warning.contains("版本不一致"))
        assertTrue(warning.contains("模块版本未知"))
    }

    @Test fun cachedSerializationPreservesVersionsAndModuleName() {
        val versions = RuntimeVersions.fromPing(ping().put("moduleName", "白泽 v2"))
        assertEquals(versions, RuntimeVersions.fromPing(JSONObject(versions.toJson().toString())))
        val unknown = RuntimeVersions.fromPing(JSONObject())
        assertEquals(unknown, RuntimeVersions.fromPing(JSONObject(unknown.toJson().toString())))
    }

    @Test fun cachedObservationIsNeverLabelledCurrent() {
        assertTrue(DiagnosticLabels.versionObservation(false).contains("历史版本缓存"))
        assertTrue(DiagnosticLabels.versionObservation(false).contains("不代表当前运行版本"))
        assertTrue(DiagnosticLabels.versionObservation(true).contains("本次连接"))
    }

    @Test fun oldCrashRemainsHistoricalWithoutCausalClaim() {
        val oldCrash = "时间：2025-08-01 12:00:00\nTheme Exception"
        listOf("App", "Root").forEach { kind ->
            val report = DiagnosticLabels.crashRecord(kind, oldCrash)
            assertTrue(report.contains("$kind 历史崩溃记录"))
            assertTrue(report.contains("未与本次连接关联"))
            assertTrue(report.contains("不能证明断连原因"))
            assertTrue(report.endsWith(oldCrash))
        }
        assertTrue(DiagnosticLabels.crashRecord("Root", null).contains("暂无 Root 崩溃记录"))
    }
}
