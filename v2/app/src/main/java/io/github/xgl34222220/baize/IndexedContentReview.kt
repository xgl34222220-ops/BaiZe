package io.github.xgl34222220.baize

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** A content snapshot made only for files the person has selected for final review. */
internal data class IndexedContentProof(val identity: ApkFileIdentity, val sha256: String, val createdAt: Long)
internal data class IndexedReviewBatch(val proofs: Map<String, IndexedContentProof>, val rejected: Map<String, String>)

internal object IndexedContentReview {
    const val BATCH_BUDGET_MS = 120_000L
    const val FILE_BUDGET_MS = 60_000L
    const val REVIEW_TTL_MS = 10 * 60_000L
    const val MAX_BATCH_BYTES = 32L * 1024 * 1024 * 1024

    fun prepare(items: List<IndexedApkCandidate>, guard: ApkDeletionGuard, cancelled: () -> Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> }): IndexedReviewBatch {
        val deadline = SystemClock.elapsedRealtime() + BATCH_BUDGET_MS
        val proofs = linkedMapOf<String, IndexedContentProof>()
        val rejected = linkedMapOf<String, String>()
        var bytes = 0L
        for ((index, item) in items.withIndex()) {
            if (cancelled()) throw CancellationException("已停止内容核对")
            progress(index + 1, items.size)
            try {
                val identity = checkNotNull(item.identity) { "扫描时未取得文件身份，请重新扫描" }
                check(identity.bytes == item.bytes) { "文件大小与索引记录不一致，请重新扫描" }
                check(item.bytes > 0 && item.bytes <= MAX_BATCH_BYTES - bytes) { "本次内容核对上限为 32 GiB，请分批选择" }
                check(SystemClock.elapsedRealtime() < deadline) { "本次核对已达 2 分钟，请分批选择" }
                bytes += item.bytes
                proofs[item.uri] = capture(identity, guard, cancelled,
                    minOf(deadline, SystemClock.elapsedRealtime() + FILE_BUDGET_MS))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { rejected[item.uri] = error.message ?: "文件内容无法核对，请查看读取诊断" }
        }
        return IndexedReviewBatch(proofs, rejected)
    }

    fun capture(identity: ApkFileIdentity, guard: ApkDeletionGuard,
        cancelled: () -> Boolean = { false }, deadline: Long = SystemClock.elapsedRealtime() + FILE_BUDGET_MS): IndexedContentProof {
        check(guard.capture(identity.canonicalPath) == identity) { "文件身份已变化，请重新扫描" }
        val digest = hash(identity, cancelled, deadline)
        check(guard.capture(identity.canonicalPath) == identity) { "核对期间文件已变化，请重新扫描" }
        return IndexedContentProof(identity, digest, SystemClock.elapsedRealtime())
    }

    fun matches(proof: IndexedContentProof?, identity: ApkFileIdentity, guard: ApkDeletionGuard,
        cancelled: () -> Boolean): Boolean {
        if (proof == null || proof.identity != identity || !proof.sha256.matches(Regex("[0-9a-f]{64}")) ||
            SystemClock.elapsedRealtime() - proof.createdAt !in 0..REVIEW_TTL_MS) return false
        return try {
            capture(identity, guard, cancelled).sha256 == proof.sha256
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { false }
    }

    private fun hash(identity: ApkFileIdentity, cancelled: () -> Boolean, deadline: Long): String {
        fun checkActive() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已停止内容核对")
            check(SystemClock.elapsedRealtime() <= deadline) { "内容核对超时，请减少所选文件后重试" }
        }
        checkActive()
        val fd = Os.open(identity.canonicalPath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        // Android's FileInputStream(FileDescriptor) borrows the descriptor. This method
        // remains its owner and closes it even when hashing is cancelled or throws.
        try { return FileInputStream(fd).use {
            check(sameFile(identity, Os.fstat(fd))) { "打开的文件身份不一致" }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                checkActive()
                val count = it.read(buffer)
                if (count < 0) break
                bytes += count
                check(bytes <= identity.bytes) { "核对期间文件大小已变化" }
                digest.update(buffer, 0, count)
            }
            checkActive()
            check(bytes == identity.bytes && sameFile(identity, Os.fstat(fd))) { "核对期间文件已变化" }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        } } finally { if (fd.valid()) Os.close(fd) }
    }

    private fun sameFile(identity: ApkFileIdentity, stat: StructStat): Boolean =
        OsConstants.S_ISREG(stat.st_mode) && identity.device == stat.st_dev && identity.inode == stat.st_ino &&
            identity.bytes == stat.st_size && identity.modifiedSeconds == stat.st_mtime && identity.changedSeconds == stat.st_ctime &&
            identity.modifiedNanos == (if (Build.VERSION.SDK_INT >= 27) stat.st_mtim.tv_nsec else -1L) &&
            identity.changedNanos == (if (Build.VERSION.SDK_INT >= 27) stat.st_ctim.tv_nsec else -1L)
}
