package io.github.xgl34222220.baize.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkRootPathMapperTest {
    @Test
    fun emulatedMediaStorePathPrefersRawMediaBackingPath() {
        val candidates = ApkRootPathMapper.rootCandidates("/storage/emulated/0/Download/test.apk")
        assertEquals("/data/media/0/Download/test.apk", candidates.first())
        assertTrue(candidates.contains("/storage/emulated/0/Download/test.apk"))
    }

    @Test
    fun runtimeEmulatedPathMapsToRawMedia() {
        val candidates = ApkRootPathMapper.rootCandidates("/mnt/runtime/default/emulated/10/Documents/a.apk")
        assertEquals("/data/media/10/Documents/a.apk", candidates.first())
    }

    @Test
    fun removablePublicPathPrefersRawMount() {
        val candidates = ApkRootPathMapper.rootCandidates("/storage/1234-5678/Download/a.apk")
        assertEquals("/mnt/media_rw/1234-5678/Download/a.apk", candidates.first())
    }

    @Test
    fun rawPathsConvertBackToDisplayPaths() {
        assertEquals(
            "/storage/emulated/0/Download/a.apk",
            ApkRootPathMapper.publicPath("/data/media/0/Download/a.apk")
        )
        assertEquals(
            "/storage/1234-5678/Download/a.apk",
            ApkRootPathMapper.publicPath("/mnt/media_rw/1234-5678/Download/a.apk")
        )
    }
}
