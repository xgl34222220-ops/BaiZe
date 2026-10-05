package io.github.xgl34222220.baize

import android.content.Context
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser

/** SharedPreferences silently maps invalid XML to empty. Protection must verify the actual file. */
internal object CheckedLegacyPreferences {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_VALUES = 100_000
    private val observedFiles = ConcurrentHashMap.newKeySet<String>()
    private class PendingWrite(message: String) : IllegalStateException(message)

    internal fun isPendingWrite(failure: Exception): Boolean = failure is PendingWrite

    fun read(context: Context, name: String): Map<String, *> {
        if (name == "baize_v2" || name == "legacy-protection-recovery-v1")
            LegacyPreferencesAccess.requireHealthy(context)
        // apply() may still be queued after the UI saves unrelated plan/theme metadata. Wait only
        // briefly for a stable disk snapshot; never write/flush corrupt or unverified preferences.
        for (attempt in 0..5) {
            try { return readOnce(context, name) }
            catch (pending: PendingWrite) {
                if (attempt == 5) throw pending
                Thread.sleep(50L)
            }
        }
        error("无法核对旧版保护记录")
    }

    private fun readOnce(context: Context, name: String): Map<String, *> {
        val file = File(context.dataDir, "shared_prefs/$name.xml")
        // Inspect before opening SharedPreferences: its loader may restore/delete a .bak file.
        val disk = readDisk(file)
        val memory = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        if (disk != memory || readDisk(file) != disk) {
            throw PendingWrite("旧版保护正在写入或磁盘记录不一致，已暂停清理，请稍后重试。")
        }
        return memory
    }

    internal fun readDisk(file: File): Map<String, Any?> {
        try {
            if (attributes(File(file.path + ".bak")) != null || attributes(File(file.path + ".tmp")) != null) {
                throw PendingWrite("旧版保护记录尚未完成写入，已暂停清理。")
            }
            val stat = attributes(file)
            if (stat == null) {
                check(file.absolutePath !in observedFiles) { "旧版保护记录文件丢失，已暂停清理。" }
                var parent = file.parentFile
                while (parent != null) {
                    val parentStat = attributes(parent)
                    if (parentStat != null) {
                        check(parentStat.isDirectory && parent.canRead() && parent.canExecute()) {
                            "旧版保护记录目录不可读，已暂停清理。"
                        }
                        break
                    }
                    parent = parent.parentFile
                }
                return emptyMap()
            }
            observedFiles += file.absolutePath
            check(stat.isRegularFile && !stat.isSymbolicLink && file.canRead() && stat.size() in 1..MAX_BYTES.toLong()) {
                "旧版保护记录文件不可读，已暂停清理。"
            }
            val bytes = file.inputStream().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_BYTES) { "旧版保护记录过大，已暂停清理。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return parse(bytes)
        } catch (pending: PendingWrite) {
            throw pending
        } catch (failure: Exception) {
            throw IllegalStateException("旧版保护记录不可核对，已暂停清理。${failure.message.orEmpty().take(120)}", failure)
        }
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun parse(bytes: ByteArray): Map<String, Any?> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var values = 0
        fun nextToken(): Int {
            val event = parser.nextToken()
            check(event != XmlPullParser.DOCDECL) { "保护记录不能包含外部文档声明" }
            return event
        }
        fun nextTag(): Int {
            while (true) {
                val event = nextToken()
                if (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG || event == XmlPullParser.END_DOCUMENT) return event
                check(event == XmlPullParser.COMMENT || event == XmlPullParser.PROCESSING_INSTRUCTION ||
                    ((event == XmlPullParser.TEXT || event == XmlPullParser.IGNORABLE_WHITESPACE) && parser.text.isNullOrBlank())) {
                    "保护记录包含无效 XML 内容"
                }
            }
        }
        fun textElement(): String {
            val name = parser.name
            val result = StringBuilder()
            while (true) {
                when (nextToken()) {
                    XmlPullParser.END_TAG -> { check(parser.name == name); return result.toString() }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> result.append(requireNotNull(parser.text))
                    XmlPullParser.COMMENT -> Unit
                    else -> error("保护记录文本格式无效")
                }
            }
        }
        check(nextTag() == XmlPullParser.START_TAG && parser.name == "map" && parser.attributeCount == 0) { "保护记录缺少 map 根节点" }
        val result = linkedMapOf<String, Any?>()
        while (nextTag() != XmlPullParser.END_TAG) {
            check(parser.eventType == XmlPullParser.START_TAG && ++values <= MAX_VALUES) { "保护记录未完整结束或项目过多" }
            val type = parser.name
            val name = requireNotNull(parser.getAttributeValue(null, "name")) { "保护记录缺少键名" }
            check(name !in result) { "保护记录包含重复键" }
            val attributes = if (type in setOf("int", "long", "float", "boolean")) setOf("name", "value") else setOf("name")
            check(parser.attributeCount == attributes.size && (0 until parser.attributeCount).all { parser.getAttributeName(it) in attributes }) {
                "保护记录属性格式无效"
            }
            val value: Any? = when (type) {
                "string" -> textElement()
                "set" -> {
                    val set = linkedSetOf<String>()
                    while (nextTag() != XmlPullParser.END_TAG) {
                        check(parser.eventType == XmlPullParser.START_TAG && parser.name == "string" && parser.attributeCount == 0 && ++values <= MAX_VALUES) {
                            "保护记录集合格式无效"
                        }
                        set += textElement()
                    }
                    check(parser.name == "set")
                    set
                }
                "int", "long", "float", "boolean", "null" -> {
                    val raw = parser.getAttributeValue(null, "value")
                    val scalar = when (type) {
                        "int" -> requireNotNull(raw).toInt()
                        "long" -> requireNotNull(raw).toLong()
                        "float" -> requireNotNull(raw).toFloat()
                        "boolean" -> { check(raw == "true" || raw == "false"); raw == "true" }
                        else -> null
                    }
                    check(nextTag() == XmlPullParser.END_TAG && parser.name == type) { "保护记录值格式无效" }
                    scalar
                }
                else -> error("保护记录包含无法核对的值类型")
            }
            result[name] = value
        }
        check(parser.name == "map" && nextTag() == XmlPullParser.END_DOCUMENT) { "保护记录未完整结束" }
        return result
    }
}
