package io.github.xgl34222220.baize.ui.appearance.material

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.appearance.AccentOption
import io.github.xgl34222220.baize.ui.appearance.AccentOptions
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceUiActions
import io.github.xgl34222220.baize.ui.appearance.KolorStyle
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle

@Composable
fun AppearanceScreenMaterial(
    settings: AppearanceSettings,
    actions: AppearanceUiActions
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val monetSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { MaterialHeader(actions.onBack) }
            item { MaterialPreview(settings) }
            item { MaterialSectionTitle("INTERFACE", "界面风格") }
            item {
                MaterialChoiceCard {
                    MaterialSegmentRow(
                        values = UiStyle.entries,
                        selected = settings.uiStyle,
                        label = { it.label },
                        onSelected = actions.onUiStyle
                    )
                }
            }
            item { MaterialSectionTitle("THEME", "明暗模式") }
            item {
                MaterialChoiceCard {
                    MaterialSegmentRow(
                        values = ThemeMode.entries,
                        selected = settings.themeMode,
                        label = { it.label },
                        onSelected = actions.onThemeMode
                    )
                }
            }
            item { MaterialSectionTitle("COLOR", "动态配色") }
            item {
                MaterialChoiceCard {
                    MaterialSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "Monet 壁纸取色",
                        description = if (monetSupported) {
                            "使用系统壁纸强调色生成整套界面颜色"
                        } else {
                            "需要 Android 12 或更高版本"
                        },
                        checked = monetSupported && settings.monetEnabled,
                        enabled = monetSupported,
                        onCheckedChange = actions.onMonetEnabled
                    )
                    HorizontalDivider()
                    Text(
                        "手动强调色",
                        modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    AccentGrid(
                        selectedArgb = settings.seedArgb,
                        enabled = !settings.monetEnabled,
                        onSelected = { actions.onSeedArgb(it.argb) }
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("配色风格", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    MaterialSegmentRow(
                        values = KolorStyle.entries,
                        selected = settings.kolorStyle,
                        label = { it.label },
                        onSelected = actions.onKolorStyle
                    )
                }
            }
            item { MaterialSectionTitle("EFFECTS", "显示效果") }
            item {
                MaterialChoiceCard {
                    MaterialSwitchRow(
                        icon = Icons.Rounded.DarkMode,
                        title = "AMOLED 纯黑",
                        description = "深色模式下使用真正的黑色背景",
                        checked = settings.amoledBlack,
                        onCheckedChange = actions.onAmoledBlack
                    )
                    HorizontalDivider()
                    MaterialSwitchRow(
                        icon = Icons.Rounded.Layers,
                        title = "玻璃材质",
                        description = "启用半透明、高光和层次效果",
                        checked = settings.glassEnabled,
                        onCheckedChange = actions.onGlassEnabled
                    )
                    HorizontalDivider()
                    MaterialSwitchRow(
                        icon = Icons.Rounded.BlurOn,
                        title = "背景模糊",
                        description = "使用 Haze 模糊悬浮底栏后方内容",
                        checked = settings.blurEnabled,
                        enabled = settings.glassEnabled,
                        onCheckedChange = actions.onBlurEnabled
                    )
                    HorizontalDivider()
                    MaterialSwitchRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = "悬浮底栏",
                        description = "关闭后底栏会贴合屏幕底部",
                        checked = settings.floatingDock,
                        onCheckedChange = actions.onFloatingDock
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialHeader(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                "APPEARANCE",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp
            )
            Text("界面与主题", style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun MaterialPreview(settings: AppearanceSettings) {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("实时主题预览", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${settings.uiStyle.label} · ${settings.themeMode.label} · ${settings.kolorStyle.label}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (settings.monetEnabled) "Monet 系统壁纸取色" else "手动强调色：${settings.accent.label}",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            Text(
                listOfNotNull(
                    "玻璃${if (settings.glassEnabled) "开启" else "关闭"}",
                    "模糊${if (settings.blurEnabled && settings.glassEnabled) "开启" else "关闭"}",
                    if (settings.amoledBlack) "AMOLED" else null,
                    if (settings.floatingDock) "悬浮底栏" else "贴底底栏"
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MaterialSectionTitle(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 2.dp)) {
        Text(
            eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun MaterialChoiceCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), content = { content() })
    }
}

@Composable
private fun <T> MaterialSegmentRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value), maxLines = 1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AccentGrid(
    selectedArgb: Int,
    enabled: Boolean,
    onSelected: (AccentOption) -> Unit
) {
    Column(
        Modifier.alpha(if (enabled) 1f else .38f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                                .size(44.dp)
                                .background(Color(option.argb), CircleShape)
                                .border(
                                    width = if (option.argb == selectedArgb) 3.dp else 1.dp,
                                    color = if (option.argb == selectedArgb) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(option.label, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialSwitchRow(
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
            .alpha(if (enabled) 1f else .45f)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}