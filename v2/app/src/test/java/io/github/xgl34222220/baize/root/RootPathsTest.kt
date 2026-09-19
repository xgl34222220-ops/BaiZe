package io.github.xgl34222220.baize.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun groupedScriptsTakePrecedenceAndFlatModulesRemainUsable() {
        val module = temporary.newFolder("module")
        val legacy = File(module, "task-worker.sh").apply { writeText("legacy") }
        assertEquals(legacy, RootPaths.script("task-worker.sh", module))
        val current = File(module, "scripts/task-worker.sh").apply {
            parentFile.mkdirs()
            writeText("current")
        }
        assertEquals(current, RootPaths.script("task-worker.sh", module))
    }
}
