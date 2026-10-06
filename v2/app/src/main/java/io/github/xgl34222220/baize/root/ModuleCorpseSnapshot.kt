package io.github.xgl34222220.baize.root

import android.os.Process
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Packaged-module entry point. It consumes only the private scan-time manifest. */
internal object ModuleCorpseSnapshot {
    private val targetPattern = Regex("^/data/media/([0-9]+)/Android/(data|obb)/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)$")
    private fun scope(path: String): Pair<Int, String> {
        val match = requireNotNull(targetPattern.matchEntire(path)) { "卸载残留路径不在允许范围" }
        return match.groupValues[1].toInt() to match.groupValues[3]
    }
    private fun installed(user: Int): Set<String> {
        val process = ProcessBuilder("/system/bin/cmd", "package", "list", "packages", "--user", user.toString())
            .redirectErrorStream(true).start()
        val output = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText().take(4 * 1024 * 1024) } }
        try {
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "应用安装清单不可用" }
            val lines = output.get(2, TimeUnit.SECONDS).lineSequence().filter { it.isNotBlank() }.toList()
            check(lines.isNotEmpty() && lines.all { it.matches(Regex("package:[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) }) { "应用安装清单不完整" }
            val packages = lines.map { it.removePrefix("package:") }.toSet()
            check("android" in packages) { "无法确认该用户的应用安装状态" }
            return packages
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
    private fun protected(state: File, target: String, user: Int, pkg: String): Boolean {
        val rulesFile = File(state, "whitelist.conf")
        check(rulesFile.isFile) { "路径保护名单不可用" }
        val rules = JSONObject(WhitelistRepository(rulesFile, File(state, "whitelist.packages")).apkProtectionJson())
        val packages = rules.getJSONArray("packages")
        if ((0 until packages.length()).any { packages.getString(it) == pkg }) return true
        val identity = AndroidPathIdentity("/storage/emulated/$user")
        val path = identity.of(target)
        val paths = rules.getJSONArray("paths")
        return (0 until paths.length()).any { index ->
            val raw = paths.getString(index)
            check(!identity.unresolvedUserAlias(raw)) { "保护路径用户归属无法核对" }
            val prefix = identity.of(raw)
            prefix == "/" || path == prefix || path.startsWith("$prefix/") || prefix.startsWith("$path/")
        }
    }
    private fun atomic(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val temp = File.createTempFile(".corpse-snapshot-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { it.write(text.toByteArray()); it.fd.sync() }
            check(temp.renameTo(file)) { "无法发布逐文件快照" }
        } finally { temp.delete() }
    }
    @JvmStatic fun main(args: Array<String>) {
        if (Process.myUid() != 0) exitProcess(8)
        var status = 8
        try {
            require(args.size >= 4)
            val state = File(args[1]); check(state.canonicalPath == state.absolutePath && state.isDirectory)
            when (args[0]) {
                "capture" -> {
                    require(args.size == 4)
                    val targetsFile = File(args[2]); require(targetsFile.length() <= 8L * 1024 * 1024)
                    val targets = targetsFile.readLines().filter { it.isNotBlank() }.distinct()
                    require(targets.size <= 20_000)
                    val entries = JSONArray(); var files = 0L; var bytes = 0L; var count = 0
                    val deadline = System.nanoTime() + 300_000_000_000L
                    val inventories = hashMapOf<Int, Set<String>>()
                    for (target in targets) {
                        val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                        check(remainingMs > 0) { "逐文件扫描超时，未授权清理" }
                        val (user, pkg) = scope(target)
                        check(pkg !in inventories.getOrPut(user) { installed(user) }) { "应用已安装，请重新扫描" }
                        check(!protected(state, target, user, pkg)) { "目标命中当前保护名单" }
                        val tree = FrozenReviewTree.capture(File(target).toPath(), AtomicBoolean(), minOf(20_000, remainingMs), 100_000 - count)
                        check(tree.complete) { "逐文件扫描未完成，未授权清理：${tree.reason}" }
                        count += tree.count(); files += tree.files; bytes += tree.bytes
                        entries.put(JSONObject().put("path", target).put("tree", FrozenReviewTree.toJson(tree)))
                    }
                    atomic(File(args[3]), JSONObject().put("version", 1).put("createdAt", System.currentTimeMillis()).put("entries", entries).toString())
                    println("files=$files\nbytes=$bytes\ntargets=${targets.size}")
                    status = 0
                }
                "clean" -> {
                    require(args.size in 7..8)
                    val manifestFile = File(args[2]); check(manifestFile.length() <= 64L * 1024 * 1024)
                    val manifest = JSONObject(manifestFile.readText()); check(manifest.getInt("version") == 1)
                    check(System.currentTimeMillis() - manifest.getLong("createdAt") in 0L..30 * 60_000L) { "逐文件快照已过期" }
                    val target = args[3]; val (user, pkg) = scope(target)
                    check(pkg !in installed(user)) { "应用已重新安装，文件已保留" }
                    check(!protected(state, target, user, pkg)) { "目标命中当前保护名单" }
                    val entries = manifest.getJSONArray("entries")
                    val entry = (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getString("path") == target }
                    val tree = requireNotNull(FrozenReviewTree.fromJson(entry.getJSONObject("tree")))
                    check(tree.root == target)
                    val stop = File(args[5]); val cancelled = AtomicBoolean(stop.exists())
                    val output = File(args[4])
                    val deletedFile = if (args.size == 8) File(args[7]) else File(output.path + ".deleted.nul")
                    val result = FileOutputStream(deletedFile).use { stream ->
                        stream.channel.lock().use {
                            val opened = Os.fstat(stream.fd)
                            val located = Os.lstat(deletedFile.path)
                            check(OsConstants.S_ISREG(located.st_mode) && opened.st_dev == located.st_dev &&
                                opened.st_ino == located.st_ino) { "删除记录队列已变化，未开始清理" }
                            java.nio.file.Files.write(File(deletedFile.path + ".writer-ready").toPath(),
                                "cleanup-media-writer-v1\n".toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE)
                            FrozenReviewTree.delete(tree, true, args[6].toLong().coerceAtLeast(0), cancelled, 20_000,
                                { _, _ -> if (stop.exists()) cancelled.set(true); !cancelled.get() },
                                { path, _, _ -> stream.write(path.toByteArray()); stream.write(0); stream.flush() })
                                .also { stream.fd.sync() }
                        }
                    }
                    atomic(output, "files=${result.files}\nbytes=${result.bytes}\ndirectories=${result.directories}\nfailures=${result.failures}\ncomplete=${if(result.complete) 1 else 0}\nreason=${result.reason}\n")
                    status = if (cancelled.get()) 9 else if (result.complete) 0 else 8
                }
                else -> error("不支持的快照操作")
            }
        } catch (error: Exception) { System.err.println("逐文件操作未完成：${error.message.orEmpty()}") }
        exitProcess(status)
    }
}
