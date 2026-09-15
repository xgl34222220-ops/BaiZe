package io.github.xgl34222220.baize.ui.appearance.miuix

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.ui.appearance.AccentOption
import io.github.xgl34222220.baize.ui.appearance.AccentOptions
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.RefreshRateMode
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroupDivider
import io.github.xgl34222220.baize.ui.miuix.LuoShuPageHeader
import io.github.xgl34222220.baize.ui.miuix.LuoShuSection
import io.github.xgl34222220.baize.ui.miuix.LuoShuSwitchRow
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

/** Appearance settings rendered with the same LuoShu hierarchy as the main BaiZe shell. */
@Composable
fun AppearanceScreenMiuix(settings: AppearanceSettings, actions: AppearanceUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val usesMonet = monetSupported && settings.monetEnabled

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "appearance-header") {
            LuoShuPageHeader("外观与主题", actions.onBack)
        }
        item(key = "appearance-preview") {
            AppearancePreview(settings, usesMonet)
        }

        item(key = "appearance-style-title") { LuoShuSection("界面风格", "白泽默认使用洛书同款 MIUIX 设计") }
        item(key = "appearance-style") {
            ChoiceCard(
                values = UiStyle.entries,
                selected = settings.uiStyle,
                label = { if (it == UiStyle.MIUIX) "洛书 / MIUIX" else it.label },
                onSelected = actions.onUiStyle
            )
        }

        item(key = "appearance-theme-title") { LuoShuSection("明暗模式") }
        item(key = "appearance-theme") { ThemeChoices(settings.themeMode, actions.onThemeMode) }

        item(key = "appearance-color-title") { LuoShuSection("主题配色", "保留白泽自己的品牌色，同时沿用洛书的层级") }
        item(key = "appearance-color") {
            LuoShuGroup {
                LuoShuSwitchRow(
                    Icons.Rounded.AutoAwesome,
                    "跟随壁纸配色",
                    if (monetSupported) "使用系统壁纸的强调色" else "需要 Android 12 或更高版本",
                    usesMonet,
                    actions.onMonetEnabled,
                    enabled = monetSupported
                )
                LuoShuGroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("强调色", style = MaterialTheme.typography.titleSmall)
                    if (usesMonet) {
                        Text(
                            "关闭壁纸配色后可手动选择强调色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AccentGrid(settings.seedArgb, !usesMonet) { actions.onSeedArgb(it.argb) }
                }
            }
        }

        item(key = "appearance-kolor-title") { LuoShuSection("色彩风格") }
        item(key = "appearance-kolor") {
            ChoiceCard(KolorStyle.entries, settings.kolorStyle, { it.label }, actions.onKolorStyle)
        }

        item(key = "appearance-effects-title") { LuoShuSection("显示效果", "玻璃、模糊与悬浮底栏使用同一套能力门控") }
        item(key = "appearance-effects") {
            LuoShuGroup {
                LuoShuSwitchRow(
                    Icons.Rounded.DarkMode,
                    "纯黑背景",
                    "深色模式使用黑色背景，适合 OLED 屏幕",
                    settings.amoledBlack,
                    actions.onAmoledBlack
                )
                LuoShuGroupDivider()
                LuoShuSwitchRow(
                    Icons.Rounded.Layers,
                    "半透明材质",
                    "让导航栏呈现洛书同款玻璃层次",
                    settings.glassEnabled,
                    actions.onGlassEnabled
                )
                LuoShuGroupDivider()
                LuoShuSwitchRow(
                    Icons.Rounded.BlurOn,
                    "背景模糊",
                    "模糊导航栏后方内容，需开启半透明材质",
                    settings.blurEnabled,
                    actions.onBlurEnabled,
                    enabled = settings.glassEnabled
                )
                LuoShuGroupDivider()
                LuoShuSwitchRow(
                    Icons.Rounded.PhoneAndroid,
                    "悬浮导航栏",
                    "关闭后导航栏贴合屏幕底部",
                    settings.floatingDock,
                    actions.onFloatingDock
                )
            }
        }

        item(key = "appearance-performance-title") { LuoShuSection("流畅与省电") }
        item(key = "appearance-refresh") {
            ChoiceCard(RefreshRateMode.entries, settings.refreshRateMode, { it.label }, actions.onRefreshRateMode)
        }
        item(key = "appearance-adaptive") {
            LuoShuGroup {
                LuoShuSwitchRow(
                    Icons.Rounded.PhoneAndroid,
                    "自适应流畅模式",
                    "掉帧、发热或省电时减少模糊与动画",
                    settings.adaptiveSmoothMode,
                    actions.onAdaptiveSmoothMode
                )
            }
        }
    }
}

@Composable
private fun AppearancePreview(settings: AppearanceSettings, usesMonet: Boolean) {
    val colors = BaiZeTokens.colors
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceRaised,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(scheme.primaryContainer.copy(alpha = .46f), colors.surfaceRaised)
                    )
                )
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(18.dp),
                color = colors.surfaceOverlay
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(24.dp), tint = scheme.primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("当前主题", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (usesMonet) "壁纸配色 · ${settings.kolorStyle.label}" else "${settings.accent.label} · ${settings.kolorStyle.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    if (settings.uiStyle == UiStyle.MIUIX) "洛书 / MIUIX" else settings.uiStyle.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                    Box(Modifier.size(12.dp, 30.dp).clip(RoundedCornerShape(6.dp)).background(color))
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceCard(values: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = BaiZeTokens.colors.surfaceOverlay
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            values.forEach { value ->
                Box(
                    Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (value == selected) BaiZeTokens.colors.surfaceRaised else Color.Transparent)
                        .selectable(value == selected, role = Role.RadioButton) { onSelected(value) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(value),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        color = if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeChoices(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val active = selected == mode
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(BaiZeTokens.colors.surfaceRaised)
                    .selectable(active, role = Role.RadioButton) { onSelected(mode) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(13.dp))) {
                    val halves = if (mode == ThemeMode.SYSTEM) listOf(false, true) else listOf(mode == ThemeMode.DARK)
                    halves.forEach { dark ->
                        val background = if (dark) Color(0xFF1A202A) else Color(0xFFEFF3F9)
                        val surface = if (dark) Color(0xFF2A3340) else Color.White
                        Column(
                            Modifier.weight(1f).fillMaxSize().background(background).padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(Modifier.width(16.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp)).background(surface))
                            Box(Modifier.fillMaxWidth(.65f).height(4.dp).clip(CircleShape).background(surface))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(5.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
            }
        }
    }
}

@Composable
private fun AccentGrid(selectedArgb: Int, enabled: Boolean, onSelected: (AccentOption) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.alpha(if (enabled) 1f else .45f).selectableGroup()
    ) {
        AccentOptions.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    val selected = option.argb == selectedArgb
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .selectable(selected, enabled = enabled, role = Role.RadioButton) { onSelected(option) }
                            .semantics { contentDescription = "${option.label}${if (selected) "，已选择" else ""}" }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(option.argb)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = if (Color(option.argb).luminance() > .4f) Color.Black else Color.White
                                )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
