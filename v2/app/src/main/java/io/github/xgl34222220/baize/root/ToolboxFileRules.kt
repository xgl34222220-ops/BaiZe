package io.github.xgl34222220.baize.root

import java.io.File

/** Aurora-style source + glob&&glob + destination, limited to shared-storage files. */
internal object ToolboxFileRules {
    data class Rule(val source: String, val patterns: List<String>, val destination: String) {
        private val sourcePattern = glob(source)
        private val filePatterns = patterns.map(::glob)
        fun accepts(file: File): Boolean = matchesDirectory(file.parentFile?.path.orEmpty()) &&
            filePatterns.any { it.matches(file.name) }
        fun matchesDirectory(path: String): Boolean = generateSequence(File(path)) { it.parentFile }.take(40).any { sourcePattern.matches(it.path) }
        fun roots(): List<File> {
            var roots = listOf(File("/"))
            for (part in source.removePrefix("/").split('/')) {
                roots = roots.flatMap { parent ->
                    if ('*' in part || '?' in part) parent.listFiles()?.filter {
                        it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) && glob(part).matches(it.name)
                    }.orEmpty() else listOf(File(parent, part)).filter { it.isDirectory }
                }.filter { it.canonicalPath == it.absolutePath }.take(1000)
            }
            return roots
        }
    }
    fun normalizePath(raw: String): String = raw.trim().trimEnd('/').let {
        when {
            it.startsWith("/sdcard/") -> "/data/media/0/" + it.removePrefix("/sdcard/")
            it.startsWith("/storage/emulated/") -> "/data/media/" + it.removePrefix("/storage/emulated/")
            else -> it
        }
    }
    fun parse(raw: String): List<Rule> {
        require(raw.length <= 32_000) { "归类规则最多 32 KB" }
        val lines = raw.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        require(lines.size <= 100) { "最多 100 条文件规则" }
        return lines.mapIndexed { index, line ->
            val parts = line.split('+')
            require(parts.size in 2..3) { "第 ${index + 1} 行应为 来源+*.扩展名+目标" }
            val source = normalizePath(parts.first())
            val destination = normalizePath(parts.last())
            fun shared(path: String) = Regex("/data/media/[0-9]+/.+").matches(path) &&
                path.split('/').none { it in setOf(".", "..") } && path.none { it.code < 32 || it in "\\|" }
            require(shared(source) && shared(destination) && '*' !in destination && '?' !in destination) { "第 ${index + 1} 行仅支持公共存储子目录，目标不能含通配符" }
            require(source != destination && !destination.startsWith("$source/") && !source.startsWith("$destination/")) { "来源和目标不能互相包含" }
            require(!source.contains("/Android/obb") && !destination.contains("/Android/obb")) { "不能转移 OBB 应用资源" }
            val patterns = if (parts.size == 2) listOf("*") else parts[1].split("&&").map(String::trim)
            require(patterns.size <= 30 && patterns.all { it.isNotEmpty() && it.length <= 80 && it.none { c -> c in "/\\\u0000" } }) { "文件类型格式无效" }
            Rule(source, patterns, destination)
        }
    }
    fun glob(pattern: String): Regex = Regex(buildString {
        append('^')
        pattern.forEach { c -> append(when (c) { '*' -> "[^/]*"; '?' -> "[^/]"; else -> Regex.escape(c.toString()) }) }
        append('$')
    }, RegexOption.IGNORE_CASE)
}
