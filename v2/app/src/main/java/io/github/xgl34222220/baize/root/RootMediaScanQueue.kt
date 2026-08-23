package io.github.xgl34222220.baize.root

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root-side durable media-scan queue used by both organizer implementations.
 *
 * The queue lives under /data/adb, so only the RootService touches it.  Shell organizer tasks
 * append to the same pending file (or a spool file when the tiny filesystem lock is busy).
 * A flush atomically claims pending -> inflight, submits MediaScannerConnection batches and only
 * deletes inflight after all callbacks arrive.  A crash/failure therefore causes a retry rather
 * than a lost media refresh.  Duplicate scans are acceptable; lost queue entries are not.
 */
internal object RootMediaScanQueue {
    const val PENDING_NAME = "organizer-media-scan.nul"
    const val INFLIGHT_NAME = "organizer-media-scan.inflight.nul"
    const val LOCK_NAME = "organizer-media-scan.lock"
    const val SPOOL_PREFIX = "organizer-media-scan.spool."

    private const val BATCH_SIZE = 1000
    private const val STALE_LOCK_MS = 30_000L
    private const val STALE_TASK_QUEUE_MS = 60_000L
    private const val CALLBACK_TIMEOUT_MS = 120_000L
    private const val RETRY_BACKOFF_MS = 30_000L

    private val monitor = Any()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var activeToken = 0L
    private var nextToken = 1L
    private var retryAfterRealtime = 0L

    fun enqueue(stateDir: File, paths: Collection<String>): Int {
        val unique = LinkedHashSet<String>()
        paths.forEach { path -> if (path.isNotBlank()) unique += path }
        if (unique.isEmpty()) return 0

        val payload = ByteArrayOutputStream().use { out ->
            unique.forEach { path ->
                out.write(path.toByteArray(Charsets.UTF_8))
                out.write(0)
            }
            out.toByteArray()
        }
        stateDir.mkdirs()

        val queueLock = acquireQueueLock(stateDir)
        if (queueLock != null) {
            try {
                if (appendBytes(File(stateDir, PENDING_NAME), payload)) return unique.size
            } finally {
                queueLock.delete()
            }
        }

        // Never overwrite an existing pending file when the lock is busy.  Persist this batch in
        // its own spool; the next RootService flush merges every spool under the filesystem lock.
        return if (writeSpool(stateDir, payload)) unique.size else 0
    }

    fun onServiceStart(context: Context) {
        flush(context)
        // A shell writer may have held the short queue lock exactly while the service started.
        handler.postDelayed({ flush(context.applicationContext) }, 1_000L)
    }

    private data class Claim(val inflight: File, val paths: List<String>, val token: Long)

    fun flush(context: Context, stateDir: File = File(RootPaths.STATE_DIR)): Int {
        val appContext = context.applicationContext
        val claim = synchronized(monitor) {
            if (activeToken != 0L) return@synchronized null
            if (SystemClock.elapsedRealtime() < retryAfterRealtime) return@synchronized null

            val queueLock = acquireQueueLock(stateDir) ?: return@synchronized null
            val inflight = try {
                if (!recoverSpoolsLocked(stateDir)) return@synchronized null
                if (!recoverInflightLocked(stateDir)) return@synchronized null
                claimPendingLocked(stateDir) ?: return@synchronized null
            } finally {
                queueLock.delete()
            }

            val paths = runCatching { readNulPaths(inflight) }.getOrNull() ?: return@synchronized null
            if (paths.isEmpty()) {
                val emptyLock = acquireQueueLock(stateDir)
                if (emptyLock != null) {
                    try { inflight.delete() } finally { emptyLock.delete() }
                }
                return@synchronized null
            }

            val token = nextToken++
            activeToken = token
            Claim(inflight, paths, token)
        } ?: return 0

        submit(appContext, stateDir, claim.inflight, claim.paths, claim.token)
        return claim.paths.size
    }

    private fun submit(context: Context, stateDir: File, inflight: File, paths: List<String>, token: Long) {
        val submitted = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val submissionDone = AtomicBoolean(false)
        val submissionFailed = AtomicBoolean(false)

        fun maybeFinish() {
            if (!submissionDone.get()) return
            if (completed.get() < submitted.get()) return
            finishSubmission(context, stateDir, inflight, token, !submissionFailed.get())
        }

        for (batch in paths.chunked(BATCH_SIZE)) {
            submitted.addAndGet(batch.size)
            try {
                MediaScannerConnection.scanFile(
                    context,
                    batch.toTypedArray(),
                    null
                ) { _, _ ->
                    completed.incrementAndGet()
                    maybeFinish()
                }
            } catch (_: Throwable) {
                submitted.addAndGet(-batch.size)
                submissionFailed.set(true)
                break
            }
        }

        submissionDone.set(true)
        if (submitted.get() == 0) {
            finishSubmission(context, stateDir, inflight, token, false)
        } else {
            maybeFinish()
            handler.postDelayed(
                { finishSubmission(context, stateDir, inflight, token, false) },
                CALLBACK_TIMEOUT_MS
            )
        }
    }

