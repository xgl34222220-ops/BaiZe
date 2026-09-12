package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme

class CleanCenterActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            val systemDark = isSystemInDarkTheme()
            val dark = when (appearance.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BaiZeTheme(appearance) {
                CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
                    CleanCenterRoute(
                        appearance = appearance,
                        actions = CleanCenterActions(
                            onBack = ::finish,
                            onQuickClean = {
                                startActivity(
                                    Intent(this, MiuixDashboardActivity::class.java)
                                        .putExtra(MiuixDashboardActivity.EXTRA_RUN_SMART_CLEAN, true)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                )
                                finish()
                            },
                            onOpenCache = { startActivity(Intent(this, CacheActivity::class.java)) },
                            onOpenPolicy = { startActivity(Intent(this, CleanupPolicyActivity::class.java)) },
                            onOpenQuarantine = { startActivity(Intent(this, QuarantineActivity::class.java)) },
                            onOpenProfile = ::openProfile
                        )
                    )
                }
            }
        }
    }

    private fun openProfile(profile: String) {
        startActivity(
            Intent(this, ProfileActivity::class.java)
                .putExtra(ProfileActivity.EXTRA_PROFILE, profile)
        )
    }
}

private data class CleanCenterActions(
    val onBack: () -> Unit,
    val onQuickClean: () -> Unit,
    val onOpenCache: () -> Unit,
    val onOpenPolicy: () -> Unit,
    val onOpenQuarantine: () -> Unit,
    val onOpenProfile: (String) -> Unit
)

private data class CleanCenterItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val badge: String,
    val profile: String? = null,
    val directAction: (() -> Unit)? = null,
    val dangerous: Boolean = false
)

@Composable
private fun CleanCenterRoute(
    appearance: AppearanceSettings,
    actions: CleanCenterActions
) {
    var confirmation by remember { mutableStateOf<String?>(null) }
    val openItem: (CleanCenterItem) -> Unit = { item ->
        when {
            item.dangerous && item.profile != null -> confirmation = item.profile
            item.profile != null -> actions.onOpenProfile(item.profile)
            item.directAction != null -> item.directAction.invoke()
        }
    }

    when (appearance.uiStyle) {
        UiStyle.MATERIAL -> CleanCenterMaterial(actions, openItem)
        UiStyle.MIUIX -> CleanCenterMiuix(actions, openItem, appearance)
    }

    confirmation?.let { profile ->
        val corpses = profile == "corpses"
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (corpses) "扫描卸载残留？" else "开始完整深度扫描？") },
            text = {
                Text(
                    if (corpses) {
                        "将核对 Android/data、obb、media 与应用私有目录中的无主数据。扫描阶段不会删除文件，确认清理时仍会重新核对安装状态。"
                    } else {
                        "将加载完整规则库并按风险分级扫描。扫描阶段不会删除文件，确认清理时仍会校验白名单、挂载点与软链接。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    actions.onOpenProfile(profile)
                }) { Text("继续扫描") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CleanCenterMaterial(
    actions: CleanCenterActions,
    openItem: (CleanCenterItem) -> Unit
) {
    val daily = listOf(
        CleanCenterItem(Icons.Rounded.Storage, "应用缓存", "内部 cache、code_cache 与外部缓存", "低风险", directAction = actions.onOpenCache),
        CleanCenterItem(Icons.Rounded.FolderOff, "空文件与空目录", "识别空项目并保护公共媒体目录", "低风险", profile = "empty"),
        CleanCenterItem(Icons.Rounded.Rule, "规则垃圾", "应用规则、隐藏垃圾与系统日志", "分级", profile = "rules"),
        CleanCenterItem(Icons.Rounded.Apps, "残留碎片", "过期临时文件、旋转日志与中断下载", "保留期", profile = "fragments")
    )
    val advanced = listOf(
        CleanCenterItem(Icons.Rounded.Tune, "清理策略", "切换保守、均衡或积极档，不影响定时周期", "三档", directAction = actions.onOpenPolicy),
        CleanCenterItem(Icons.Rounded.Inventory2, "隔离区", "恢复或永久删除已隔离的高风险内容", "可撤销", directAction = actions.onOpenQuarantine),
        CleanCenterItem(Icons.Rounded.DeleteForever, "卸载残留", "核对 data、obb、media 与应用私有目录", "二次确认", profile = "corpses", dangerous = true),
        CleanCenterItem(Icons.Rounded.DeleteSweep, "完整深度清理", "完整规则库扫描并按风险分级", "二次确认", profile = "deep", dangerous = true)
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(BaiZeTokens.colors.surfaceBase)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { CleanCenterMaterialHeader(actions.onBack) }
            item { CleanCenterMaterialHero(actions) }
            item { CleanCenterMaterialSection("DAILY CLEAN", "日常清理", "自动保护白名单与关键路径") }
            item { CleanCenterMaterialGroup(daily, openItem) }
            item { CleanCenterMaterialSection("MANUAL TOOLS", "高级清理", "执行前进行二次确认") }
            item { CleanCenterMaterialGroup(advanced, openItem) }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun CleanCenterMaterialHeader(onBack: () -> Unit) {
    DetailPageHeader("清理工具", "选择清理范围，查看明细后再处理", onBack)
}

@Composable
private fun CleanCenterMaterialHero(actions: CleanCenterActions) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised)
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(66.dp),
                    shape = RoundedCornerShape(23.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(31.dp)
                        )
                    }
                }
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    Text("智能安全清理", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "缓存、空项目、规则垃圾和碎片自动归类；白名单、软链接与挂载点保护始终生效。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanCenterPill("白名单保护", Modifier.weight(1f))
                CleanCenterPill("软链接防护", Modifier.weight(1f))
                CleanCenterPill("挂载点保护", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = actions.onQuickClean,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("立即清理", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CleanCenterMaterialSection(eyebrow: String, title: String, subtitle: String) {
    DetailSectionHeader(title, subtitle)
}

@Composable
private fun CleanCenterMaterialGroup(items: List<CleanCenterItem>, openItem: (CleanCenterItem) -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = BaiZeTokens.colors.surfaceRaised)
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 4.dp)) {
            items.forEachIndexed { index, item ->
                CleanCenterMaterialRow(item) { openItem(item) }
                if (index != items.lastIndex) HorizontalDivider(Modifier.padding(start = 59.dp))
            }
        }
    }
}

@Composable
private fun CleanCenterMaterialRow(item: CleanCenterItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (item.dangerous) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (item.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                item.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            item.badge,
            color = if (item.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CleanCenterMiuix(
    actions: CleanCenterActions,
    openItem: (CleanCenterItem) -> Unit,
    appearance: AppearanceSettings
) {
    CleanCenterMaterial(actions, openItem)
}

@Composable
private fun CleanCenterPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f))
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
