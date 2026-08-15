package io.github.xgl34222220.baize.root

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

internal object RootPaths {
    const val MODULE_DIR = "/data/adb/modules/baize_v2"
    const val STATE_DIR = "/data/adb/baize-v2"
    const val CONFIG_FILE = "$STATE_DIR/config.conf"
    const val WHITELIST_FILE = "$STATE_DIR/whitelist.conf"
    const val WHITELIST_PACKAGES_FILE = "$STATE_DIR/whitelist.packages"
    const val RISK_OVERRIDES_FILE = "$STATE_DIR/risk-overrides.conf"

    /**
     * 按设备 ABI 优先级查找原生引擎。
     *
     * 此前这里硬编码 bin/arm64-v8a/baize_engine，armeabi-v7a 设备即使包里
     * 带了对应引擎也会被报成"不可用"。
     */
    fun nativeEngine(name: String, moduleDir: File = File(MODULE_DIR)): File? =
        android.os.Build.SUPPORTED_ABIS
            .asSequence()
            .map { abi -> File(moduleDir, "bin/$abi/$name") }
            .firstOrNull { it.canExecute() }
}

internal object RootValidation {
    val packageName = Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$""")
}

internal object RootFileStore {
    fun readEnv(file: File): JSONObject {
        val result = JSONObject()
        if (!file.isFile) return result
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#") || !line.contains('=')) return@forEachLine
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                result.put(key, value.toLongOrNull() ?: value)
            }
        }
        return result
    }

    fun writeAtomic(file: File, text: String, worldReadable: Boolean = false) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp.${Process.myPid()}")
        temporary.writeText(text)
        replaceFile(temporary, file, worldReadable)
    }

    fun replaceFile(temporary: File, target: File, worldReadable: Boolean = false) {
        temporary.setReadable(true, !worldReadable)
        temporary.setWritable(true, true)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target.setReadable(true, !worldReadable)
        target.setWritable(true, true)
    }

    fun tailText(file: File, maxChars: Int): String = runCatching {
        if (!file.isFile || maxChars <= 0) return@runCatching ""
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val byteLimit = (maxChars.toLong() * 4L).coerceAtMost(length)
            input.seek((length - byteLimit).coerceAtLeast(0L))
            val bytes = ByteArray(byteLimit.toInt())
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }
    }.getOrDefault("")
}
