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


app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
home_route = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/HomeRoute.kt"
material_home = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/home/material/HomeScreenMaterial.kt"

replace_once(
    home_route,
    "fun HomeRoute(style: UiStyle, state: DashboardUiState, actions: DashboardActions) {\n    when (style) {\n        UiStyle.MATERIAL -> HomeScreenMaterial(state, actions)\n        UiStyle.MIUIX -> HomeScreenMiuix(state, actions)\n    }\n}",
    "fun HomeRoute(\n    style: UiStyle,\n    state: DashboardUiState,\n    actions: DashboardActions,\n    onOpenClean: () -> Unit\n) {\n    when (style) {\n        UiStyle.MATERIAL -> HomeScreenMaterial(state, actions, onOpenClean)\n        UiStyle.MIUIX -> HomeScreenMiuix(state, actions, onOpenClean)\n    }\n}"
)

replace_once(
    app,
    "BaiZePage.Home -> HomeRoute(UiStyle.MATERIAL, state, actions)",
    "BaiZePage.Home -> HomeRoute(UiStyle.MATERIAL, state, actions) { page = BaiZePage.Clean }"
)
replace_once(
    app,
    "BaiZePage.Home -> HomeRoute(UiStyle.MIUIX, state, actions)",
    "BaiZePage.Home -> HomeRoute(UiStyle.MIUIX, state, actions) { page = BaiZePage.Clean }"
)
replace_once(
    app,
    "internal fun HomeScreenMiuix(state: DashboardUiState, actions: DashboardActions) {",
    "internal fun HomeScreenMiuix(\n    state: DashboardUiState,\n    actions: DashboardActions,\n    onOpenClean: () -> Unit\n) {"
)
replace_once(
    app,
    '"Miuix × Liquid Glass · Alpha 34",',
    '"Miuix × Liquid Glass · Alpha 36",'
)

old_miuix_section = '''        item {
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
        }'''
new_miuix_section = '''        item {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 5.dp)) {
                Text(
                    "QUICK ACTIONS",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text("快捷操作", fontSize = 27.sp, fontWeight = FontWeight.Black)
                Text(
                    "完整清理类别、开关与周期统一放在“清理”页",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        item {
            GlassSurface(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                shadow = 6,
                contentPadding = PaddingValues(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MiuixHomeQuickAction(
                        icon = Icons.Rounded.Search,
                        title = "垃圾扫描",
                        modifier = Modifier.weight(1f),
                        onClick = actions.scan
                    )
                    MiuixHomeQuickAction(
                        icon = Icons.Rounded.InstallMobile,
                        title = "安装包",
                        modifier = Modifier.weight(1f),
                        onClick = actions.apkScan
                    )
                    MiuixHomeQuickAction(
                        icon = Icons.Rounded.CleaningServices,
                        title = "全部选项",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenClean
                    )
                }
            }
        }'''
replace_once(app, old_miuix_section, new_miuix_section)

insert_anchor = "@Composable\nprivate fun StorageRing(progress: Float) {"
quick_action = '''@Composable
private fun MiuixHomeQuickAction(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
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
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

'''
replace_once(app, insert_anchor, quick_action + insert_anchor)

replace_once(
    material_home,
    "fun HomeScreenMaterial(state: DashboardUiState, actions: DashboardActions) {",
    "fun HomeScreenMaterial(\n    state: DashboardUiState,\n    actions: DashboardActions,\n    onOpenClean: () -> Unit\n) {"
)
replace_once(
    material_home,
    'Text(\n                "Material 3 清理概览 · Alpha 33",',
    'Text(\n                "Material 3 清理概览 · Alpha 36",'
)
replace_once(
    material_home,
    '            item { MaterialSectionTitle("CLEANING CATEGORIES", "更多清理") }\n            item { MaterialCategoryGroup(actions) }',
    '            item { MaterialSectionTitle("QUICK ACTIONS", "快捷操作") }\n            item { MaterialQuickActions(actions, onOpenClean) }'
)

old_material_group = '''@Composable
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
}'''
new_material_group = '''@Composable
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
}'''
replace_once(material_home, old_material_group, new_material_group)

for path in [
    "v2/app/build.gradle.kts",
    "v2/module/module.prop",
    "v2/scripts/package-module.sh",
    "v2/module/customize.sh",
]:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    text = text.replace("versionCode = 21500", "versionCode = 21600")
    text = text.replace('versionName = "2.0.0-alpha35"', 'versionName = "2.0.0-alpha36"')
    text = text.replace("version=v2.0.0-alpha35", "version=v2.0.0-alpha36")
    text = text.replace("versionCode=21500", "versionCode=21600")
    text = text.replace("Alpha35", "Alpha36")
    text = text.replace("Alpha 35", "Alpha 36")
    text = text.replace("alpha35", "alpha36")
    target.write_text(text, encoding="utf-8")
