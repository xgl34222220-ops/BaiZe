package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkArchivePolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun invalidInputsAreRejectedBeforeArchiveParsing() {
        assertNull(ApkArchivePolicy.inputFailure("/storage/emulated/0/中文下载/App.APK", 10))
        assertEquals(ApkArchiveFailure.INVALID_PATH, ApkArchivePolicy.inputFailure("relative.apk", 10))
        assertEquals(ApkArchiveFailure.INVALID_PATH, ApkArchivePolicy.inputFailure("/x/../app.apk", 10))
        assertEquals(ApkArchiveFailure.INVALID_PATH, ApkArchivePolicy.inputFailure("/x/\u0000app.apk", 10))
        assertEquals(ApkArchiveFailure.UNSUPPORTED_FORMAT, ApkArchivePolicy.inputFailure("/x/bundle.apks", 10))
        assertEquals(ApkArchiveFailure.UNSUPPORTED_FORMAT, ApkArchivePolicy.inputFailure("/x/bundle.xapk", 10))
        assertEquals(ApkArchiveFailure.FILE_CHANGED, ApkArchivePolicy.inputFailure("/x/app.apk", 0))
        assertEquals(ApkArchiveFailure.TOO_LARGE, ApkArchivePolicy.inputFailure("/x/app.apk", Long.MAX_VALUE))
    }

    @Test fun sizeAndKnownModificationTimeMustMatchExactly() {
        assertTrue(ApkArchivePolicy.snapshotMatches(100, 900, 100, 900))
        assertFalse(ApkArchivePolicy.snapshotMatches(101, 900, 100, 900))
        assertFalse(ApkArchivePolicy.snapshotMatches(100, 901, 100, 900))
        assertFalse(ApkArchivePolicy.snapshotMatches(0, 900, 0, 900))
        assertTrue(ApkArchivePolicy.snapshotMatches(100, 900, 100, 0))
    }

    @Test fun aZipWithAnApkSuffixDoesNotImplyItIsAnApk() {
        val file = fixture("readme.txt" to "not an APK".toByteArray())
        assertFailure(ApkArchiveFailure.INVALID_ARCHIVE) { ApkArchivePolicy.checkZip(file) }
    }

    @Test fun oversizedCompressedManifestIsRejectedWithoutExtraction() {
        val file = fixture("AndroidManifest.xml" to ByteArray((ApkArchivePolicy.MAX_RESOURCE_BYTES + 1).toInt()))
        assertTrue(file.length() < 10_000)
        assertFailure(ApkArchiveFailure.RESOURCE_LIMIT) { ApkArchivePolicy.checkZip(file) }
        assertEquals(1, requireNotNull(folder.root.listFiles()).size)
    }

    @Test fun resourceBudgetAppliesToInflatedBytesAndRequiresAnExistingEntry() {
        val file = fixture("AndroidManifest.xml" to byteArrayOf(1),
            "res/drawable/giant.png" to ByteArray((ApkArchivePolicy.MAX_RESOURCE_BYTES + 1).toInt()))
        ApkArchivePolicy.checkZip(file)
        ZipFile(file).use { zip ->
            assertFailure(ApkArchiveFailure.RESOURCE_LIMIT) { ApkArchivePolicy.checkResource(zip, "res/drawable/giant.png") }
            assertFailure(ApkArchiveFailure.ICON_UNAVAILABLE) { ApkArchivePolicy.checkResource(zip, "res/drawable/missing.png") }
        }
    }

    @Test fun cancellationEscapesZipInspectionAndClosesItsDescriptor() {
        val file = fixture("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to byteArrayOf(0))
        val cancellation = CancellationException("synthetic cancellation")
        repeat(12) {
            assertSame(cancellation, assertThrows(CancellationException::class.java) {
                ApkArchivePolicy.checkZip(file) { throw cancellation }
            })
        }
        val openHandles = File("/proc/self/fd").listFiles()?.count {
            runCatching { it.canonicalPath == file.canonicalPath }.getOrDefault(false)
        }
        if (openHandles != null) assertEquals(0, openHandles)
    }

    @Test fun labelsKeepActualArchiveTextWithoutControlOrBidiSpoofing() {
        assertEquals("示例应用", ApkArchiveMetadata.cleanLabel(" \u202e示例\n应用\u2069 "))
        assertEquals(256, ApkArchiveMetadata.cleanLabel("a".repeat(1000)).length)
        assertEquals("", ApkArchiveMetadata.cleanLabel(""))
        assertEquals(ApkInstallStatus.OLDER, ApkArchiveMetadata.compareVersions(1, 2))
        assertEquals(ApkInstallStatus.NEWER, ApkArchiveMetadata.compareVersions(3, 2))
        assertEquals(ApkInstallStatus.INSTALLED, ApkArchiveMetadata.compareVersions(2, 2))
        assertEquals(ApkInstallStatus.NOT_INSTALLED, ApkArchiveMetadata.compareVersions(2, null))
    }

    @Test fun samplingNeverAllocatesAnOriginalHugeRasterForTheFinalIcon() {
        assertEquals(1, ApkArchiveIcon.sampleSize(192, 192))
        assertEquals(8, ApkArchiveIcon.sampleSize(2048, 1024))
        assertTrue(Int.MAX_VALUE / ApkArchiveIcon.sampleSize(Int.MAX_VALUE, 1) <= 384)
    }

    private fun fixture(vararg entries: Pair<String, ByteArray>): File = folder.newFile("fixture-${System.nanoTime()}.apk").also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name)); output.write(content); output.closeEntry()
            }
        }
    }

    private fun assertFailure(reason: ApkArchiveFailure, action: () -> Unit) {
        assertEquals(reason, assertThrows(ApkArchiveReadException::class.java, action).reason)
    }
}
