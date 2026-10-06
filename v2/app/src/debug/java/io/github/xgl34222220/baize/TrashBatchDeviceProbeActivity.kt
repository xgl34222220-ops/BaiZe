package io.github.xgl34222220.baize

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Exercises the production batch helper against real Android files, never user storage.
 * Present only in debug builds and non-exported; CI launches it through rooted AOSP adbd.
 */
class TrashBatchDeviceProbeActivity : ComponentActivity() {
    private fun counts(result: TrashBatchResult) = JSONObject().put("succeeded", result.succeeded)
        .put("failed", result.failed).put("remaining", result.remaining).put("reviewed", result.reviewed.size)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val token = intent.getStringExtra("probe_token").orEmpty()
        if (!BuildConfig.DEBUG || !emulator || !token.matches(Regex("[a-f0-9]{32}"))) {
            finish()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cache = cacheDir.canonicalFile
            val output = File(cache, "trash-batch-probe")
            val result = JSONObject().put("passed", false).put("token", token)
                .put("uid", Process.myUid()).put("api", Build.VERSION.SDK_INT)
                .put("debug", BuildConfig.DEBUG).put("emulatorOnly", emulator)
                .put("input", "New synthetic text files inside application cache only")
            var fixtureRoot: File? = null
            var stage = "cache-fixture"
            try {
                check(Process.myUid() >= 10_000) { "Probe must run as ordinary app UID" }
                check(!Files.isSymbolicLink(output.toPath()) && output.canonicalPath == output.absolutePath)
                check(output.isDirectory || output.mkdir())
                val root = File(cache, "trash-batch-fixtures-${UUID.randomUUID()}")
                check(!root.exists() && root.mkdir() && root.canonicalPath == root.absolutePath)
                fixtureRoot = root
                result.put("fixtureCacheOnly", root.parentFile == cache)
                // Same st_dev and directory-fsync implementations as the production Android
                // adapter, with an isolated cache root instead of public storage discovery.
                fun repository(name: String): Pair<File, OrdinaryFileTrash> {
                    val caseRoot = File(root, name).apply { check(mkdir()) }
                    val trash = OrdinaryFileTrash(File(caseRoot, "metadata"), listOf(File(caseRoot, "payload")),
                        sameFileSystem = { a, b -> Os.stat(a.path).st_dev == Os.stat(b.path).st_dev },
                        syncDirectory = { directory ->
                            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                                OsConstants.O_NONBLOCK or IndexedContentReview.closeOnExecFlag(), 0)
                            try {
                                check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
                                Os.fsync(fd)
                            } finally { Os.close(fd) }
                        })
                    return caseRoot to trash
                }
                fun add(caseRoot: File, trash: OrdinaryFileTrash, name: String, value: String = "data"): TrashEntry {
                    val original = File(caseRoot, name).apply { writeText(value) }
                    val bytes = original.length()
                    val hash = OrdinaryFileTrash.digest(original)
                    return trash.move(original, bytes, hash, 1_048_576L) {
                        original.isFile && original.length() == bytes && OrdinaryFileTrash.digest(original) == hash
                    }.also {
                        check(!original.exists() && File(it.stored).readText() == value)
                    }
                }

                stage = "reviewed-purge"
                val (purgeRoot, purgeTrash) = repository("purge")
                val purgeFirst = add(purgeRoot, purgeTrash, "first.txt")
                val purgeSecond = add(purgeRoot, purgeTrash, "second.txt")
                val purgeReview = TrashBatchSnapshot.capture(purgeTrash.entries())
                check(purgeReview.size == 2 && purgeReview.bytes == 8L)
                val arrival = add(purgeRoot, purgeTrash, "arrived-after-review.txt")
                val purgeResult = runTrashBatch(purgeReview, { false }, { entry ->
                    val bytes = purgeTrash.purge(entry.id, expected = entry)
                    check(bytes == entry.bytes)
                    TrashItemSuccess("Deleted synthetic payload")
                })
                check(purgeResult.succeeded == 2 && purgeResult.failed == 0 && purgeResult.remaining == 0)
                check(!File(purgeFirst.stored).exists() && !File(purgeSecond.stored).exists())
                check(purgeTrash.entries().map { it.id } == listOf(arrival.id))
                check(File(arrival.stored).readText() == "data")
                result.put("reviewedSnapshotPurge", true).put("newArrivalExcluded", true)
                    .put("purgeCounts", counts(purgeResult))

                stage = "reviewed-restore"
                val (restoreRoot, restoreTrash) = repository("restore")
                val restoreFirst = add(restoreRoot, restoreTrash, "first.txt", "restore one")
                val restoreSecond = add(restoreRoot, restoreTrash, "second.txt", "restore two")
                val notSelected = add(restoreRoot, restoreTrash, "not-selected.txt")
                val restoreReview = TrashBatchSnapshot.capture(restoreTrash.entries(), setOf(restoreFirst.id, restoreSecond.id))
                val restoreArrival = add(restoreRoot, restoreTrash, "arrived-after-review.txt")
                File(restoreFirst.original).writeText("new original must survive")
                val restoredFiles = mutableMapOf<String, File>()
                val restoreResult = runTrashBatch(restoreReview, { false }, { entry ->
                    val restored = restoreTrash.restore(entry.id, expected = entry)
                    restoredFiles[entry.id] = restored
                    TrashItemSuccess("Restored synthetic payload")
                })
                check(restoreResult.succeeded == 2 && restoreResult.failed == 0 && restoreResult.remaining == 0)
                check(requireNotNull(restoredFiles[restoreFirst.id]).readText() == "restore one")
                check(requireNotNull(restoredFiles[restoreSecond.id]).readText() == "restore two")
                check(restoredFiles[restoreFirst.id]?.path != restoreFirst.original)
                check(File(restoreFirst.original).readText() == "new original must survive")
                check(restoreTrash.entries().map { it.id }.toSet() == setOf(notSelected.id, restoreArrival.id))
                check(File(notSelected.stored).readText() == "data" && File(restoreArrival.stored).readText() == "data")
                check(!File(restoreFirst.stored).exists() && !File(restoreSecond.stored).exists())
                result.put("reviewedSnapshotRestore", true).put("restoreConflictPreserved", true)
                    .put("unselectedRestorePreserved", true).put("restoreCounts", counts(restoreResult))

                stage = "changed-payload-and-journal"
                val (mixedRoot, mixedTrash) = repository("mixed")
                val accepted = add(mixedRoot, mixedTrash, "accepted.txt")
                val changed = add(mixedRoot, mixedTrash, "changed.txt")
                val rewritten = add(mixedRoot, mixedTrash, "rewritten.txt")
                val missing = add(mixedRoot, mixedTrash, "missing.txt")
                val mixedReview = TrashBatchSnapshot.capture(listOf(accepted, changed, rewritten, missing))
                // Keep length unchanged so only the real content digest detects this rewrite.
                File(changed.stored).writeText("evil")
                val changedJournal = rewritten.copy(original = File(mixedRoot, "different-original.txt").path,
                    created = rewritten.created + 1)
                File(mixedRoot, "metadata/${rewritten.id}.json").writeText(changedJournal.json().toString())
                check(File(missing.stored).delete())
                val mixedResult = runTrashBatch(mixedReview, { false }, { entry ->
                    mixedTrash.purge(entry.id, expected = entry)
                    TrashItemSuccess("Deleted synthetic payload")
                })
                check(mixedResult.succeeded == 1 && mixedResult.failed == 3 && mixedResult.remaining == 0)
                check(mixedResult.items.single { it.entry.id == accepted.id }.succeeded)
                check(mixedResult.items.filterNot { it.entry.id == accepted.id }.all { !it.succeeded })
                check(mixedTrash.entries().map { it.id }.toSet() == setOf(changed.id, rewritten.id, missing.id))
                check(File(changed.stored).readText() == "evil")
                check(File(rewritten.stored).readText() == "data")
                check(!File(changed.original).exists() && !File(changedJournal.original).exists())
                check(runCatching { mixedTrash.restore(changed.id, expected = changed) }.isFailure)
                check(runCatching { mixedTrash.restore(rewritten.id, expected = rewritten) }.isFailure)
                check(!File(changed.original).exists() && !File(changedJournal.original).exists())
                check(File(changed.stored).readText() == "evil" && File(rewritten.stored).readText() == "data")
                result.put("changedPayloadRejected", true).put("changedJournalRejected", true)
                    .put("missingPayloadRecordPreserved", true).put("mixedResultsAccounted", true)
                    .put("mixedCounts", counts(mixedResult))

                stage = "cancel-remaining"
                val (cancelRoot, cancelTrash) = repository("cancel")
                val cancelEntries = (1..3).map { add(cancelRoot, cancelTrash, "item-$it.txt") }
                val cancelReview = TrashBatchSnapshot.capture(cancelEntries)
                var cancelRemaining = false
                val progress = mutableListOf<TrashBatchProgress>()
                val cancelledResult = runTrashBatch(cancelReview, { cancelRemaining }, { entry ->
                    cancelTrash.purge(entry.id, expected = entry)
                    // Simulate the UI's stop request arriving during the current operation.
                    // This item is complete and counted; only unstarted items may stop.
                    cancelRemaining = true
                    TrashItemSuccess("Current synthetic operation completed")
                }, onProgress = { progress += it })
                check(cancelledResult.succeeded == 1 && cancelledResult.failed == 0 && cancelledResult.remaining == 2)
                check(cancelledResult.items.single().entry.id == cancelEntries.first().id)
                check(!File(cancelEntries.first().stored).exists())
                check(cancelEntries.drop(1).all { File(it.stored).readText() == "data" })
                check(cancelTrash.entries().map { it.id }.toSet() == cancelEntries.drop(1).map { it.id }.toSet())
                check(progress.last().completed == 1 && progress.last().total == 3)
                val stoppedBeforeStart = runTrashBatch(TrashBatchSnapshot.capture(cancelTrash.entries()), { true }, {
                    error("A cancelled batch must not start another operation")
                })
                check(stoppedBeforeStart.items.isEmpty() && stoppedBeforeStart.remaining == 2)
                check(cancelEntries.drop(1).all { File(it.stored).readText() == "data" })
                result.put("cancelRemainingPreserved", true).put("cancelBeforeStartPreserved", true)
                    .put("cancelCounts", counts(cancelledResult))

                stage = "complete"
                result.put("passed", true).put("batchHelper", "production")
            } catch (error: Exception) {
                result.put("passed", false).put("stage", stage).put("error", error.javaClass.name)
                    .put("message", error.message.orEmpty()).put("stack", error.stackTraceToString())
            } finally {
                // The freshly-created UUID tree is the only cleanup target. Files.walk does
                // not follow symbolic links, including any introduced by a failed fixture.
                val cleanup = runCatching {
                    fixtureRoot?.let { root ->
                        check(root.parentFile == cache && root.name.startsWith("trash-batch-fixtures-"))
                        Files.walk(root.toPath()).use { paths ->
                            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                        }
                        check(!Files.exists(root.toPath()))
                    }
                }
                result.put("fixtureCleanupComplete", cleanup.isSuccess)
                cleanup.exceptionOrNull()?.let {
                    result.put("passed", false).put("cleanupError", it.toString())
                }
            }
            // A per-invocation token prevents a stale file from satisfying the host checker.
            if (output.isDirectory && output.canonicalPath == output.absolutePath && !Files.isSymbolicLink(output.toPath())) {
                val pending = File(output, "result-$token.tmp")
                pending.writeText(result.toString(2))
                Files.move(pending.toPath(), File(output, "result-$token.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            launch(Dispatchers.Main) { finish() }
        }
    }
}
