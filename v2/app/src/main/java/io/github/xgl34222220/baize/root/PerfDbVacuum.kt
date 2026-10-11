package io.github.xgl34222220.baize.root

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.github.xgl34222220.baize.PerfToolsPolicy
import java.io.File
import java.io.FileInputStream

/**
 * 性能工具·数据库优化的 Root 引擎（3.0.1）。
 *
 * 不依赖 sqlite3 二进制（HyperOS 等量产系统没有）：以 root 身份在 app_process 里直接用
 * Android 的 [SQLiteDatabase] 完成 PRAGMA integrity_check → REINDEX → VACUUM，然后恢复属主、
 * 权限与 SELinux 标签。执行方式与 App 的 libsu RootService 相同（同一个 APK、root app_process），
 * 但由模块 perf-tools.sh 在维护窗口直接拉起，App 不在运行时也能完成。
 *
 * 安全闸与模块 perf_db_skip_reason / [PerfToolsPolicy.dbSkipReason] 一致，并在打开前再查一遍：
 * 只接受 /data/data/<包>/databases/ 或 /data/user/<N>/<包>/databases/ 下的普通文件；非符号链接；
 * 头部为 "SQLite format 3"；无使用中的 -wal、无热 -journal；4 KB–256 MB；不在黑名单；名字不像加密库。
 * 应用是否在运行由调用方（模块）在交给引擎前最后确认。
 */
object PerfDbVacuum {
    private val DB_PATH = Regex("^/data/(?:data|user/\\d+)/([A-Za-z][A-Za-z0-9._]{0,199})/databases/([^/]+)$")
    private const val HEADER_BYTES = 100

    /** 输出给模块解析的一行：ok <前> <后> <路径> / skip <原因> <路径> / fail <原因> <路径>。 */
    fun line(kind: String, a: String, b: String, path: String? = null): String =
        listOfNotNull(kind, a.replace(' ', '_'), b, path).joinToString(" ")

    fun packageOf(path: String): String? = DB_PATH.find(path)?.groupValues?.get(1)

    /** SQLite 头部第 18/19 字节为 2 表示 WAL；其余按回滚日志处理（DELETE 不会留下 -journal）。 */
    fun journalModeOf(header: ByteArray): String =
        if (header.size >= 20 && header[18].toInt() == 2 && header[19].toInt() == 2) "WAL" else "DELETE"

    fun headerText(header: ByteArray): String =
        if (header.size >= 15) String(header, 0, 15, Charsets.US_ASCII) else ""

    /** 返回 null 表示可以优化；否则是跳过原因（与模块同名）。 */
    fun preflight(path: String, sizeBytes: Long, walBytes: Long, journalExists: Boolean, header: ByteArray,
                  userBlacklist: Collection<String> = emptyList()): String? {
        val pkg = packageOf(path) ?: return "path-not-allowed"
        return PerfToolsPolicy.dbSkipReason(pkg, File(path).name, listOf(pkg), userBlacklist,
            sizeBytes, walBytes, journalExists, headerText(header))
    }

