#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch target not found: {target}\n--- expected ---\n{old}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


material = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt"
replace_once(
    material,
    '''private data class MaterialCleanCategory(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)

''',
    '''private data class MaterialQuickAction(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)

'''
)
replace_once(
    material,
    '''            item { MaterialSectionTitle("CLEANING CATEGORIES", "更多清理") }
            item { MaterialCategoryGroup(actions) }
''',
    '''            item { MaterialQuickActions(actions) }
'''
)
replace_once(material, 'Material 3 清理概览 · Alpha 33', 'Material 3 清理概览 · Alpha 36')
replace_once(material, 'state.running -> "安全停止任务"', 'state.running -> "停止清理"')
replace_once(
    material,
    '''@Composable
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
private fun MaterialCategoryGroup(actions: DashboardActions) {
    val categories = listOf(
        MaterialCleanCategory(Icons.Rounded.Search, "垃圾扫描", "只扫描并统计，确认后再清理", actions.scan),
        MaterialCleanCategory(Icons.Rounded.InstallMobile, "安装包扫描", "查找 APK、APKS、XAPK 与 APKM", actions.apkScan),
        MaterialCleanCategory(Icons.Rounded.DeleteSweep, "深度清理", "进一步扫描日志、规则垃圾与残留", actions.deep),
        MaterialCleanCategory(Icons.Rounded.FolderDelete, "卸载残留", "查找无主应用目录和遗留文件", actions.corpses),
        MaterialCleanCategory(Icons.Rounded.Rule, "清理明细", "查看各类垃圾与实际删除记录", actions.audit)
    )
    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            categories.forEachIndexed { index, item ->
                MaterialCategoryRow(item)
                if (index != categories.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialCategoryRow(item: MaterialCleanCategory) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = item.onClick).padding(horizontal = 17.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(
                item.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Rounded.CleaningServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
''',
    '''@Composable
private fun MaterialQuickActions(actions: DashboardActions) {
    val quickActions = listOf(
        MaterialQuickAction(
            icon = Icons.Rounded.Search,
            title = "垃圾扫描",
            description = "先统计，再决定是否清理",
            onClick = actions.scan
        ),
        MaterialQuickAction(
            icon = Icons.Rounded.InstallMobile,
            title = "安装包扫描",
            description = "查找 APK 与分包安装文件",
            onClick = actions.apkScan
        )
    )

    Column(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(
                "QUICK ACTIONS",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text("快捷清理", style = MaterialTheme.typography.headlineMedium)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            quickActions.forEach { item ->
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(144.dp)
                        .clickable(onClick = item.onClick),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(17.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    item.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                item.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Text(
            "完整类别、清理周期与高级任务已统一移到下方「清理」页面。",
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}
'''
)

miuix = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(miuix, 'Miuix × Liquid Glass · Alpha 34', 'Miuix × Liquid Glass · Alpha 36')
replace_once(
    miuix,
    '''        item {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 5.dp)) {
                Text(
                    "CLEANING CATEGORIES",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("更多清理", fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
        }
        item {
            GlassSurface(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                shadow = 6,
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                Column {
                    ToolRow(Icons.Rounded.Search, "垃圾扫描", "只查找并统计垃圾，不删除；完成后可一键清理", actions.scan)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.09f))
                    ToolRow(Icons.Rounded.InstallMobile, "安装包扫描", "查找 Download、QQ、微信等目录中的 APK/APKS/XAPK", actions.apkScan)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.09f))
                    ToolRow(Icons.Rounded.DeleteSweep, "深度清理", "扫描日志、临时文件与常见残留", actions.deep)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.09f))
                    ToolRow(Icons.Rounded.FolderDelete, "卸载残留", "扫描 data / obb / media 无主目录", actions.corpses)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.09f))
                    ToolRow(Icons.Rounded.Rule, "清理明细", "查看缓存、空项目、规则与碎片", actions.audit)
                }
            }
        }
''',
    '''        item {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 5.dp)) {
                Text(
                    "QUICK ACTIONS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("快捷清理", fontSize = 27.sp, fontWeight = FontWeight.Black)
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiuixHomeQuickAction(
                    icon = Icons.Rounded.Search,
                    title = "垃圾扫描",
                    subtitle = "先统计，再清理",
                    onClick = actions.scan,
                    modifier = Modifier.weight(1f)
                )
                MiuixHomeQuickAction(
                    icon = Icons.Rounded.InstallMobile,
                    title = "安装包扫描",
                    subtitle = "APK 与分包文件",
                    onClick = actions.apkScan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Text(
                "完整类别、执行周期和高级任务请进入底栏「清理」。",
                modifier = Modifier.padding(horizontal = 22.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
'''
)
replace_once(
    miuix,
    '''}
@Composable
private fun StorageRing(progress: Float) {
''',
    '''}

@Composable
private fun MiuixHomeQuickAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassSurface(
        modifier = modifier.height(132.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        shadow = 6,
        contentPadding = PaddingValues(17.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StorageRing(progress: Float) {
'''
)

replace_once(
    "v2/app/build.gradle.kts",
    '''        versionCode = 21400
        versionName = "2.0.0-alpha34"
''',
    '''        versionCode = 21600
        versionName = "2.0.0-alpha36"
'''
)
replace_once(
    "v2/module/module.prop",
    '''version=v2.0.0-alpha35
versionCode=21500
''',
    '''version=v2.0.0-alpha36
versionCode=21600
'''
)
replace_once(
    "v2/scripts/package-module.sh",
    'OUTPUT="$OUT/BaiZe-v2-Alpha35-Module.zip"',
    'OUTPUT="$OUT/BaiZe-v2-Alpha36-Module.zip"'
)
replace_once(
    "v2/scripts/package-module.sh",
    'echo "已生成 Alpha 35 清理类别双皮肤模块：$OUTPUT"',
    'echo "已生成 Alpha 36 精简首页与双皮肤清理页模块：$OUTPUT"'
)
replace_once(
    "v2/module/customize.sh",
    'ui_print "- 安装白泽 v2 Alpha 35 清理类别双皮肤版"',
    'ui_print "- 安装白泽 v2 Alpha 36 精简首页与双皮肤清理页版"'
)
