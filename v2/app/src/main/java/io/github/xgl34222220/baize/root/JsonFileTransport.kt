package io.github.xgl34222220.baize.root

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * JSON travels through private, unlinked, read-only files. Binder carries only a descriptor, so
 * candidate paths and per-item results cannot exhaust its shared transaction buffer. Files are
 * fully written before dispatch: malformed or oversized requests never start a deletion task.
 * There is deliberately no fallback/retry of a mutating Binder call.
 */
internal object JsonFileTransport {
    private const val MAX_BYTES = 32L * 1024L * 1024L

    fun call(
        directory: File,
        arguments: JSONArray,
        exchange: (ParcelFileDescriptor) -> ParcelFileDescriptor?
    ): String {
        val request = try {
            write(directory, arguments.toString())
        } catch (error: Exception) {
            throw RootJsonTransportException("服务请求尚未提交：${error.message.orEmpty()}", false, error)
        }
        try {
            request.use { descriptor ->
                val response = exchange(descriptor) ?: throw IOException("服务未返回结果文件")
                return read(response)
            }
        } catch (error: Exception) {
            // A Binder failure cannot tell us whether execution began or whether only its reply
            // was lost. The UI must preserve the review, and require a rescan before another clean.
            throw RootJsonTransportException("服务结果未确认：${error.message.orEmpty()}", true, error)
        }
    }

    fun serve(
        directory: File,
        request: ParcelFileDescriptor?,
        operation: (JSONArray) -> String
    ): ParcelFileDescriptor {
        val arguments = try {
            requireNotNull(request) { "请求文件缺失" }
            JSONArray(read(request))
        } catch (error: Exception) {
            return write(directory, JSONObject()
                .put("success", false).put("error", "transport_request_rejected")
                .put("requestRejected", true)
                .put("message", "请求未执行：${error.message.orEmpty()}").toString())
        }
        // The original service entry retains task locking, audit, snapshot identity and all
        // whitelist/path validation. Transport neither interprets nor widens the selection.
        val result = operation(arguments)
        return write(directory, result)
    }

    internal fun write(directory: File, text: String): ParcelFileDescriptor {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建服务传输目录")
        val file = File.createTempFile("baize-ipc-", ".json", directory)
        var descriptor: ParcelFileDescriptor? = null
        try {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.outputStream().use { output ->
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                // No named file remains even if the process dies while encoding a large reply.
                if (!file.delete()) throw IOException("无法移除服务传输临时文件")
                var bytesWritten = 0L
                val bounded = object : OutputStream() {
                    override fun write(value: Int) {
                        if (bytesWritten >= MAX_BYTES) throw IOException("服务数据超出单次读取上限")
                        output.write(value)
                        bytesWritten++
                    }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        if (bytesWritten + length > MAX_BYTES) throw IOException("服务数据超出单次读取上限")
                        output.write(buffer, offset, length)
                        bytesWritten += length
                    }
                }
                bounded.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }
            return requireNotNull(descriptor)
        } catch (error: Exception) {
            descriptor?.close()
            throw error
        } finally {
            file.delete()
        }
    }

    internal fun read(descriptor: ParcelFileDescriptor): String = descriptor.use {
        // Accept regular files only. A supplied pipe must not block a Root Binder worker forever.
        val size = descriptor.statSize
        if (size < 0L || size > MAX_BYTES) throw IOException("服务数据文件无效或过大")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            readBounded(input, MAX_BYTES)
        }
    }

    internal fun readBounded(input: InputStream, limit: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("服务数据超出单次读取上限")
            output.write(buffer, 0, count)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
}

internal class RootJsonTransportException(
    message: String,
    val requestSubmitted: Boolean,
    cause: Throwable
) : IOException(message, cause)
