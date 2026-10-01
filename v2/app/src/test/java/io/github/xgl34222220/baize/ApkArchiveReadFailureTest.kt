package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipException

class ApkArchiveReadFailureTest {
    @Test fun unreadableOrMissingFilesAreNotLabelledInvalidArchives() {
        for (error in listOf(FileNotFoundException("synthetic unreadable file"), IOException("synthetic read error"), SecurityException())) {
            assertEquals(ApkArchiveFailure.INACCESSIBLE, ApkArchivePolicy.readFailure(error))
        }
    }
    @Test fun malformedZipAndUnknownPlatformFailureRemainDistinct() {
        assertEquals(ApkArchiveFailure.INVALID_ARCHIVE, ApkArchivePolicy.readFailure(ZipException("synthetic bad zip")))
        assertEquals(ApkArchiveFailure.READ_FAILED, ApkArchivePolicy.readFailure(IllegalStateException("synthetic platform error")))
        assertFalse(ApkArchiveFailure.READ_FAILED.label.contains("损坏"))
    }
    @Test fun cancellationAndKnownSafeFailureKeepTheirOriginalMeaning() {
        assertThrows(CancellationException::class.java) { ApkArchivePolicy.readFailure(CancellationException()) }
        assertEquals(ApkArchiveFailure.FILE_CHANGED, ApkArchivePolicy.readFailure(ApkArchiveReadException(ApkArchiveFailure.FILE_CHANGED)))
    }
}
