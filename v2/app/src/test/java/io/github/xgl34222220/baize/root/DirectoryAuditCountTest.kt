package io.github.xgl34222220.baize.root

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectoryAuditCountTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun workbenchDirectoryOnlyCleanupKeepsItsActualCountInTheAuditEvent() {
        val task = JSONObject().put("mode", "workbench-clean").put("success", true)
            .put("bytes", 0).put("files", 0).put("emptyDirs", 3)
        AuditRepository(folder.root).recordNativeTask(task.toString(), "{\"success\":true}")
        val event = JSONObject(File(folder.root, "audit.jsonl").readLines().single())
        assertEquals(0L, event.getLong("bytes"))
        assertEquals(0L, event.getLong("files"))
        assertEquals(3L, event.getLong("directories"))
    }
}
