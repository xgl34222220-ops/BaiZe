package io.github.xgl34222220.baize.root

import android.app.Application
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowParcelFileDescriptor
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/**
 * Exercises caller-owned descriptors and acknowledgement handling on JVM file descriptors.
 * A real App/Root Binder exchange and OEM SELinux policy still need device validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CallerOwnedJsonTransportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun largeUnicodeRequestAndResponseUseOnlyCallerOwnedFilesAndIntegerAcknowledgement() {
        val directory = folder.newFolder("caller-only")
        val paths = (0 until 1772).map { index ->
            "/storage/emulated/0/Android/data/com.example.$index/cache/" +
                "中文清理目录/".repeat(12) + "下载暂存 \"测试\" 文件-$index.tmp"
        }
        val selection = JSONObject()
        val details = JSONArray()
        paths.forEachIndexed { index, path ->
            selection.put(path, true)
            details.put(JSONObject().put("path", path).put("bytes", index + 1L))
        }
        val arguments = JSONArray().put("snapshot-1772").put(selection.toString())
        val expected = JSONObject().put("success", true).put("details", details).toString()
        assertTrue(arguments.toString().toByteArray(Charsets.UTF_8).size > 429480)
        assertTrue(expected.toByteArray(Charsets.UTF_8).size > 429480)
        var exchanges = 0
        var mutations = 0
        var requestHandle: FileDescriptor? = null
        var responseHandle: FileDescriptor? = null

        val actual = JsonFileTransport.callInto(directory, arguments) { request, response ->
            exchanges++
            requestHandle = request.fileDescriptor
            responseHandle = response.fileDescriptor
            assertTrue(request.statSize > 429480L)
            // Both files exist before Root executes; neither has a name left in the directory.
            assertEquals(0L, response.statSize)
            assertEmptyDirectory(directory)
            // No service directory and no service-created return descriptor are involved.
            JsonFileTransport.serveInto(request, response) { received ->
                mutations++
                assertEquals("snapshot-1772", received.getString(0))
                val receivedSelection = JSONObject(received.getString(1))
                assertEquals(paths.size, receivedSelection.length())
                paths.forEach { assertTrue(receivedSelection.getBoolean(it)) }
                expected
            }
        }

        assertEquals(expected, actual)
        val returnedDetails = JSONObject(actual).getJSONArray("details")
        paths.forEachIndexed { index, path ->
            assertEquals(path, returnedDetails.getJSONObject(index).getString("path"))
            assertEquals(index + 1L, returnedDetails.getJSONObject(index).getLong("bytes"))
        }
        assertEquals(1, exchanges)
        assertEquals(1, mutations)
        assertFalse(requireNotNull(requestHandle).valid())
        assertFalse(requireNotNull(responseHandle).valid())
        assertEmptyDirectory(directory)
    }

    @Test fun responseReaderStartsAtZeroAfterWriterAdvancesAndAllHandlesClose() {
        val directory = folder.newFolder("independent-offsets")
        val target = JsonFileTransport.createResponse(directory)
        val writerHandle = target.writer.fileDescriptor
        val readerHandle = target.reader.fileDescriptor
        val expected = "{\"success\":true,\"path\":\"中文下载/📦.apk\"}"
        target.use {
            assertEquals(0L, target.writer.statSize)
            assertEquals(0L, target.reader.statSize)
            assertEmptyDirectory(directory)
            ParcelFileDescriptor.AutoCloseOutputStream(target.writer).use { output ->
                val bytes = expected.toByteArray(Charsets.UTF_8)
                output.write(bytes)
                assertEquals(bytes.size.toLong(), output.channel.position())
            }
            assertEquals(expected, JsonFileTransport.read(target.reader))
        }
        assertFalse(writerHandle.valid())
        assertFalse(readerHandle.valid())
        assertEmptyDirectory(directory)
    }

    @Test fun malformedOrMissingRequestReturnsExplicitRejectionWithoutMutation() {
        val directory = folder.newFolder("rejected-requests")
        var mutations = 0
        listOf<String?>(null, "[", "{\"selected\":true}").forEach { text ->
            val request = text?.let { JsonFileTransport.write(directory, it) }
            JsonFileTransport.createResponse(directory).use { response ->
                val status = JsonFileTransport.serveInto(request, response.writer) {
                    mutations++
                    "{\"success\":true}"
                }
                assertEquals(JsonFileTransport.COMPLETE, status)
                val result = JSONObject(JsonFileTransport.read(response.reader))
                assertFalse(result.getBoolean("success"))
                assertTrue(result.getBoolean("requestRejected"))
                assertEquals("transport_request_rejected", result.getString("error"))
            }
            assertEmptyDirectory(directory)
        }
        assertEquals(0, mutations)
    }

    @Test fun invalidUtf8RequestIsRejectedWithoutChangingItsPathOrStartingMutation() {
        val directory = folder.newFolder("invalid-utf8")
        val input = folder.newFile("invalid-utf8.json").apply {
            writeBytes(byteArrayOf(0x5b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x5d))
        }
        var mutations = 0
        JsonFileTransport.createResponse(directory).use { response ->
            val status = JsonFileTransport.serveInto(
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY), response.writer
            ) {
                mutations++
                "{}"
            }
            assertEquals(JsonFileTransport.COMPLETE, status)
            assertTrue(JSONObject(JsonFileTransport.read(response.reader)).getBoolean("requestRejected"))
        }
        assertEquals(0, mutations)
        assertEmptyDirectory(directory)
    }

    @Test fun missingResponseIsRejectedBeforeMutation() {
        val directory = folder.newFolder("missing-response")
        var mutations = 0
        val status = JsonFileTransport.serveInto(
            JsonFileTransport.write(directory, "[]"), null
        ) {
            mutations++
            "{}"
        }
        assertEquals(JsonFileTransport.REQUEST_REJECTED, status)
        assertEquals(0, mutations)
        assertEmptyDirectory(directory)
    }

    @Test fun emptyReadOnlyResponseIsRejectedBeforeMutation() {
        val directory = folder.newFolder("read-only-response")
        var mutations = 0
        // An empty read-only file is the important case: truncate(0) alone may be a no-op.
        val response = JsonFileTransport.write(directory, "")
        val responseHandle = response.fileDescriptor
        val status = JsonFileTransport.serveInto(JsonFileTransport.write(directory, "[]"), response) {
            mutations++
            "{}"
        }
        assertEquals(JsonFileTransport.REQUEST_REJECTED, status)
        assertEquals(0, mutations)
        assertFalse(responseHandle.valid())
        assertEmptyDirectory(directory)
    }

    @Test fun nonEmptyResponseIsRejectedBeforeMutationAndDoesNotOverwriteExistingData() {
        val directory = folder.newFolder("nonempty-response")
        val input = folder.newFile("occupied-response.json").apply { writeText("preserve me") }
        var mutations = 0
        val status = JsonFileTransport.serveInto(
            JsonFileTransport.write(directory, "[]"),
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_WRITE)
        ) {
            mutations++
            "{}"
        }
        assertEquals(JsonFileTransport.REQUEST_REJECTED, status)
        assertEquals(0, mutations)
        assertEquals("preserve me", input.readText())
        assertEmptyDirectory(directory)
    }

    @Test
    @Config(shadows = [NonRegularDescriptorShadow::class])
    fun pipeResponseIsRejectedBeforeMutationOrWriting() {
        val directory = folder.newFolder("pipe-response")
        var mutations = 0
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[0].use {
            pipe[1].use { sink ->
                assertEquals(-1L, sink.statSize)
                val status = JsonFileTransport.serveInto(JsonFileTransport.write(directory, "[]"), sink) {
                    mutations++
                    "{}"
                }
                assertEquals(JsonFileTransport.REQUEST_REJECTED, status)
            }
        }
        assertEquals(0, mutations)
        assertEmptyDirectory(directory)
    }

    @Test fun lostAcknowledgementAfterMutationReportsUnknownOutcomeAndNeverRetries() {
        val directory = folder.newFolder("lost-acknowledgement")
        var exchanges = 0
        var mutations = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.callInto(directory, JSONArray().put("snapshot")) { request, response ->
                exchanges++
                assertEquals(JsonFileTransport.COMPLETE, JsonFileTransport.serveInto(request, response) {
                    mutations++
                    "{\"success\":true}"
                })
                // The result file is complete, but Binder never delivers its acknowledgement.
                throw IOException("acknowledgement disconnected")
            }
        }
        assertTrue(error.requestSubmitted)
        assertEquals("acknowledgement disconnected", error.cause?.message)
        assertEquals(1, exchanges)
        assertEquals(1, mutations)
        assertEmptyDirectory(directory)
    }

    @Test fun unknownStatusNeverReadsPartialResultEvenWhenSomeBytesWereWritten() {
        val directory = folder.newFolder("unknown-status")
        listOf(JsonFileTransport.OUTCOME_UNKNOWN, 71).forEach { status ->
            var exchanges = 0
            val error = assertThrows(RootJsonTransportException::class.java) {
                JsonFileTransport.callInto(directory, JSONArray()) { _, response ->
                    exchanges++
                    ParcelFileDescriptor.AutoCloseOutputStream(response).use {
                        // Deliberately invalid UTF-8: reading this would produce a different error.
                        it.write(byteArrayOf(0xc3.toByte()))
                    }
                    status
                }
            }
            assertTrue(error.requestSubmitted)
            assertEquals("服务执行结果未确认，请查看任务记录后重新扫描", error.cause?.message)
            assertEquals(1, exchanges)
            assertEmptyDirectory(directory)
        }
    }

    @Test fun failureDuringOperationIsUnknownAndDoesNotReplayTheOperation() {
        val directory = folder.newFolder("operation-failure")
        var exchanges = 0
        var mutations = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                exchanges++
                JsonFileTransport.serveInto(request, response) {
                    mutations++
                    throw IOException("operation interrupted after starting")
                }
            }
        }
        assertTrue(error.requestSubmitted)
        assertEquals(1, exchanges)
        assertEquals(1, mutations)
        assertEmptyDirectory(directory)
    }

    @Test fun rejectedChannelReportsUnsubmittedWithoutRetryingOrReadingAResponse() {
        val directory = folder.newFolder("channel-rejection")
        var exchanges = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.callInto(directory, JSONArray()) { _, _ ->
                exchanges++
                JsonFileTransport.REQUEST_REJECTED
            }
        }
        assertFalse(error.requestSubmitted)
        assertEquals(1, exchanges)
        assertEmptyDirectory(directory)
    }

    @Test fun localPreparationFailureNeverDispatchesAndReportsUnsubmitted() {
        val directoryIsAFile = folder.newFile("not-a-directory")
        var exchanges = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.callInto(directoryIsAFile, JSONArray()) { _, _ ->
                exchanges++
                JsonFileTransport.COMPLETE
            }
        }
        assertFalse(error.requestSubmitted)
        assertEquals(0, exchanges)
        assertEquals(0L, directoryIsAFile.length())
        assertEquals(listOf(directoryIsAFile), folder.root.listFiles()!!.toList())
    }

    private fun assertEmptyDirectory(directory: File) {
        assertTrue("Caller-owned transport files must be unlinked: $directory", directory.listFiles()!!.isEmpty())
    }

    /** Robolectric implements pipes with temporary files; supply the platform's non-regular stat. */
    @Implements(ParcelFileDescriptor::class)
    class NonRegularDescriptorShadow : ShadowParcelFileDescriptor() {
        @Implementation
        public override fun getStatSize(): Long = -1L
    }
}
