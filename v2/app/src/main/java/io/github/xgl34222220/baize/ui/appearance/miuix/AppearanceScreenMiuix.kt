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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.AccentOption
import io.github.xgl34222220.baize.ui.appearance.AccentOptions
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle

@Composable
fun AppearanceScreenMiuix(
    settings: AppearanceSettings,
    actions: AppearanceUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MiuixHeader(actions.onBack) }
        item { MiuixPreviewHero(settings) }
        item { MiuixSectionTitle("INTERFACE", "界面风格", "两套皮肤共享同一清理状态与设置") }
        item {
            MiuixGroup {
                MiuixSegmentRow(
                    values = UiStyle.entries,
                    selected = settings.uiStyle,
                    label = { it.label },
                    onSelected = actions.onUiStyle
                )
            }
        }
        item { MiuixSectionTitle("THEME", "明暗模式", "切换后当前页面立即重绘") }
        item {
            MiuixGroup {
                MiuixSegmentRow(
                    values = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = { it.label },
                    onSelected = actions.onThemeMode
                )
            }
        }
        item { MiuixSectionTitle("COLOR", "动态配色", "壁纸取色、强调色与配色风格") }
        item {
            MiuixGroup {
                MiuixSwitchRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Monet 壁纸取色",
                    description = if (monetSupported) {
                        "读取系统壁纸强调色并生成完整主题"
                    } else {
                        "需要 Android 12 或更高版本"
                    },
                    checked = monetSupported && settings.monetEnabled,
                    enabled = monetSupported,
                    onCheckedChange = actions.onMonetEnabled
                )
                MiuixDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (settings.monetEnabled) .38f else 1f)
                        .padding(vertical = 14.dp)
                ) {
                    Text("手动强调色", fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (settings.monetEnabled) "Monet 开启时暂不使用手动颜色" else settings.accent.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    AccentGrid(
                        selectedArgb = settings.seedArgb,
                        enabled = !settings.monetEnabled,
                        onSelected = { actions.onSeedArgb(it.argb) }
                    )
                }
                MiuixDivider()
                Column(Modifier.padding(vertical = 14.dp)) {
                    Text("配色风格", fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    MiuixSegmentRow(
                        values = KolorStyle.entries,
                        selected = settings.kolorStyle,
                        label = { it.label },
                        onSelected = actions.onKolorStyle
                    )
                }
            }
        }
        item { MiuixSectionTitle("EFFECTS", "显示效果", "玻璃、Haze 模糊与底栏形态") }
        item {
            MiuixGroup {
                MiuixSwitchRow(
                    icon = Icons.Rounded.DarkMode,
                    title = "AMOLED 纯黑",
                    description = "深色模式使用真正的黑色背景",
                    checked = settings.amoledBlack,
                    onCheckedChange = actions.onAmoledBlack
                )
                MiuixDivider()
                MiuixSwitchRow(
                    icon = Icons.Rounded.Layers,
                    title = "玻璃材质",
                    description = "启用半透明、高光和材质层次",
                    checked = settings.glassEnabled,
                    onCheckedChange = actions.onGlassEnabled
                )
                MiuixDivider()
                MiuixSwitchRow(
                    icon = Icons.Rounded.BlurOn,
                    title = "Haze 背景模糊",
                    description = "模糊悬浮底栏后方的真实页面内容",
                    checked = settings.blurEnabled,
                    enabled = settings.glassEnabled,
                    onCheckedChange = actions.onBlurEnabled
                )
                MiuixDivider()
                MiuixSwitchRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = "悬浮底栏",
                    description = "关闭后切换为贴合屏幕底部的导航栏",
                    checked = settings.floatingDock,
                    onCheckedChange = actions.onFloatingDock
                )
            }
        }
    }
}

@Composable
private fun MiuixHeader(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(5.dp))
        Column {
            Text(
                "APPEARANCE",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp
            )
            Text(
                "界面与主题",
                fontSize = 35.sp,
                lineHeight = 39.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun MiuixPreviewHero(settings: AppearanceSettings) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pureBlack = dark && settings.amoledBlack
    val shape = RoundedCornerShape(38.dp)
    val background = when {
        pureBlack -> Color(0xFF080808)
        dark -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }

    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(14.dp, shape, clip = false)
            .clip(shape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) .09f else .05f), shape)
            .padding(23.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Palette, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("实时主题预览", fontSize = 21.sp, fontWeight = FontWeight.Black)
                    Text(
                        "${settings.uiStyle.label} · ${settings.themeMode.label} · ${settings.kolorStyle.label}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (settings.monetEnabled) "Monet 壁纸动态取色" else settings.accent.label,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "${if (settings.glassEnabled) "玻璃开启" else "实心材质"} · ${if (settings.blurEnabled && settings.glassEnabled) "Haze 模糊" else "无模糊"} · ${if (settings.floatingDock) "悬浮底栏" else "贴底底栏"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MiuixSectionTitle(eyebrow: String, title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 2.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Text(title, fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun MiuixGroup(content: @Composable () -> Unit) {
    val settings = LocalAppearanceSettings.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pureBlack = dark && settings.amoledBlack
    val shape = RoundedCornerShape(31.dp)
    val fill = when {
        pureBlack -> Color(0xFF090909)
        dark -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }
    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(7.dp, shape, clip = false)
            .clip(shape)
            .background(fill)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) .08f else .05f), shape)
            .padding(horizontal = 17.dp, vertical = 8.dp)
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun <T> MiuixSegmentRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        values.forEach { value ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = .16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = .32f)
                        else Color.Transparent,
                        RoundedCornerShape(18.dp)
                    )
                    .clickable { onSelected(value) }
                    .padding(horizontal = 7.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(value),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AccentGrid(
    selectedArgb: Int,
    enabled: Boolean,
    onSelected: (AccentOption) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentOptions.chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { option ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onSelected(option) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(option.argb))
                                .border(
                                    width = if (option.argb == selectedArgb) 3.dp else 1.dp,
                                    color = if (option.argb == selectedArgb) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        Color.White.copy(alpha = .42f)
                                    },
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(option.label, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixSwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .42f)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun MiuixDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .075f))
    )
}