    private fun finishSubmission(
        context: Context,
        stateDir: File,
        inflight: File,
        token: Long,
        success: Boolean
    ) {
        var acknowledged = false
        synchronized(monitor) {
            if (activeToken != token) return
            if (success) {
                val queueLock = acquireQueueLock(stateDir)
                if (queueLock != null) {
                    try {
                        acknowledged = !inflight.exists() || inflight.delete()
                    } finally {
                        queueLock.delete()
                    }
                }
            }
            activeToken = 0L
            retryAfterRealtime = if (success && acknowledged) 0L
            else SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
        }

        if (success && acknowledged) {
            // New shell/app entries may have arrived in pending while this batch was in flight.
            handler.post { flush(context, stateDir) }
        }
    }

    /** Caller must hold LOCK_NAME.  Requeue a stale in-flight claim after crash/failure. */
    private fun recoverInflightLocked(stateDir: File): Boolean {
        val inflight = File(stateDir, INFLIGHT_NAME)
        if (!inflight.exists()) return true
        if (inflight.length() == 0L) return inflight.delete() || !inflight.exists()
        val bytes = runCatching { inflight.readBytes() }.getOrNull() ?: return false
        if (!appendBytes(File(stateDir, PENDING_NAME), bytes)) return false
        return inflight.delete() || !inflight.exists()
    }

    /** Caller must hold LOCK_NAME. */
    private fun recoverSpoolsLocked(stateDir: File): Boolean {
        val now = System.currentTimeMillis()
        val spools = stateDir.listFiles { file ->
            if (!file.isFile || !file.name.endsWith(".nul")) return@listFiles false
            val normalSpool = file.name.startsWith(SPOOL_PREFIX)
            // Last-resort shell persistence: if both pending append and spool rename failed,
            // organizer-worker preserves its task-local media-scan-*.nul instead of deleting it.
            // Only recover an aged file so a still-running organizer cannot be consumed mid-write.
            val orphanTaskQueue = file.name.startsWith("media-scan-") &&
                now - file.lastModified() > STALE_TASK_QUEUE_MS
            normalSpool || orphanTaskQueue
        }?.sortedBy { it.name }.orEmpty()
        val pending = File(stateDir, PENDING_NAME)
        for (spool in spools) {
            val bytes = runCatching { spool.readBytes() }.getOrNull() ?: return false
            if (!appendBytes(pending, bytes)) return false
            if (!spool.delete() && spool.exists()) return false
        }
        return true
    }

    /** Caller must hold LOCK_NAME. */
    private fun claimPendingLocked(stateDir: File): File? {
        val pending = File(stateDir, PENDING_NAME)
        val inflight = File(stateDir, INFLIGHT_NAME)
        if (!pending.isFile || pending.length() == 0L) {
            if (pending.exists()) pending.delete()
            return null
        }
        if (inflight.exists()) return null
        return if (pending.renameTo(inflight)) inflight else null
    }

    private fun acquireQueueLock(stateDir: File): File? {
        stateDir.mkdirs()
        val lock = File(stateDir, LOCK_NAME)
        repeat(2) {
            if (lock.mkdir()) return lock
            val age = System.currentTimeMillis() - lock.lastModified()
            if (age > STALE_LOCK_MS) runCatching { lock.deleteRecursively() }
        }
        return null
    }

    private fun appendBytes(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        FileOutputStream(target, true).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
        true
    }.getOrDefault(false)

    private fun writeSpool(stateDir: File, bytes: ByteArray): Boolean {
        val name = "$SPOOL_PREFIX${android.os.Process.myPid()}.${System.nanoTime()}.nul"
        val target = File(stateDir, name)
        val temporary = File(stateDir, ".$name.tmp")
        return try {
            FileOutputStream(temporary).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            temporary.delete()
            false
        }
    }

    private fun readNulPaths(file: File): List<String> {
        val bytes = file.readBytes()
        val result = LinkedHashSet<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] != 0.toByte()) continue
            if (i > start) result += String(bytes, start, i - start, Charsets.UTF_8)
            start = i + 1
        }
        // Tolerate a final non-NUL segment so an interrupted legacy append is retryable.
        if (start < bytes.size) result += String(bytes, start, bytes.size - start, Charsets.UTF_8)
        return result.filter { it.isNotBlank() }
    }
}
