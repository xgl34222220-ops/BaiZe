package io.github.xgl34222220.baize

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeVersionsTest {
    private val app = ComponentVersion.parse("2.8.2", 28002)

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
