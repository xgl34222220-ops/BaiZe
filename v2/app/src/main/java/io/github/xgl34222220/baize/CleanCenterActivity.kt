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
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Code
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
                            onOpenWhitelist = { CleanerNavigation.open(this, Intent(this, WhitelistActivity::class.java)) },
                            onOpenPolicy = { CleanerNavigation.open(this, Intent(this, CleanupPolicyActivity::class.java)) },
                            onOpenRuleVersions = { CleanerNavigation.open(this, Intent(this, RuleBundleActivity::class.java)) },
                            onOpenCustomRules = { CleanerNavigation.open(this, StorageToolsActivity.intent(this, StorageToolMode.CUSTOM)) },
                            onOpenRootTidyExclusions = { CleanerNavigation.open(this, StorageToolsActivity.intent(this, StorageToolMode.ROOT)) },
                            onOpenLegacyProtection = { CleanerNavigation.open(this, Intent(this, LegacyProtectionRecoveryActivity::class.java)) }
                        )
                    )
                }
            }
        }
    }

}

/**
 * 设置 →「规则与保护」中心（唯一固定入口）。原首页「规则与白名单」、设置「保护名单」「规则版本与试跑」都并到这里。
 * 去重：原来的「空文件与空目录 / 规则垃圾 / 残留碎片」三个子扫描已由首页一键扫描覆盖（同一次 scanSafe），
 * 旧 ProfileActivity Intent 仍重定向到工作台对应分类；隔离区移到 记录 → 回收站 的页内切换。
 */
internal data class CleanCenterActions(
    val onBack: () -> Unit = {},
    val onOpenWhitelist: () -> Unit = {},
    val onOpenPolicy: () -> Unit = {},
    val onOpenRuleVersions: () -> Unit = {},
    val onOpenCustomRules: () -> Unit = {},
    val onOpenRootTidyExclusions: () -> Unit = {},
    val onOpenLegacyProtection: () -> Unit = {}
)

private data class CleanCenterItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val directAction: () -> Unit
)

@Composable
internal fun CleanCenterRoute(actions: CleanCenterActions) {
    val openItem: (CleanCenterItem) -> Unit = { item -> item.directAction() }
    val protection = listOf(
        CleanCenterItem(Icons.Rounded.Shield, "保护名单", "保护应用与文件路径，清理时再次核对", directAction = actions.onOpenWhitelist),
        CleanCenterItem(Icons.Rounded.Tune, "清理策略", "清理范围、保留时间与风险偏好", directAction = actions.onOpenPolicy),
        CleanCenterItem(Icons.Rounded.History, "检查旧版保护", "核对升级前的保护记录", directAction = actions.onOpenLegacyProtection)
    )
    val rules = listOf(
        CleanCenterItem(Icons.Rounded.Rule, "规则版本与试跑", "本地签名规则与只读命中预览", directAction = actions.onOpenRuleVersions),
        CleanCenterItem(Icons.Rounded.Code, "自定义路径规则", "按路径与时间筛选，只预览不自动删除", directAction = actions.onOpenCustomRules),
        CleanCenterItem(Icons.Rounded.FolderOff, "不再整理的文件夹", "只影响根目录自动整理，不是删除保护", directAction = actions.onOpenRootTidyExclusions)
    )
    // 「应用缓存」入口已移除：首页一键扫描已包含应用缓存；按应用清理在 清理 →「免 Root 缓存清理」。
    // 旧的 CacheActivity Intent 仍重定向到工作台 cache 分类。

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BaiZeTokens.colors.surfaceBase),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { DetailPageHeader("规则与保护", "", actions.onBack) }
        item { DetailSectionHeader("保护") }
        item { CleanCenterGroup(protection, openItem) }
        item { DetailSectionHeader("规则") }
        item { CleanCenterGroup(rules, openItem) }
        item {
            DetailExpandableText("清理与保护说明",
                "保护名单、关键路径、软链接与挂载点保护会在清理时再次核对。\n\n「不再整理的文件夹」只让根目录自动整理跳过这些目录，不会阻止手动或其他规则清理；需要禁止删除请加入保护名单。")
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
