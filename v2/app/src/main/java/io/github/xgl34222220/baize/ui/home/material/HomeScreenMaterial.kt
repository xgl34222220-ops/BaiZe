package io.github.xgl34222220.baize.ui.home.material

import android.text.format.Formatter
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderDelete
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.BuildConfig
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings

private data class MaterialCleanCategory(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)

@Composable
fun HomeScreenMaterial(
    state: DashboardUiState,
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val settings = LocalAppearanceSettings.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scheme = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .drawBehind {
                if (!settings.glassEnabled) return@drawBehind
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(scheme.primary.copy(alpha = .15f), Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = size.width * .82f
                    ),
                    radius = size.width * .82f,
                    center = Offset(size.width, 0f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(scheme.tertiary.copy(alpha = .11f), Color.Transparent),
                        center = Offset(0f, size.height * .58f),
                        radius = size.width
                    ),
                    radius = size.width,
                    center = Offset(0f, size.height * .58f)
                )
            }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 154.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { MaterialPageHeader(actions.refresh) }
            item { MaterialHeroCard(state) }
            item { MaterialStorageCard(state) }
            item { MaterialCleanButton(state, actions) }
            item { MaterialServiceCard(state) }
            if (state.scanCompleted) item { MaterialScanResultCard(state, actions) }
            item { MaterialSectionTitle("QUICK ACTIONS", "快捷操作") }
            item { MaterialQuickActions(actions, onOpenClean) }
        }
    }
}

@Composable
private fun MaterialPageHeader(onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "SMART CLEAN",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp
            )
            Spacer(Modifier.height(5.dp))
            Text("白泽", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Material 3 清理概览 · v${BuildConfig.VERSION_NAME}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        FilledTonalIconButton(onClick = onRefresh, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun MaterialHeroCard(state: DashboardUiState) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val title = when {
        state.running -> "清理任务执行中"
        state.scanCompleted -> "扫描结果已就绪"
        state.ready -> "清理引擎已就绪"
        state.connected -> "Root 服务已连接"
        else -> "正在连接清理引擎"
    }

    Box(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .shadow(16.dp, MaterialTheme.shapes.extraLarge)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)))
            .border(1.dp, Color.White.copy(alpha = .22f), MaterialTheme.shapes.extraLarge)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .23f), Color.Transparent)
                    ),
                    size = size.copy(height = size.height * .43f)
                )
            }
            .padding(24.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.ready || state.scanCompleted) Color(0xFF78EEB8) else Color(0xFFFFD36F))
                )
                Spacer(Modifier.width(9.dp))
                Text(state.device, color = Color.White.copy(alpha = .88f), fontWeight = FontWeight.Bold)
                Text("  ·  ${state.android}", color = Color.White.copy(alpha = .66f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(25.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(7.dp))
            Text(
                state.taskPhase,
                color = Color.White.copy(alpha = .74f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(28.dp))
            Text("最近一次释放", color = Color.White.copy(alpha = .64f), fontSize = 12.sp)
            Text(
                Formatter.formatFileSize(context, state.lastReleased),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall
            )
        }
    }
}

@Composable
private fun MaterialStorageCard(state: DashboardUiState) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .92f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { state.storagePercent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 9.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Text(
                    "${(state.storagePercent * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text("可用空间", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Formatter.formatFileSize(context, state.storageFree),
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun MaterialCleanButton(state: DashboardUiState, actions: DashboardActions) {
    val enabled = state.running || state.ready || state.scanCompleted
    Button(
        onClick = when {
            state.running -> actions.stop
            state.scanCompleted -> actions.cleanScan
            else -> actions.clean
        },
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(70.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 10.dp)
    ) {
        Icon(
            when {
                state.running -> Icons.Rounded.Stop
                state.scanCompleted -> Icons.Rounded.DeleteSweep
                else -> Icons.Rounded.AutoAwesome
            },
            contentDescription = null
        )
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                state.running -> "安全停止任务"
                state.scanCompleted -> "按扫描结果清理"
                else -> "一键智能清理"
            },
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MaterialServiceCard(state: DashboardUiState) {
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (state.ready || state.scanCompleted) Color(0xFF2DBE87) else Color(0xFFF2A93B))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (state.scanCompleted) "扫描快照已就绪" else state.serviceText,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                when {
                    state.scanCompleted -> "可清理"
                    state.ready -> "运行正常"
                    else -> "未就绪"
                },
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MaterialScanResultCard(state: DashboardUiState, actions: DashboardActions) {
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(19.dp)) {
            Text("垃圾扫描完成", style = MaterialTheme.typography.titleLarge)
            Text(
                "发现 ${state.scanFiles} 项 · 空文件 ${state.scanEmptyFiles} · 空目录 ${state.scanEmptyDirs} · 碎片 ${state.scanFragments} · 异常 ${state.scanErrors}",
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .72f),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = actions.dismissScan,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text("关闭") }
                Button(onClick = actions.cleanScan, modifier = Modifier.weight(1.5f)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("立即清理")
                }
            }
        }
    }
}

@Composable
private fun MaterialSectionTitle(eyebrow: String, title: String) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
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
private fun MaterialQuickActions(
    actions: DashboardActions,
    onOpenClean: () -> Unit
) {
    val categories = listOf(
        MaterialCleanCategory(Icons.Rounded.Search, "垃圾扫描", "只扫描统计", actions.scan),
        MaterialCleanCategory(Icons.Rounded.InstallMobile, "安装包", "查找安装包", actions.apkScan),
        MaterialCleanCategory(Icons.Rounded.CleaningServices, "全部选项", "进入清理页", onOpenClean)
    )
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            categories.forEach { item ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable(onClick = item.onClick)
                        .padding(horizontal = 6.dp, vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
