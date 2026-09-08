package io.github.xgl34222220.baize.root

import org.junit.Assert.*
import org.junit.Test

class RootMediaScanCommandTest {
    @Test fun providerErrorsAreNotSuccessEvenWithExitZero() {
        assertFalse(RootMediaScanCommand.succeeded(0, "Error while accessing provider:media\njava.lang.SecurityException"))
        assertFalse(RootMediaScanCommand.succeeded(0, "Result: null"))
        assertFalse(RootMediaScanCommand.succeeded(1, "Result: Bundle[{android.intent.extra.STREAM=null}]"))
        assertTrue(RootMediaScanCommand.succeeded(0, "Result: Bundle[{android.intent.extra.STREAM=null}]"))
        assertTrue(RootMediaScanCommand.succeeded(0, "Result: Bundle[{android.intent.extra.STREAM=content://media/external/images/media/1}]"))
    }

    @Test fun pathsAreSingleArgumentsAndSelectCorrectStorageUser() {
        val path = "/storage/emulated/10/照片/a ' ; touch bad.jpg"
        val args = RootMediaScanCommand.arguments(path)
        assertEquals("10", args[args.indexOf("--user") + 1])
        assertEquals(path, args.last())
        assertEquals("/system/bin/content", args.first())
        val privatePath = RootMediaScanCommand.arguments("/data/media/12/DCIM/a.jpg")
        assertEquals("12", privatePath[privatePath.indexOf("--user") + 1])
    }
}
