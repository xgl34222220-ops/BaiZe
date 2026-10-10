package io.github.xgl34222220.baize

import org.junit.Assert.assertTrue
import org.junit.Test

class BaiZeSplashExitTest {
    @Test
    fun exitAnimationIsShortFadeAndScale() {
        assertTrue("启动页退出动画不得超过 250ms", BaiZeSplashExit.DURATION_MS in 1..250)
        assertTrue("图标只做轻微放大", BaiZeSplashExit.ICON_END_SCALE in 1.0f..1.15f)
    }
}
