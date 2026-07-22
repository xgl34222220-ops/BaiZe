from pathlib import Path

ROOT = Path("v2/app/src/main/java/io/github/xgl34222220/baize")
APP_FILE = ROOT / "BaiZeMiuixApp.kt"
ROUTE_FILE = ROOT / "ui/clean/CleanRoute.kt"
MIUIX_FILE = ROOT / "ui/clean/miuix/CleanScreenMiuix.kt"
MATERIAL_FILE = ROOT / "ui/clean/material/CleanScreenMaterial.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing source marker: {label}")
    return text.replace(old, new, 1)


def replace_block(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_index = text.find(start)
    end_index = text.find(end, start_index + len(start))
    if start_index < 0 or end_index < 0:
        raise SystemExit(f"missing block marker: {label}")
    return text[:start_index] + replacement.rstrip() + "\n" + text[end_index:]


app = APP_FILE.read_text()
if "expandedCleanCategory" not in app:
    app = replace_once(
        app,
        "            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }\n",
        "            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }\n"
        "            var expandedCleanCategory by rememberSaveable { mutableStateOf(\"\") }\n",
        "top-level clean expansion state",
    )
    app = replace_once(
        app,
        "BaiZePage.Clean -> CleanRoute(UiStyle.MATERIAL, state.forCleanPage(), scheduler, actions)",
        """BaiZePage.Clean -> CleanRoute(
                            style = UiStyle.MATERIAL,
                            dashboard = state.forCleanPage(),
                            scheduler = scheduler,
                            dashboardActions = actions,
                            expandedCategory = expandedCleanCategory,
                            onExpandedCategoryChanged = { expandedCleanCategory = it }
                        )""",
        "Material clean route",
    )
    app = replace_once(
        app,
        "BaiZePage.Clean -> CleanRoute(UiStyle.MIUIX, state.forCleanPage(), scheduler, actions)",
        """BaiZePage.Clean -> CleanRoute(
                                style = UiStyle.MIUIX,
                                dashboard = state.forCleanPage(),
                                scheduler = scheduler,
                                dashboardActions = actions,
                                expandedCategory = expandedCleanCategory,
                                onExpandedCategoryChanged = { expandedCleanCategory = it }
                            )""",
        "MIUIx clean route",
    )
    APP_FILE.write_text(app)

route = ROUTE_FILE.read_text()
if "expandedCategory: String" not in route:
    route = replace_once(
        route,
        """fun CleanRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    dashboardActions: DashboardActions
) {""",
        """fun CleanRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    dashboardActions: DashboardActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {""",
        "CleanRoute signature",
    )
    route = replace_once(
        route,
        """    when (style) {
        UiStyle.MATERIAL -> CleanScreenMaterial(state, actions)
        UiStyle.MIUIX -> CleanScreenMiuix(state, actions)
    }""",
        """    when (style) {
        UiStyle.MATERIAL -> CleanScreenMaterial(
            state = state,
            actions = actions,
            expandedCategory = expandedCategory,
            onExpandedCategoryChanged = onExpandedCategoryChanged
        )
        UiStyle.MIUIX -> CleanScreenMiuix(
            state = state,
            actions = actions,
            expandedCategory = expandedCategory,
            onExpandedCategoryChanged = onExpandedCategoryChanged
        )
    }""",
        "CleanRoute screen dispatch",
    )
    ROUTE_FILE.write_text(route)

miuix = MIUIX_FILE.read_text()
if "onToggleExpanded: () -> Unit" not in miuix:
    miuix = replace_once(
        miuix,
        "import androidx.compose.material.icons.rounded.Edit\n",
        "import androidx.compose.material.icons.rounded.Edit\n"
        "import androidx.compose.material.icons.rounded.ExpandLess\n"
        "import androidx.compose.material.icons.rounded.ExpandMore\n",
        "MIUIx expand icons",
    )
    miuix = replace_once(
        miuix,
        """fun CleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions
) {""",
        """fun CleanScreenMiuix(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {""",
        "MIUIx screen signature",
    )
    miuix = replace_once(
        miuix,
        "                    MiuixCategoryRow(item, actions, state.dailyEnabled)\n",
        """                    MiuixCategoryRow(
                        item = item,
                        actions = actions,
                        dailyMode = state.dailyEnabled,
                        expanded = expandedCategory == item.id.name,
                        onToggleExpanded = {
                            onExpandedCategoryChanged(if (expandedCategory == item.id.name) "" else item.id.name)
                        }
                    )
""",
        "MIUIx category call",
    )
    miuix_block = r'''@Composable
private fun MiuixCategoryRow(
    item: CleanCategoryUiItem,
    actions: CleanUiActions,
    dailyMode: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    var showIntervalDialog by remember(item.id, item.intervalMinutes) { mutableStateOf(false) }
    if (showIntervalDialog) {
        IntValueDialog(
            title = "${item.title}执行周期",
            description = "输入 5–43200 分钟。支持 30 分钟、1 小时以及任意整数周期。",
            initialValue = item.intervalMinutes,
            range = 5..43_200,
            suffix = "分钟",
            onDismiss = { showIntervalDialog = false },
            onConfirm = { actions.onCategoryIntervalChanged(item.id, it) }
        )
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIconTile(categoryIcon(item.id))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    item.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            MiuixSuperSwitch(
                checked = item.enabled,
                onCheckedChange = { actions.onCategoryEnabledChanged(item.id, it) }
            )
        }
        if (item.enabled) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .padding(start = 58.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f))
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (dailyMode) "每日模式已启用"
                        else "每 ${formatMinutes(item.intervalMinutes)}执行一次",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (dailyMode) "关闭每日模式后恢复当前独立周期"
                        else if (expanded) "点击收起周期设置" else "点击展开周期设置",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
                Text(
                    if (expanded) "收起设置" else "展开设置",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起设置" else "展开设置",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.padding(start = 58.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    listOf(30, 60, 360, 1_440).forEach { minutes ->
                        val active = item.intervalMinutes == minutes
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = .16f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)
                                )
                                .clickable { actions.onCategoryIntervalChanged(item.id, minutes) }
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        ) {
                            Text(
                                formatMinutes(minutes),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .padding(start = 58.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .09f))
                        .clickable(onClick = { showIntervalDialog = true })
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "精确周期：${formatMinutes(item.intervalMinutes)}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (dailyMode) "每日模式开启时暂不使用，关闭后恢复"
                            else "每 ${formatMinutes(item.intervalMinutes)}执行一次",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                    }
                    Text("修改", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
'''
    miuix = replace_block(
        miuix,
        "@Composable\nprivate fun MiuixCategoryRow(",
        "\n@Composable\nprivate fun MiuixScheduleValueButton(",
        miuix_block,
        "MIUIx category function",
    )
    miuix = miuix.replace('"AUTOMATIC CLEANING"', '"自动执行"')
    miuix = miuix.replace('"MANUAL TOOLS"', '"手动工具"')
    miuix = miuix.replace('"CLEANING CATEGORIES"', '"清理分类"')
    MIUIX_FILE.write_text(miuix)

material = MATERIAL_FILE.read_text()
if "onToggleExpanded: () -> Unit" not in material:
    material = replace_once(
        material,
        "import androidx.compose.material.icons.rounded.Edit\n",
        "import androidx.compose.material.icons.rounded.Edit\n"
        "import androidx.compose.material.icons.rounded.ExpandLess\n"
        "import androidx.compose.material.icons.rounded.ExpandMore\n",
        "Material expand icons",
    )
    material = replace_once(
        material,
        """fun CleanScreenMaterial(
    state: CleanUiState,
    actions: CleanUiActions
) {""",
        """fun CleanScreenMaterial(
    state: CleanUiState,
    actions: CleanUiActions,
    expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit
) {""",
        "Material screen signature",
    )
    material = replace_once(
        material,
        "            MaterialCategoryCard(item, actions, state.dailyEnabled)\n",
        """            MaterialCategoryCard(
                item = item,
                actions = actions,
                dailyMode = state.dailyEnabled,
                expanded = expandedCategory == item.id.name,
                onToggleExpanded = {
                    onExpandedCategoryChanged(if (expandedCategory == item.id.name) "" else item.id.name)
                }
            )
""",
        "Material category call",
    )
    material_block = r'''@Composable
private fun MaterialCategoryCard(
    item: CleanCategoryUiItem,
    actions: CleanUiActions,
    dailyMode: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    var showIntervalDialog by remember(item.id, item.intervalMinutes) { mutableStateOf(false) }
    if (showIntervalDialog) {
        IntValueDialog(
            title = "${item.title}执行周期",
            description = "输入 5–43200 分钟，支持 30 分钟、1 小时或任意自定义周期。",
            initialValue = item.intervalMinutes,
            range = 5..43_200,
            suffix = "分钟",
            onDismiss = { showIntervalDialog = false },
            onConfirm = { actions.onCategoryIntervalChanged(item.id, it) }
        )
    }

    Card(
        modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            categoryIcon(item.id),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { actions.onCategoryEnabledChanged(item.id, it) }
                )
            }
            if (item.enabled) {
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                FilledTonalButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(
                            if (dailyMode) "每日模式已启用"
                            else "每 ${formatMinutes(item.intervalMinutes)}执行一次",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            if (dailyMode) "关闭每日模式后恢复当前独立周期"
                            else if (expanded) "周期设置已展开" else "周期设置已收起",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    Text(if (expanded) "收起设置" else "展开设置", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(5.dp))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起设置" else "展开设置",
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "执行周期：${formatMinutes(item.intervalMinutes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (dailyMode) {
                        Text(
                            "每日模式开启时暂不使用，关闭每日模式后自动恢复",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 360, 1_440).forEach { minutes ->
                            FilledTonalButton(
                                onClick = { actions.onCategoryIntervalChanged(item.id, minutes) },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (item.intervalMinutes == minutes) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(formatMinutes(minutes), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(
                        onClick = { showIntervalDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("修改精确周期", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
'''
    material = replace_block(
        material,
        "@Composable\nprivate fun MaterialCategoryCard(",
        "\n@Composable\nprivate fun MaterialSectionHeader(",
        material_block,
        "Material category function",
    )
    material = material.replace('"AUTOMATIC CLEANING"', '"自动执行"')
    material = material.replace('"MANUAL TOOLS"', '"手动工具"')
    material = material.replace('"CLEANING CATEGORIES"', '"清理分类"')
    MATERIAL_FILE.write_text(material)
