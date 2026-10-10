package io.github.xgl34222220.baize.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAccentTest {
    @Test
    fun defaultAccentIsBaiZeTealMatchingTheIcon() {
        assertEquals(0xFF006B72.toInt(), DEFAULT_ACCENT_ARGB)
        assertEquals("teal", AccentOptions.first().id)
        assertEquals(DEFAULT_ACCENT_ARGB, AppearanceSettings().seedArgb)
        assertEquals("teal", AppearanceSettings().accent.id)
    }

    @Test
    fun savedChoicesKeepTheirOwnAccent() {
        // 用户已保存的颜色按 argb 原样还原，不会被新默认值覆盖。
        assertEquals("hyper", AppearanceSettings(seedArgb = 0xFFF25C26.toInt()).accent.id)
        assertEquals("default", AppearanceSettings(seedArgb = 0xFF245FD3.toInt()).accent.id)
    }

    @Test
    fun previousSelectableAccentsRemainAvailable() {
        val ids = AccentOptions.map { it.id }
        assertTrue(ids.containsAll(listOf("hyper", "default", "mist", "sage", "sand", "terracotta", "mauve", "slate", "amber")))
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(AccentOptions.size, AccentOptions.map { it.argb }.toSet().size)
    }
}
