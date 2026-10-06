package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OrganizerRecoveryTest {
    @Test fun journalRestoresLatestCommittedEntryAndIgnoresTornTail() {
        val dir = Files.createTempDirectory("organizer-recovery").toFile()
        try {
            val engine = FileOrganizerEngine(AtomicBoolean(), dir)
            val journal = File(dir, "test.journal")
            val write = FileOrganizerEngine::class.java.getDeclaredMethod("persistUndoRecord", File::class.java, JSONArray::class.java).apply { isAccessible = true }
            val read = FileOrganizerEngine::class.java.getDeclaredMethod("readUndoJson", File::class.java).apply { isAccessible = true }
            val first = JSONObject().put("source", "a").put("pending", true)
            write.invoke(engine, journal, JSONArray().put(first))
            first.put("pending", false)
            write.invoke(engine, journal, JSONArray().put(first))
            val second = JSONObject().put("source", "b").put("pending", true)
            write.invoke(engine, journal, JSONArray().put(first).put(second))
            journal.appendText("{\"source\":")
            val moves = (read.invoke(engine, journal) as JSONObject).getJSONArray("moves")
            assertEquals(2, moves.length())
            assertFalse(moves.getJSONObject(0).getBoolean("pending"))
            assertTrue(moves.getJSONObject(1).getBoolean("pending"))
            assertEquals(4, journal.readLines().size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun interruptedUndoDoesNotRepeatCompletedMoves() {
        val dir = Files.createTempDirectory("organizer-undo-recovery").toFile()
        try {
            val engine = FileOrganizerEngine(AtomicBoolean(), dir)
            val journal = File(dir, "test.journal")
            val write = FileOrganizerEngine::class.java.getDeclaredMethod("appendUndoEntry", File::class.java, JSONObject::class.java).apply { isAccessible = true }
            val read = FileOrganizerEngine::class.java.getDeclaredMethod("readUndoJson", File::class.java).apply { isAccessible = true }
            write.invoke(engine, journal, JSONObject().put("source", "a"))
            write.invoke(engine, journal, JSONObject().put("source", "b"))
            write.invoke(engine, journal, JSONObject().put("source", "a").put("undone", true))
            val moves = (read.invoke(engine, journal) as JSONObject).getJSONArray("moves")
            assertEquals(1, moves.length())
            assertEquals("b", moves.getJSONObject(0).getString("source"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun snapshotIdsCannotEscapeTheirDirectory() {
        val dir = Files.createTempDirectory("organizer-plan").toFile()
        try {
            val engine = FileOrganizerEngine(AtomicBoolean(), dir)
            for (id in listOf("../config", "", "/data/adb/test")) {
                assertEquals("snapshot_expired", JSONObject(engine.page(id, 0, 10)).getString("error"))
            }
        } finally { dir.deleteRecursively() }
    }
}
