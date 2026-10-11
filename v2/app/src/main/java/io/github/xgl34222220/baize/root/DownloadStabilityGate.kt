package io.github.xgl34222220.baize.root

import java.io.File
import java.nio.file.Files

/**
 * 3.0.1：归类前判断文件是否可能仍在下载/写入。
 *
 * 很多下载器（网盘、部分浏览器、迅雷类）直接写最终文件名而不是 .part/.crdownload，
 * 归类若在写入中途把文件搬走，下载器继续写已不存在的路径，留下截断、无法播放的视频。
 * 与模块 organizer-worker.sh 同一套三道闸：
 *  1. 最近 [minAgeSeconds] 秒内修改过（含时钟偏差的未来时间）不动；
 *  2. 仍被任何进程打开（/proc 下任意 fd 指向它，含 FUSE 视图与下层 /data/media）不动；
 *  3. 移动前复核大小与修改时间（由 FileOrganizerEngine 的 dev:ino:size:mtime 指纹完成）。
 *
 * 打开文件集合每轮只建一次（扫描/执行各一次），逐文件只做集合查找，开销有上限。
 * 保守起见不区分读写模式：被播放器或 MediaProvider 打开的文件也会本轮跳过，下次再归类。
 */
class DownloadStabilityGate(
    val minAgeSeconds: Long,
    private val openPaths: Set<String>,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    enum class Verdict { STABLE, RECENT, OPEN }

    fun check(path: String, mtimeSeconds: Long): Verdict = when {
        isRecent(mtimeSeconds, nowSeconds(), minAgeSeconds) -> Verdict.RECENT
        path in openPaths -> Verdict.OPEN
        else -> Verdict.STABLE
    }

    fun reason(verdict: Verdict): String? = when (verdict) {
        Verdict.STABLE -> null
        Verdict.RECENT -> "文件最近 ${minAgeSeconds / 60} 分钟内仍在修改，可能正在下载，本次跳过"
        Verdict.OPEN -> "文件仍被其他应用打开（可能正在下载或播放），本次跳过"
    }

    val openCount: Int get() = openPaths.size

    companion object {
        const val DEFAULT_MIN_AGE_MINUTES = 10
        const val MIN_AGE_MINUTES_RANGE_FIRST = 1
        const val MIN_AGE_MINUTES_RANGE_LAST = 1_440
        private const val MAX_FD_ENTRIES = 200_000
        private const val SCAN_BUDGET_MS = 4_000L
        private val PID = Regex("^\\d+$")
        private val FUSE_VIEWS = listOf(
            Regex("^/storage/emulated/(\\d+)/(.+)$"),
            Regex("^/mnt/user/\\d+/emulated/(\\d+)/(.+)$"),
            Regex("^/mnt/pass_through/\\d+/emulated/(\\d+)/(.+)$"),
            Regex("^/data/media/(\\d+)/(.+)$")
        )

        fun clampMinAgeMinutes(raw: Int?): Int =
            (raw ?: DEFAULT_MIN_AGE_MINUTES).coerceIn(MIN_AGE_MINUTES_RANGE_FIRST, MIN_AGE_MINUTES_RANGE_LAST)

        fun isRecent(mtimeSeconds: Long, nowSeconds: Long, minAgeSeconds: Long): Boolean =
            nowSeconds - mtimeSeconds < minAgeSeconds

        /** 把 fd 链接目标折算回 [mediaRoot]/<user>/...；不在共享存储下的返回 null。 */
        fun normalizeOpenTarget(target: String, mediaRoot: String = "/data/media"): String? {
            val clean = target.removeSuffix(" (deleted)")
            for (pattern in FUSE_VIEWS) {
                val match = pattern.find(clean) ?: continue
                return "$mediaRoot/${match.groupValues[1]}/${match.groupValues[2]}"
            }
            return null
        }

        /** 一次遍历 /proc/<pid>/fd，返回共享存储下所有被打开文件的规范路径。 */
        fun scanOpenFiles(
            procRoot: File = File("/proc"),
            mediaRoot: String = "/data/media",
            budgetMs: Long = SCAN_BUDGET_MS,
            maxEntries: Int = MAX_FD_ENTRIES
        ): Set<String> {
            val started = System.nanoTime()
            val result = HashSet<String>()
            var visited = 0
            val pids = procRoot.listFiles { file -> PID.matches(file.name) } ?: return result
            for (pid in pids) {
                if ((System.nanoTime() - started) / 1_000_000L > budgetMs || visited >= maxEntries) break
                val fds = File(pid, "fd").listFiles() ?: continue
                for (fd in fds) {
                    if (++visited >= maxEntries) break
                    val target = runCatching { Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull() ?: continue
                    normalizeOpenTarget(target, mediaRoot)?.let(result::add)
                }
            }
            return result
        }

        fun capture(minAgeMinutes: Int, procRoot: File = File("/proc")): DownloadStabilityGate =
            DownloadStabilityGate(
                minAgeSeconds = clampMinAgeMinutes(minAgeMinutes) * 60L,
                openPaths = runCatching { scanOpenFiles(procRoot) }.getOrDefault(emptySet())
            )
    }
}
