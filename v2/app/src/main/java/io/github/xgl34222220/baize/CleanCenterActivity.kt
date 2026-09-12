package io.github.xgl34222220.baize

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

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

internal data class CleanCenterActions(
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
    val profile: String? = null,
    val directAction: (() -> Unit)? = null,
    val dangerous: Boolean = false
)

@Composable
internal fun CleanCenterRoute(actions: CleanCenterActions) {
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val openItem: (CleanCenterItem) -> Unit = { item ->
        when {
            item.dangerous && item.profile != null -> confirmation = item.profile
            item.profile != null -> actions.onOpenProfile(item.profile)
            item.directAction != null -> item.directAction.invoke()
        }
    }
    val rules = listOf(
        CleanCenterItem(Icons.Rounded.FolderOff, "空文件与空目录", "识别空项目，保留公共媒体目录", profile = "empty"),
        CleanCenterItem(Icons.Rounded.Rule, "规则垃圾", "应用垃圾、隐藏文件与系统日志", profile = "rules"),
        CleanCenterItem(Icons.Rounded.Apps, "残留碎片", "过期临时文件、日志与中断下载", profile = "fragments")
    )
    val protection = listOf(
        CleanCenterItem(Icons.Rounded.Tune, "清理策略", "清理范围、保留时间与风险偏好", directAction = actions.onOpenPolicy),
        CleanCenterItem(Icons.Rounded.Inventory2, "隔离区", "恢复或永久删除已隔离的内容", directAction = actions.onOpenQuarantine)
    )
    val more = listOf(
        CleanCenterItem(Icons.Rounded.Storage, "应用缓存", "查看应用缓存与占用明细", directAction = actions.onOpenCache),
        CleanCenterItem(Icons.Rounded.DeleteForever, "卸载残留", "核对应用卸载后留下的数据", profile = "corpses", dangerous = true),
        CleanCenterItem(Icons.Rounded.DeleteSweep, "完整深度清理", "扫描完整规则库，按风险查看结果", profile = "deep", dangerous = true)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { DetailPageHeader("规则与保护", "", actions.onBack) }
        item { DetailSectionHeader("规则范围") }
        item { CleanCenterGroup(rules, openItem) }
        item { DetailSectionHeader("保护与策略") }
        item { CleanCenterGroup(protection, openItem) }
        item { DetailSectionHeader("更多清理") }
        item { CleanCenterGroup(more, openItem) }
        item {
            GlassActionButton(
                label = "按当前规则快速清理",
                onClick = actions.onQuickClean,
                icon = Icons.Rounded.CleaningServices,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp),
                secondary = true
            )
        }
        item {
            DetailExpandableText("清理与保护说明",
                "各项扫描完成后可查看明细，再选择需要处理的内容。白名单、关键路径、软链接与挂载点保护会在清理时再次核对。\n\n快速清理会立即按当前启用的规则与保留时间执行。高风险内容需要单独确认，不会被普通清理直接删除。")
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    confirmation?.let { profile ->
        val corpses = profile == "corpses"
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (corpses) "扫描卸载残留？" else "开始完整深度扫描？") },
            text = {
                Text(
                    if (corpses) {
                        "将核对应用卸载后留下的数据。扫描不会删除文件，清理前会再次确认应用安装状态。"
                    } else {
                        "将扫描完整规则库，并按风险展示结果。扫描不会删除文件。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    actions.onOpenProfile(profile)
                }) { Text("继续扫描") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CleanCenterGroup(items: List<CleanCenterItem>, openItem: (CleanCenterItem) -> Unit) {
    DetailGlassPanel {
        items.forEachIndexed { index, item ->
            val accent = MaterialTheme.colorScheme.primary
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { openItem(item) }.heightIn(min = 66.dp).padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = .07f)) {
                    Icon(item.icon, null, Modifier.padding(9.dp).size(20.dp), tint = accent)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, lineHeight = 17.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
            }
            if (index != items.lastIndex) HorizontalDivider(Modifier.padding(start = 49.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f))
        }
    }
}
