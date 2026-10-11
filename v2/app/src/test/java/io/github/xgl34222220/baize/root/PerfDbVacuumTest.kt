package io.github.xgl34222220.baize.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerfDbVacuumTest {
    private fun header(walByte: Int = 1, text: String = "SQLite format 3"): ByteArray =
        ByteArray(100).also { bytes ->
            text.toByteArray(Charsets.US_ASCII).copyInto(bytes)
            bytes[18] = walByte.toByte(); bytes[19] = walByte.toByte()
        }

    @Test fun onlyAppDatabasesUnderDataDataOrDataUserAreAccepted() {
        assertEquals("com.example.notes", PerfDbVacuum.packageOf("/data/data/com.example.notes/databases/notes.db"))
        assertEquals("com.example.notes", PerfDbVacuum.packageOf("/data/user/10/com.example.notes/databases/notes.db"))
        assertNull(PerfDbVacuum.packageOf("/data/data/com.example.notes/files/notes.db"))
        assertNull(PerfDbVacuum.packageOf("/data/data/com.example.notes/databases/sub/notes.db"))
        assertNull(PerfDbVacuum.packageOf("/sdcard/notes.db"))
        assertNull(PerfDbVacuum.packageOf("/data/data/../system/databases/x.db"))
    }

    @Test fun journalModeIsPreservedFromTheHeader() {
        assertEquals("WAL", PerfDbVacuum.journalModeOf(header(walByte = 2)))
        assertEquals("DELETE", PerfDbVacuum.journalModeOf(header(walByte = 1)))
        assertEquals("DELETE", PerfDbVacuum.journalModeOf(ByteArray(10)))
    }

    @Test fun preflightMatchesModuleSkipRules() {
        val ok = "/data/data/com.example.notes/databases/notes.db"
        assertNull(PerfDbVacuum.preflight(ok, 65_536, 0, false, header()))
        assertEquals("wal-in-use", PerfDbVacuum.preflight(ok, 65_536, 12, false, header()))
        assertEquals("hot-journal", PerfDbVacuum.preflight(ok, 65_536, 0, true, header()))
        assertEquals("too-small", PerfDbVacuum.preflight(ok, 1_024, 0, false, header()))
        assertEquals("too-large", PerfDbVacuum.preflight(ok, 300L * 1024 * 1024, 0, false, header()))
        assertEquals("encrypted-or-not-sqlite", PerfDbVacuum.preflight(ok, 65_536, 0, false, header(text = "garbage-bytes!!")))
        assertEquals("blacklist", PerfDbVacuum.preflight("/data/data/com.tencent.mm/databases/x.db", 65_536, 0, false, header()))
        assertEquals("blacklist", PerfDbVacuum.preflight(ok, 65_536, 0, false, header(), listOf("com.example.notes")))
        assertEquals("encrypted-name", PerfDbVacuum.preflight("/data/data/com.example.notes/databases/EnMicroMsg.db", 65_536, 0, false, header()))
        assertEquals("path-not-allowed", PerfDbVacuum.preflight("/sdcard/x.db", 65_536, 0, false, header()))
    }

    @Test fun outputLinesAreParsableByTheModule() {
        assertEquals("ok 8192 4096 /data/data/a/databases/b.db", PerfDbVacuum.line("ok", "8192", "4096", "/data/data/a/databases/b.db"))
        assertEquals("skip integrity /data/data/a/databases/b.db", PerfDbVacuum.line("skip", "integrity", "/data/data/a/databases/b.db"))
        assertEquals("fail SQLiteException:no_such_collation /p", PerfDbVacuum.line("fail", "SQLiteException:no such collation", "/p"))
    }
}
