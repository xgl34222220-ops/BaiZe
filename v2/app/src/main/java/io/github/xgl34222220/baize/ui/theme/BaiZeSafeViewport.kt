package io.github.xgl34222220.baize.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag

/**
 * Own the top/cutout inset outside every scroll or animated page. Padding inside a lazy item
 * scrolls away. Clipping the padded viewport also prevents overscroll and shadows painting
 * over system icons. Inset consumption prevents legacy headers/Scaffolds applying it twice.
 * Bottom insets remain available to the dock, selection bars and keyboard-aware forms.
 */
@Composable
internal fun BaiZeSafeViewport(
    insets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(insets).clipToBounds().testTag("baize-safe-viewport")) {
            content()
        }
    }
}