    private fun readHeader(file: File): ByteArray = FileInputStream(file).use { input ->
        val buffer = ByteArray(HEADER_BYTES)
        var read = 0
        while (read < HEADER_BYTES) {
            val n = input.read(buffer, read, HEADER_BYTES - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    }

    private fun userBlacklist(): List<String> = runCatching {
        PerfToolsPolicy.normalizeList(File(RootPaths.STATE_DIR, PerfToolsStore.DB_BLACKLIST).readText())
    }.getOrDefault(emptyList())

    /** 处理一个数据库，返回给模块的一行结果。 */
    fun optimize(path: String, blacklist: Collection<String> = userBlacklist()): String {
        val file = File(path)
        val stat = runCatching { Os.lstat(path) }.getOrNull() ?: return line("skip", "not-regular-file", path)
        if (!OsConstants.S_ISREG(stat.st_mode)) return line("skip", "not-regular-file", path)
        val canonical = runCatching { file.canonicalPath }.getOrDefault("")
        if (packageOf(canonical) == null) return line("skip", "path-not-allowed", path)
        val header = runCatching { readHeader(file) }.getOrNull() ?: return line("skip", "unreadable", path)
        val wal = File("$path-wal")
        preflight(path, stat.st_size, if (wal.isFile) wal.length() else 0L, File("$path-journal").exists(), header, blacklist)
            ?.let { return line("skip", it, path) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return line("skip", "needs-android-9", path)

        val label = runCatching { Os.getxattr(path, SELINUX_XATTR) }.getOrNull()
        val before = stat.st_size
        val journalMode = journalModeOf(header)
        var flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        if (journalMode == "WAL") flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(flags)
            // 显式指定日志模式：避免系统默认的兼容 WAL 把回滚日志库改成 WAL（或反之）。
            .setJournalMode(journalMode)
            .setSynchronousMode("FULL")
            .build()
        val outcome = try {
            val db = SQLiteDatabase.openDatabase(file, params)
            try {
                val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else ""
                }
                if (integrity != "ok") {
                    line("skip", "integrity", path)
                } else {
                    db.execSQL("REINDEX")
                    db.execSQL("VACUUM")
                    null
                }
            } finally {
                db.close()
            }
        } catch (error: Throwable) {
            // 自定义排序规则（如 LOCALIZED）缺失、只读、损坏等：VACUUM 是事务，失败时原文件不变。
            line("fail", (error.javaClass.simpleName + ":" + (error.message ?: "")).take(80), path)
        } finally {
            restoreMetadata(path, stat.st_uid, stat.st_gid, stat.st_mode and 0xFFF, label)
        }
        if (outcome != null) return outcome
        val after = runCatching { Os.lstat(path).st_size }.getOrDefault(before)
        return line("ok", before.toString(), after.toString(), path)
    }

    /** 恢复主库及可能新建的 -journal / -wal / -shm 的属主、权限与 SELinux 标签（新文件由 root 创建）。 */
    private fun restoreMetadata(path: String, uid: Int, gid: Int, mode: Int, label: ByteArray?) {
        for (candidate in listOf(path, "$path-journal", "$path-wal", "$path-shm")) {
            val st = runCatching { Os.lstat(candidate) }.getOrNull() ?: continue
            if (!OsConstants.S_ISREG(st.st_mode)) continue
            runCatching { Os.chown(candidate, uid, gid) }
            runCatching { Os.chmod(candidate, mode) }
            if (label != null) runCatching { Os.setxattr(candidate, SELINUX_XATTR, label, 0) }
        }
    }

    private const val SELINUX_XATTR = "security.selinux"
}

/** 模块入口：CLASSPATH=<baize.apk> app_process /system/bin io.github.xgl34222220.baize.root.PerfDbVacuumMain <db>... */
object PerfDbVacuumMain {
    @JvmStatic
    fun main(args: Array<String>) {
        // 单个数据库卡住（I/O 异常）时整体退出；模块把未报告的数据库记为失败。
        val watchdog = Thread {
            if (runCatching { Thread.sleep(WATCHDOG_MS) }.isSuccess) Runtime.getRuntime().halt(124)
        }
        watchdog.isDaemon = true
        watchdog.start()
        val blacklist = runCatching {
            PerfToolsPolicy.normalizeList(File(RootPaths.STATE_DIR, PerfToolsStore.DB_BLACKLIST).readText())
        }.getOrDefault(emptyList())
        for (path in args.take(MAX_PER_RUN)) {
            val result = runCatching { PerfDbVacuum.optimize(path, blacklist) }
                .getOrElse { PerfDbVacuum.line("fail", it.javaClass.simpleName, path) }
            println(result)
            System.out.flush()
        }
        watchdog.interrupt()
    }

    private const val WATCHDOG_MS = 840_000L
    private const val MAX_PER_RUN = 300
}
