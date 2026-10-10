package io.github.xgl34222220.baize

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** 根目录整理的文件操作：只作用于存储根目录的直接子项，不跟随链接，可在 JVM 上测试。 */
internal object RootTidyFiles {
    private fun attributes(file: File): BasicFileAttributes? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    private fun child(root: File, name: String): File? {
        if (!RootDirectoryOrganizer.validName(name)) return null
        val file = File(root, name)
        return file.takeIf { it.parentFile?.absolutePath == root.absolutePath }
    }

    /** 列出第一层；每个文件夹最多统计 [fileCap] 个文件，超过即标记不完整。 */
    fun scan(root: File, cancelled: () -> Boolean = { false }, fileCap: Int = RootDirectoryOrganizer.MAX_FILES_PER_FOLDER): List<RootEntry> {
        val names = root.list()?.sorted().orEmpty().take(RootDirectoryOrganizer.MAX_ENTRIES)
        return names.mapNotNull { name ->
            if (cancelled()) throw java.util.concurrent.CancellationException()
            val file = File(root, name)
            val attrs = attributes(file) ?: return@mapNotNull null
            val modified = attrs.lastModifiedTime().toMillis() / 1000
            when {
                attrs.isSymbolicLink -> RootEntry(name, directory = false, empty = false, modifiedSeconds = modified, link = true)
                attrs.isDirectory -> {
                    if (RootDirectoryOrganizer.standard(name) || name.equals(OrdinaryFileTrash.DIRECTORY, true)) {
                        // 标准目录与回收站不统计，避免遍历大量相册文件。
                        RootEntry(name, directory = true, empty = false, modifiedSeconds = modified)
                    } else {
                        val walk = walk(file, fileCap, cancelled)
                        RootEntry(name, true, file.list()?.isEmpty() == true, walk.files.size, walk.bytes, modified,
                            limited = walk.limited, link = walk.links > 0)
                    }
                }
                else -> RootEntry(name, directory = false, empty = attrs.size() == 0L, files = 1, bytes = attrs.size(), modifiedSeconds = modified)
            }
        }
    }

    data class Walk(val files: List<File>, val directories: List<File>, val bytes: Long, val limited: Boolean, val links: Int)

    /** 深度优先遍历；遇到链接只计数不进入。目录按“子先父后”排列，便于逐层删除空目录。 */
    fun walk(directory: File, fileCap: Int, cancelled: () -> Boolean = { false }): Walk {
        val files = ArrayList<File>(); val directories = ArrayList<File>()
        var bytes = 0L; var limited = false; var links = 0
        fun visit(dir: File, depth: Int) {
            if (limited) return
            if (depth > 32) { limited = true; return }
            for (name in dir.list().orEmpty()) {
                if (cancelled()) throw java.util.concurrent.CancellationException()
                val file = File(dir, name)
                val attrs = attributes(file) ?: continue
                when {
                    attrs.isSymbolicLink -> links++
                    attrs.isDirectory -> visit(file, depth + 1)
                    attrs.isRegularFile -> {
                        if (files.size >= fileCap) { limited = true; return }
                        files += file; bytes += attrs.size()
                    }
                    else -> links++
                }
            }
            directories += dir
        }
        visit(directory, 0)
        return Walk(files, directories, bytes, limited, links)
    }

    /** 删除空文件夹（rmdir 语义，非空时失败，不会删除任何文件）。 */
    fun removeEmptyDirectory(root: File, name: String): Boolean {
        val dir = child(root, name) ?: return false
        val attrs = attributes(dir) ?: return false
        if (!attrs.isDirectory || attrs.isSymbolicLink) return false
        return runCatching { Files.delete(dir.toPath()); true }.getOrDefault(false)
    }

    /** 自下而上删除已清空的子目录；任一目录仍有内容就停在那里。 */
    fun pruneEmptyDirectories(directories: List<File>): Int = directories.count { dir ->
        val attrs = attributes(dir)
        attrs != null && attrs.isDirectory && !attrs.isSymbolicLink && runCatching { Files.delete(dir.toPath()); true }.getOrDefault(false)
    }

    enum class PlaceholderResult(val message: String) {
        CREATED("已创建占位文件，应用无法再建同名文件夹"), EXISTS("占位文件已存在"),
        NOT_EMPTY("同名文件夹仍有内容，未创建占位"), INVALID("名称不允许设置禁止重建"), FAILED("占位文件创建失败")
    }

    /** 同名空文件夹先移除，再独占创建零字节文件；已有内容的目录永远不动。 */
    fun createPlaceholder(root: File, name: String): PlaceholderResult {
        if (!RootDirectoryOrganizer.ruleName(name)) return PlaceholderResult.INVALID
        val target = child(root, name) ?: return PlaceholderResult.INVALID
        val attrs = attributes(target)
        when {
            attrs == null -> Unit
            attrs.isSymbolicLink -> return PlaceholderResult.INVALID
            attrs.isRegularFile && attrs.size() == 0L -> return PlaceholderResult.EXISTS
            attrs.isDirectory -> if (!removeEmptyDirectory(root, name)) return PlaceholderResult.NOT_EMPTY
            else -> return PlaceholderResult.NOT_EMPTY
        }
        return runCatching { Files.createFile(target.toPath()); PlaceholderResult.CREATED }.getOrDefault(PlaceholderResult.FAILED)
    }

    /** 撤销禁止重建：只删除零字节普通文件，不会删除有内容的同名文件。 */
    fun removePlaceholder(root: File, name: String): Boolean {
        val target = child(root, name) ?: return false
        val attrs = attributes(target) ?: return true
        if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != 0L) return false
        return runCatching { Files.delete(target.toPath()); true }.getOrDefault(false)
    }
}
