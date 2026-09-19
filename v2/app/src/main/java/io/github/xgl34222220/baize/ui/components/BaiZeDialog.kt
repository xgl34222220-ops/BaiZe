package io.github.xgl34222220.baize.ui.components

import android.os.Build
import android.view.WindowManager
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

/** Shared dialog body scrolls independently; actions remain reachable above the keyboard. */
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
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding(), contentAlignment = Alignment.Center) {
            Surface(modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(28.dp), color = BaiZeTokens.colors.surfaceRaised,
                tonalElevation = 0.dp, shadowElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (icon != null) Box(Modifier.align(Alignment.CenterHorizontally)) { icon() }
                    if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                    if (text != null) Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
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

@Composable
internal fun BaiZeDialogButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = LocalPrimaryDialogAction.current,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 46.dp), enabled = enabled,
        shape = CircleShape, elevation = null, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else BaiZeTokens.colors.surfaceOverlay,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
        content = content)
}
