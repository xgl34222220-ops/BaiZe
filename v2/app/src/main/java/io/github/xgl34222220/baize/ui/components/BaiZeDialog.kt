package io.github.xgl34222220.baize.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.xgl34222220.baize.performance.PerformanceRuntime
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

private val LocalPrimaryDialogAction = staticCompositionLocalOf { false }

/** Heading and body share a scroll viewport; arbitrary titles cannot displace the actions. */
@Composable
internal fun BaiZeDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .86f).dp
    val contentDensity = LocalDensity.current
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides contentDensity) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val appearance = LocalAppearanceSettings.current
        val blur = appearance.blurEnabled && !PerformanceRuntime.degraded.value
        val radius = with(LocalDensity.current) { 18.dp.roundToPx() }
        DisposableEffect(window, blur, radius) {
            if (window != null) {
                window.setDimAmount(.28f)
                if (Build.VERSION.SDK_INT >= 31 && blur) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    window.attributes = window.attributes.apply { blurBehindRadius = radius }
                }
            }
            onDispose { if (Build.VERSION.SDK_INT >= 31) window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) }
        }
        // HyperOS 风格：手机竖屏时弹层贴底（拇指可达），横屏 / 平板仍居中。
        val portrait = LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp && it.screenWidthDp < 600 }
        Box(Modifier.fillMaxSize()) {
        // 点击弹层外部关闭；独立的背景层，不会拦截弹层内部的点击。
        Box(Modifier.matchParentSize().pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } })
        Box(Modifier.fillMaxSize()
            .navigationBarsPadding().imePadding()
            .padding(horizontal = if (portrait) 12.dp else 24.dp, vertical = if (portrait) 12.dp else 0.dp),
            contentAlignment = if (portrait) Alignment.BottomCenter else Alignment.Center) {
            Surface(modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(32.dp), color = BaiZeTokens.colors.surfaceRaised,
                tonalElevation = 0.dp, shadowElevation = 12.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (icon != null || title != null || text != null) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            if (icon != null) Box(Modifier.align(Alignment.CenterHorizontally)) { icon() }
                            if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                            if (text != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (dismissButton != null) Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalPrimaryDialogAction provides false) { dismissButton() }
                        }
                        Box(Modifier.weight(1f)) {
                            CompositionLocalProvider(LocalPrimaryDialogAction provides true) { confirmButton() }
                        }
                    }
                }
            }
        }
        }
        }
    }
}

@Composable
internal fun BaiZeDialogButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = LocalPrimaryDialogAction.current,
    content: @Composable RowScope.() -> Unit) {
    val haptic = rememberBaiZeHaptic()
    Button(onClick = { if (primary) haptic(); onClick() }, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
        shape = CircleShape, elevation = null, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else BaiZeTokens.colors.surfaceOverlay,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
        content = content)
}
