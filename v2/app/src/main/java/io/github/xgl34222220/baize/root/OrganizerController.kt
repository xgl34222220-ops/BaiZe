package io.github.xgl34222220.baize.root

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class OrganizerController(
    cancelled: AtomicBoolean,
    stateDir: File = File(RootPaths.STATE_DIR)
) {
    private val engine = FileOrganizerEngine(cancelled, stateDir)

    fun scan(onProgress: (FileOrganizerEngine.Progress) -> Unit): String = engine.scan(onProgress)

    fun apply(
        snapshotId: String,
        selectionJson: String,
        onProgress: (FileOrganizerEngine.Progress) -> Unit
    ): String = engine.apply(snapshotId, selectionJson, onProgress)

    fun undo(onProgress: (FileOrganizerEngine.Progress) -> Unit): String = engine.undo(onProgress)
}
