package io.github.xgl34222220.baize

import org.junit.Assert.*
import org.junit.Test

class DirectoryUsageTreeTest {
    private val root = "/storage/emulated/0"
    private fun usage(vararg dirs: StorageDirectory) = DirectoryUsage(listOf(root), listOf(*dirs), 0, 0, false, "本地")

    @Test fun childrenAreIndexedOnceAndSortedBySize() {
        val u = usage(StorageDirectory(root, 10, 1000), StorageDirectory("$root/DCIM", 4, 300),
            StorageDirectory("$root/Download", 5, 600), StorageDirectory("$root/DCIM/Camera", 4, 300),
            StorageDirectory("$root/Download/x", 1, 100))
        assertEquals(listOf(root), u.children(null).map { it.path })
        assertEquals(listOf("$root/Download", "$root/DCIM"), u.children(root).map { it.path })
        assertEquals(listOf("$root/DCIM/Camera"), u.children("$root/DCIM").map { it.path })
        assertTrue(u.children("$root/missing").isEmpty())
        assertSame(u.tree, u.tree)
        assertEquals(300L, u.tree.directory("$root/DCIM")?.bytes)
    }

    @Test fun pagesSplitLargeDirectoriesWithoutLosingEntries() {
        val dirs = (0 until 95).map { StorageDirectory("$root/d$it", 1, (1000 - it).toLong()) }
        val tree = DirectoryUsageTree(listOf(root), listOf(StorageDirectory(root, 95, 100_000)) + dirs)
        assertEquals(40, tree.page(root, 0).size)
        assertEquals(40, tree.page(root, 1).size)
        assertEquals(15, tree.page(root, 2).size)
        assertTrue(tree.page(root, 3).isEmpty())
        assertEquals(dirs.map { it.path }, (0..2).flatMap { tree.page(root, it) }.map { it.path })
    }

    @Test fun sunburstAnglesFollowShareAndMergeTinyDirectories() {
        val tree = usage(StorageDirectory(root, 10, 1000), StorageDirectory("$root/A", 1, 600),
            StorageDirectory("$root/B", 1, 300), StorageDirectory("$root/tiny", 1, 5),
            StorageDirectory("$root/A/inner", 1, 300)).tree
        val rings = tree.sunburst(root)
        val a = rings.single { it.path == "$root/A" }
        assertEquals(0, a.level); assertEquals(0.0, a.start, 1e-9); assertEquals(.6, a.sweep, 1e-9)
        val b = rings.single { it.path == "$root/B" }
        assertEquals(.6, b.start, 1e-9); assertEquals(.3, b.sweep, 1e-9)
        val other = rings.single { it.path == null && it.level == 0 }
        assertEquals(5L, other.bytes)
        val inner = rings.single { it.path == "$root/A/inner" }
        assertEquals(1, inner.level); assertEquals(0.0, inner.start, 1e-9); assertEquals(.3, inner.sweep, 1e-9)
        // Files directly inside the root leave an honest gap rather than being stretched to 360°.
        assertTrue(rings.filter { it.level == 0 }.sumOf { it.sweep } < 1.0)
        assertTrue(tree.sunburst("$root/B").isEmpty())
        assertTrue(DirectoryUsageTree(emptyList(), emptyList()).sunburst(null).isEmpty())
    }

    @Test fun tapsResolveToTheRingAndAngleUnderTheFinger() {
        val segments = listOf(SunburstSegment("a", "a", 0, 0.0, .5, 5), SunburstSegment("b", "b", 0, .5, .25, 2),
            SunburstSegment("a/x", "x", 1, 0.0, .25, 1))
        // inner radius 40, ring width 30: ring 0 is 40..70, ring 1 is 70..100.
        assertEquals("a", sunburstHit(segments, 55f, 0f, 40f, 30f)?.path)      // 3 o'clock, 25 %
        assertEquals("b", sunburstHit(segments, 0f, 55f, 40f, 30f)?.path)      // 6 o'clock, 50 %
        assertEquals("a/x", sunburstHit(segments, 60f, -40f, 40f, 30f)?.path)  // ring 1, ~15 %
        assertNull(sunburstHit(segments, -55f, 0f, 40f, 30f))                   // 9 o'clock: unused gap
        assertNull(sunburstHit(segments, 10f, 10f, 40f, 30f))                   // centre hole
    }
}
