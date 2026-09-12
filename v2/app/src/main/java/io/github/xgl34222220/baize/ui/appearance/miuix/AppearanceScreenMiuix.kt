package io.github.xgl34222220.baize.ui.appearance.miuix

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.AccentOption
import io.github.xgl34222220.baize.ui.appearance.AccentOptions
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.RefreshRateMode
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.miuix.VideoCard
import io.github.xgl34222220.baize.ui.miuix.VideoDivider
import io.github.xgl34222220.baize.ui.miuix.VideoIconButton
import io.github.xgl34222220.baize.ui.miuix.VideoLeadingIcon
import io.github.xgl34222220.baize.ui.miuix.VideoListRow
import io.github.xgl34222220.baize.ui.miuix.VideoSectionTitle
import io.github.xgl34222220.baize.ui.miuix.VideoTopBar

/** Both skins share this responsive structure and immediately reflect every appearance change. */
@Composable
fun AppearanceScreenMiuix(settings: AppearanceSettings, actions: AppearanceUiActions) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val usesMonet = monetSupported && settings.monetEnabled
    LazyColumn(
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { VideoTopBar("界面与主题", "你的配色，实时呈现", start = { VideoIconButton(Icons.Rounded.ArrowBack, "返回", actions.onBack) }) }
        item { AppearancePreview(settings, usesMonet) }
        item { VideoSectionTitle("界面风格") }
        item { ChoiceCard(UiStyle.entries, settings.uiStyle, { it.label }, actions.onUiStyle) }
        item { VideoSectionTitle("明暗模式") }
        item { ChoiceCard(ThemeMode.entries, settings.themeMode, { it.label }, actions.onThemeMode) }
        item { VideoSectionTitle("主题配色", "壁纸取色或选一种喜欢的颜色") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                AppearanceSwitch(Icons.Rounded.AutoAwesome, "跟随壁纸配色",
                    if (monetSupported) "使用系统壁纸的强调色" else "需要 Android 12 或更高版本",
                    usesMonet, monetSupported, actions.onMonetEnabled)
                VideoDivider()
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("强调色", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (usesMonet) Text("关闭壁纸配色后可选择", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AccentGrid(settings.seedArgb, !usesMonet) { actions.onSeedArgb(it.argb) }
                }
            }
        }
        item { VideoSectionTitle("色彩风格") }
        item { ChoiceCard(KolorStyle.entries, settings.kolorStyle, { it.label }, actions.onKolorStyle) }
        item { VideoSectionTitle("显示效果") }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                AppearanceSwitch(Icons.Rounded.DarkMode, "纯黑背景", "深色模式使用黑色背景，适合 OLED 屏幕", settings.amoledBlack, onCheckedChange = actions.onAmoledBlack)
                VideoDivider()
                AppearanceSwitch(Icons.Rounded.Layers, "半透明材质", "让导航栏呈现柔和的层次", settings.glassEnabled, onCheckedChange = actions.onGlassEnabled)
                VideoDivider()
                AppearanceSwitch(Icons.Rounded.BlurOn, "背景模糊", "模糊导航栏后方内容，需开启半透明材质", settings.blurEnabled, settings.glassEnabled, actions.onBlurEnabled)
                VideoDivider()
                AppearanceSwitch(Icons.Rounded.PhoneAndroid, "悬浮导航栏", "关闭后导航栏贴合屏幕底部", settings.floatingDock, onCheckedChange = actions.onFloatingDock)
            }
        }
        item { VideoSectionTitle("流畅与省电") }
        item { ChoiceCard(RefreshRateMode.entries, settings.refreshRateMode, { it.label }, actions.onRefreshRateMode) }
        item {
            VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
                AppearanceSwitch(Icons.Rounded.PhoneAndroid, "自适应流畅模式", "掉帧、发热或省电时减少模糊与动画", settings.adaptiveSmoothMode, onCheckedChange = actions.onAdaptiveSmoothMode)
            }
        }
    }
}

@Composable
private fun AppearancePreview(settings: AppearanceSettings, usesMonet: Boolean) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = 24) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoLeadingIcon(Icons.Rounded.Palette)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("主题预览", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (usesMonet) "壁纸配色 · ${settings.kolorStyle.label}" else "${settings.accent.label} · ${settings.kolorStyle.label}",
                    fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(18.dp)) {
            Text("清爽，从每一天开始", fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("更清晰的内容，更自在的空间", fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary).forEach { color ->
                    Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(9.dp)).background(color))
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceCard(values: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
    VideoCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), contentPadding = 0) {
        values.forEachIndexed { index, value ->
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.RadioButton) { onSelected(value) }.padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label(value), Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp,
                    color = if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal)
                RadioButton(value == selected, { onSelected(value) })
            }
            if (index != values.lastIndex) VideoDivider(start = 18)
        }
    }
}

@Composable
private fun AccentGrid(selectedArgb: Int, enabled: Boolean, onSelected: (AccentOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.alpha(if (enabled) 1f else .45f)) {
        AccentOptions.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { option ->
                    val selected = option.argb == selectedArgb
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled, role = Role.RadioButton) { onSelected(option) }
                        .semantics { contentDescription = "${option.label}${if (selected) "，已选择" else ""}" }
                        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(option.argb))
                            .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = if (Color(option.argb).luminance() > .4f) Color.Black else Color.White)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(option.label, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppearanceSwitch(icon: ImageVector, title: String, description: String, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    VideoListRow(icon, title, description, enabled = enabled, trailing = { Switch(checked, onCheckedChange, enabled = enabled) })
}
