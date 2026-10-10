package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfToolsPolicyTest {
    private val defaults = PerfToolsPolicy.DEFAULT_WHITELIST

    @Test
    fun everythingIsOffByDefault() {
        val config = PerfToolsConfig.parse(null)
        assertFalse(config.anyEnabled)
        assertFalse(config.memKill || config.dbForceStop || config.dex2oatForce || config.dex2oatAllApps)
        assertEquals(Dex2oatMode.SPEED_PROFILE, config.dex2oatMode)
        assertEquals(config, PerfToolsConfig.parse(PerfToolsConfig().encode()))
    }

    @Test
    fun configRoundTripsAndClampsOutOfRangeValues() {
        val config = PerfToolsConfig(freezeEnabled = true, freezeAfterMinutes = 30, memEnabled = true, memKill = true,
            memThresholdMb = 2048, memCooldownSeconds = 120, dex2oatMode = Dex2oatMode.EVERYTHING, dex2oatAllApps = true)
        assertEquals(config, PerfToolsConfig.parse(config.encode()))
        val clamped = PerfToolsConfig.parse("freeze_after_minutes=0\nmem_threshold_mb=1\nmem_cooldown_seconds=99999\npoll_seconds=5\ndex2oat_mode=rm -rf /\n")
        assertEquals(1, clamped.freezeAfterMinutes)
        assertEquals(100, clamped.memThresholdMb)
        assertEquals(3600, clamped.memCooldownSeconds)
        assertEquals(30, clamped.pollSeconds)
        assertEquals(Dex2oatMode.SPEED_PROFILE, clamped.dex2oatMode)
        assertTrue(PerfToolsConfig(pollSeconds = 1).encode().contains("poll_seconds=30"))
    }

    @Test
    fun defaultWhitelistProtectsChatAppsAndOwnApp() {
        for (process in listOf("com.tencent.mm", "com.tencent.mm:push", "com.tencent.mm:tools",
                "com.tencent.mobileqq:MSF", "com.tencent.tim", PerfToolsPolicy.APP_ID)) {
            assertTrue(process, PerfToolsPolicy.isWhitelisted(process, defaults))
        }
        assertFalse(PerfToolsPolicy.isWhitelisted("com.example.game", defaults))
    }

    @Test
    fun processEntryProtectsOnlyThatProcess() {
        val entries = listOf("com.foo:push")
        assertTrue(PerfToolsPolicy.isWhitelisted("com.foo:push", entries))
        assertFalse(PerfToolsPolicy.isWhitelisted("com.foo", entries))
        assertFalse(PerfToolsPolicy.isWhitelisted("com.foo:other", entries))
        assertTrue(PerfToolsPolicy.isWhitelisted("com.bar:remote", listOf("com.bar")))
    }

    @Test
    fun userListIsNormalized() {
        val list = PerfToolsPolicy.normalizeList(" com.a.b  # 注释\n\nbad name!\n../etc/passwd\n:x\ncom.a.b\ncom.c:push\n")
        assertEquals(listOf("com.a.b", "com.c:push"), list)
        assertEquals(PerfToolsPolicy.MAX_LIST_ENTRIES, PerfToolsPolicy.normalizeList((1..900).joinToString("\n") { "p$it" }).size)
    }

    @Test
    fun freezeWaitsForBackgroundDuration() {
        assertFalse(PerfToolsPolicy.freezeDue(1_000, 1_599, 10))
        assertTrue(PerfToolsPolicy.freezeDue(1_000, 1_600, 10))
        assertFalse("clock moved back", PerfToolsPolicy.freezeDue(2_000, 1_000, 1))
    }

    @Test
    fun memoryThresholdAndCooldown() {
        assertEquals(MemoryPressure.NONE, PerfToolsPolicy.memoryPressure(2_000_000, 1024, 0, 5_000, 300))
        assertEquals(MemoryPressure.LOW, PerfToolsPolicy.memoryPressure(900_000, 1024, 0, 5_000, 300))
        assertEquals(MemoryPressure.CRITICAL, PerfToolsPolicy.memoryPressure(400_000, 1024, 0, 5_000, 300))
        assertEquals(MemoryPressure.NONE, PerfToolsPolicy.memoryPressure(400_000, 1024, 4_900, 5_000, 300))
        assertEquals(MemoryPressure.CRITICAL, PerfToolsPolicy.memoryPressure(400_000, 1024, 4_600, 5_000, 300))
        assertEquals(MemoryPressure.NONE, PerfToolsPolicy.memoryPressure(-1, 1024, 0, 5_000, 300))
    }

    @Test
    fun databaseBlacklistAndSkipReasons() {
        val users = listOf("com.example.notes", "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")
        fun reason(pkg: String, name: String = "notes.db", size: Long = 65_536, wal: Long = 0, journal: Boolean = false,
                   header: String = "SQLite format 3", userBlacklist: List<String> = emptyList()) =
            PerfToolsPolicy.dbSkipReason(pkg, name, users, userBlacklist, size, wal, journal, header)
        assertNull(reason("com.example.notes"))
        for (chat in listOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")) assertEquals("blacklist", reason(chat))
        assertEquals("blacklist", reason("com.example.notes", userBlacklist = listOf("com.example.notes")))
        assertEquals("system-app", reason("com.android.providers.contacts"))
        assertEquals("encrypted-name", reason("com.example.notes", name = "EnMicroMsg.db"))
        assertEquals("encrypted-name", reason("com.example.notes", name = "vault.enc.db"))
        assertEquals("wal-in-use", reason("com.example.notes", wal = 12))
        assertEquals("hot-journal", reason("com.example.notes", journal = true))
        assertEquals("encrypted-or-not-sqlite", reason("com.example.notes", header = "\u0012\u0034garbage"))
        assertEquals("too-large", reason("com.example.notes", size = 300L * 1024 * 1024))
    }
}
