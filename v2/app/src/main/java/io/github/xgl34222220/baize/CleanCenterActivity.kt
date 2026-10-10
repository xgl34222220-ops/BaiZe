package io.github.xgl34222220.baize

import io.github.xgl34222220.baize.ui.components.BaiZeDialog
import io.github.xgl34222220.baize.ui.components.BaiZeDialogButton
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.ThemeMode
import io.github.xgl34222220.baize.ui.components.*
import io.github.xgl34222220.baize.ui.miuix.GlassActionButton
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroup
import io.github.xgl34222220.baize.ui.miuix.LuoShuGroupDivider
import io.github.xgl34222220.baize.ui.miuix.LuoShuNavigationRow
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
                            onQuickClean = { CleanerNavigation.scan(this) },
                            onOpenPolicy = { CleanerNavigation.open(this, Intent(this, CleanupPolicyActivity::class.java)) },
                            onOpenQuarantine = { CleanerNavigation.open(this, Intent(this, QuarantineActivity::class.java)) },
                            onOpenProfile = ::openProfile
                        )
                    )
                }
            }
        }
    }

    private fun openProfile(profile: String) { CleanerNavigation.scan(this, profile) }

}

internal data class CleanCenterActions(
    val onBack: () -> Unit,
    val onQuickClean: () -> Unit,
    val onOpenPolicy: () -> Unit,
    val onOpenQuarantine: () -> Unit,
    val onOpenProfile: (String) -> Unit
)

private data class CleanCenterItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val profile: String? = null,
    val directAction: (() -> Unit)? = null
)

@Composable
internal fun CleanCenterRoute(actions: CleanCenterActions) {
    val openItem: (CleanCenterItem) -> Unit = { item ->
        when {
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
    // 「应用缓存」入口已移除：扫描工作台的默认扫描已包含应用缓存，「清理 → 即时缓存」提供按应用清理，
    // 这里再放一个只扫缓存的入口属于重复功能。旧的 CacheActivity Intent 仍重定向到工作台 cache 分类。

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { DetailPageHeader("规则与保护", "", actions.onBack) }
        item { DetailSectionHeader("规则范围") }
        item { CleanCenterGroup(rules, openItem) }
        item { DetailSectionHeader("保护与策略") }
        item { CleanCenterGroup(protection, openItem) }
        item {
            DetailExpandableText("清理与保护说明",
                "各项扫描完成后可查看明细，再选择需要处理的内容。白名单、关键路径、软链接与挂载点保护会在清理时再次核对。\n\n扫描不会删除文件，结果与选择都在同一个页面完成。高风险内容需要单独确认，不会被普通清理直接删除。")
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }


}

@Composable
private fun CleanCenterGroup(items: List<CleanCenterItem>, openItem: (CleanCenterItem) -> Unit) {
    // 与首页、设置页共用 LuoShu 列表行：同样的图标块、字号、72dp 最小触控高度与分隔线缩进。
    LuoShuGroup(Modifier.padding(horizontal = 16.dp)) {
        items.forEachIndexed { index, item ->
            LuoShuNavigationRow(item.icon, item.title, item.description) { openItem(item) }
            if (index != items.lastIndex) LuoShuGroupDivider()
        }
    }
}
