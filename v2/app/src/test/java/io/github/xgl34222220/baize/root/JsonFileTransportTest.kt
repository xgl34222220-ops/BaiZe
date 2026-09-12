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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException

/** These exercise real file-descriptor payloads, not a device's shared Binder buffer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class JsonFileTransportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun all1772SelectedPathsAndLargeCleanupDetailsSurviveTheDescriptorRoundTrip() {
        val clientDirectory = folder.newFolder("client")
        val serviceDirectory = folder.newFolder("service")
        val paths = (0 until 1772).map { index ->
            "/storage/emulated/0/Android/data/com.example.$index/cache/" +
                "中文清理目录/".repeat(12) + "下载暂存 \"测试\" 文件-$index.tmp"
        }
        val selection = JSONObject()
        val details = JSONArray()
        paths.forEachIndexed { index, path ->
            selection.put(path, true)
            details.put(JSONObject().put("id", "candidate-$index").put("path", path)
                .put("status", "cleaned").put("bytes", index + 1L))
        }
        val options = JSONObject().put("allowHighRisk", false).toString()
        val arguments = JSONArray().put("snapshot-1772").put(selection.toString()).put(options)
        val expected = JSONObject().put("success", true).put("cleanedCandidates", 1772)
            .put("details", details).toString()
        // Reproduce the scale of the reported 429480-byte transaction in both directions.
        assertTrue(arguments.toString().toByteArray(Charsets.UTF_8).size > 429480)
        assertTrue(expected.toByteArray(Charsets.UTF_8).size > 429480)
        var exchanges = 0
        var mutations = 0

        val actual = JsonFileTransport.call(clientDirectory, arguments) { request ->
            exchanges++
            assertEmptyDirectory(clientDirectory)
            JsonFileTransport.serve(serviceDirectory, request) { received ->
                mutations++
                assertEquals("snapshot-1772", received.getString(0))
                assertEquals(options, received.getString(2))
                val receivedSelection = JSONObject(received.getString(1))
                assertEquals(paths.size, receivedSelection.length())
                paths.forEach { assertTrue(receivedSelection.getBoolean(it)) }
                expected
            }.also { assertEmptyDirectory(serviceDirectory) }
        }

        assertEquals(1, exchanges)
        assertEquals(1, mutations)
        assertEquals(expected, actual)
        val returnedDetails = JSONObject(actual).getJSONArray("details")
        assertEquals(paths.size, returnedDetails.length())
        paths.forEachIndexed { index, path ->
            assertEquals(path, returnedDetails.getJSONObject(index).getString("path"))
            assertEquals(index + 1L, returnedDetails.getJSONObject(index).getLong("bytes"))
        }
        assertEmptyDirectory(clientDirectory)
        assertEmptyDirectory(serviceDirectory)
    }

    @Test fun requestIsUnlinkedBeforeItIsConsumedAndStillContainsExactUnicode() {
        val directory = folder.newFolder("unlinked")
        val text = "[\"中文下载/保留 空格/📦.apk\"]".repeat(2000)
        val descriptor = JsonFileTransport.write(directory, text)
        assertEmptyDirectory(directory)
        assertEquals(text, JsonFileTransport.read(descriptor))
        assertEmptyDirectory(directory)
    }

    @Test fun malformedOrMissingRequestsAreRejectedBeforeAnyMutation() {
        val directory = folder.newFolder("rejected")
        var mutations = 0
        listOf<String?>(null, "[", "{\"selected\":true}").forEach { malformed ->
            val request = malformed?.let { JsonFileTransport.write(directory, it) }
            val response = JsonFileTransport.serve(directory, request) {
                mutations++
                "{\"success\":true}"
            }
            val result = JSONObject(JsonFileTransport.read(response))
            assertFalse(result.getBoolean("success"))
            assertTrue(result.getBoolean("requestRejected"))
            assertEquals("transport_request_rejected", result.getString("error"))
            assertEmptyDirectory(directory)
        }
        assertEquals(0, mutations)
    }

    @Test fun invalidUtf8RequestCannotReachMutation() {
        val directory = folder.newFolder("utf8-response")
        // An invalid UTF-8 sequence inside an otherwise valid JSON string must not be repaired
        // into a different filename and then used for cleanup.
        val input = folder.newFile("invalid-utf8.json").apply {
            writeBytes(byteArrayOf(0x5b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x5d))
        }
        var mutations = 0
        val result = JSONObject(JsonFileTransport.read(JsonFileTransport.serve(
            directory, ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        ) {
            mutations++
            "{}"
        }))
        assertEquals(0, mutations)
        assertTrue(result.getBoolean("requestRejected"))
        assertEmptyDirectory(directory)
    }

    @Test fun boundedReadCountsUtf8BytesAndNeverReplacesTruncatedCharacters() {
        val bytes = "清理📦".toByteArray(Charsets.UTF_8)
        assertEquals("清理📦", JsonFileTransport.readBounded(ByteArrayInputStream(bytes), bytes.size.toLong()))
        assertThrows(IOException::class.java) {
            JsonFileTransport.readBounded(ByteArrayInputStream(bytes), bytes.size - 1L)
        }
        assertThrows(CharacterCodingException::class.java) {
            JsonFileTransport.readBounded(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), bytes.size.toLong())
        }
    }

    @Test fun requestLargerThan32MiBIsRejectedBeforeMutation() {
        val directory = folder.newFolder("oversized-response")
        val oversized = folder.newFile("oversized-request.json")
        // A sparse file checks the actual descriptor limit without allocating a huge test string.
        RandomAccessFile(oversized, "rw").use { it.setLength(32L * 1024L * 1024L + 1L) }
        // Check the byte limit separately: malformed sparse content alone could also be rejected
        // by the JSON parser and would not demonstrate that oversized descriptors are stopped.
        assertThrows(IOException::class.java) {
            JsonFileTransport.read(ParcelFileDescriptor.open(oversized, ParcelFileDescriptor.MODE_READ_ONLY))
        }
        var mutations = 0
        val result = JSONObject(JsonFileTransport.read(JsonFileTransport.serve(
            directory, ParcelFileDescriptor.open(oversized, ParcelFileDescriptor.MODE_READ_ONLY)
        ) {
            mutations++
            "{}"
        }))
        assertEquals(0, mutations)
        assertTrue(result.getBoolean("requestRejected"))
        assertEmptyDirectory(directory)
    }

    @Test fun localPreparationFailureReportsUnsubmittedAndDoesNotDispatch() {
        val directoryIsAFile = folder.newFile("not-a-directory")
        var exchanges = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.call(directoryIsAFile, JSONArray().put("snapshot")) {
                exchanges++
                null
            }
        }
        assertFalse(error.requestSubmitted)
        assertEquals(0, exchanges)
    }

    @Test fun missingReplyAfterMutationReportsSubmittedAndDoesNotRetry() {
        val directory = folder.newFolder("missing-reply")
        var exchanges = 0
        var mutations = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.call(directory, JSONArray().put("snapshot")) { request ->
                exchanges++
                JsonFileTransport.serve(directory, request) {
                    mutations++
                    "{\"success\":true}"
                }.close()
                null // The service completed, but its reply was lost.
            }
        }
        assertTrue(error.requestSubmitted)
        assertEquals(1, exchanges)
        assertEquals(1, mutations)
        assertEmptyDirectory(directory)
    }

    @Test fun exchangeFailureHasUnknownOutcomeAndDoesNotRetry() {
        val directory = folder.newFolder("failed-exchange")
        var exchanges = 0
        val error = assertThrows(RootJsonTransportException::class.java) {
            JsonFileTransport.call(directory, JSONArray().put("snapshot")) {
                exchanges++
                throw IOException("reply channel disconnected")
            }
        }
        assertTrue(error.requestSubmitted)
        assertEquals(1, exchanges)
        assertEquals("reply channel disconnected", error.cause?.message)
        assertEmptyDirectory(directory)
    }

    private fun assertEmptyDirectory(directory: File) {
        assertTrue("Transport files must be unlinked before dispatch: $directory", directory.listFiles()!!.isEmpty())
    }
}
