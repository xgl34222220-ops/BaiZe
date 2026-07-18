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


app_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
text = app_path.read_text(encoding="utf-8")

imports_anchor = "import io.github.xgl34222220.baize.ui.home.HomeRoute\n"
imports_new = """import io.github.xgl34222220.baize.ui.home.HomeRoute
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidDock
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidNavItem
import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidPrimaryButton
import io.github.xgl34222220.baize.ui.miuix.MiuixOverviewHero
"""
if "import io.github.xgl34222220.baize.ui.miuix.MiuixLiquidDock" not in text:
    if imports_anchor not in text:
        raise SystemExit("Miuix import anchor missing")
    text = text.replace(imports_anchor, imports_new, 1)

remember_anchor = "            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }\n"
remember_new = """            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
            val miuixNavItems = remember {
                BaiZePage.entries.map { MiuixLiquidNavItem(it.title, it.icon) }
            }
"""
if "val miuixNavItems = remember" not in text:
    if remember_anchor not in text:
        raise SystemExit("Navigation item anchor missing")
    text = text.replace(remember_anchor, remember_new, 1)

old_dock_call = """                    FloatingDock(
                        selected = page,
                        onSelected = { page = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )"""
new_dock_call = """                    MiuixLiquidDock(
                        selectedIndex = page.ordinal,
                        items = miuixNavItems,
                        onSelected = { index -> page = BaiZePage.entries[index] },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )"""
if new_dock_call not in text:
    if old_dock_call not in text:
        raise SystemExit("Old Miuix dock call missing")
    text = text.replace(old_dock_call, new_dock_call, 1)

home_start = text.index("@Composable\ninternal fun HomeScreenMiuix")
home_end = text.index("\n@Composable\nprivate fun StorageRing", home_start)
new_home = '''@Composable
internal fun HomeScreenMiuix(state: DashboardUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val positive = state.ready || state.scanCompleted
    val statusTitle = when {
        state.running -> "清理任务执行中"
        state.scanCompleted -> "扫描结果已就绪"
        state.ready -> "清理引擎已就绪"
        state.connected -> "清理引擎已连接"
        else -> "正在恢复清理引擎"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomInset + 154.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeader(
                "SMART CLEAN",
                "白泽",
                "Miuix × Liquid Glass · Alpha 34",
                actions.refresh
            )
        }
        item {
            MiuixOverviewHero(
                device = state.device,
                android = state.android,
                statusTitle = statusTitle,
                taskPhase = state.taskPhase,
                releasedText = Formatter.formatFileSize(context, state.lastReleased),
                positive = positive,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
        item {
            GlassSurface(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                shadow = 6,
                contentPadding = PaddingValues(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StorageRing(state.storagePercent)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "可用空间",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            Formatter.formatFileSize(context, state.storageFree),
                            fontSize = 31.sp,
                            lineHeight = 35.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "已用 ${Formatter.formatFileSize(context, state.storageUsed)} · 共 ${Formatter.formatFileSize(context, state.storageTotal)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        item {
            MiuixLiquidPrimaryButton(
                running = state.running,
                scanReady = state.scanCompleted,
                enabled = state.running || state.ready || state.scanCompleted,
                onClick = when {
                    state.running -> actions.stop
                    state.scanCompleted -> actions.cleanScan
                    else -> actions.clean
                },
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }
        item {
            StatusPill(
                ready = state.ready,
                scanReady = state.scanCompleted,
                text = if (state.scanCompleted && !state.ready) {
                    "扫描快照已就绪；清理时会自动恢复 Root 服务"
                } else {
                    state.serviceText
                }
            )
        }
        if (state.scanCompleted) {
            item { ScanResultCard(state, actions) }
        }
        item {
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
    }
}'''
text = text[:home_start] + new_home + text[home_end:]

old_dock_start = text.find("@Composable\nprivate fun FloatingDock(")
if old_dock_start >= 0:
    old_dock_end = text.index("\n@Composable\nprivate fun MaterialFloatingDock", old_dock_start)
    text = text[:old_dock_start] + text[old_dock_end + 1:]

app_path.write_text(text, encoding="utf-8")

replacements = {
    "v2/app/build.gradle.kts": [
        ("versionCode = 21300", "versionCode = 21400"),
        ('versionName = "2.0.0-alpha33"', 'versionName = "2.0.0-alpha34"'),
    ],
    "v2/module/module.prop": [
        ("version=v2.0.0-alpha33", "version=v2.0.0-alpha34"),
        ("versionCode=21300", "versionCode=21400"),
    ],
    "v2/module/customize.sh": [("白泽 v2 Alpha 33", "白泽 v2 Alpha 34")],
    "v2/scripts/package-module.sh": [
        ("BaiZe-v2-Alpha33-Module.zip", "BaiZe-v2-Alpha34-Module.zip"),
        ("Alpha 33", "Alpha 34"),
    ],
}
for path, pairs in replacements.items():
    for old, new in pairs:
        replace_once(path, old, new)

notes = Path("v2/ALPHA34-MIUIX-LIQUID.md")
if not notes.exists():
    notes.write_text(
        """# Alpha 34 Miuix × Liquid Glass

- Miuix 底部导航固定为四等分的“上图标、下文字”，所有标签始终显示。
- 选中项使用横向滑动的液态胶囊，不再改变导航项宽度或把文字挤到图标旁边。
- 底栏增加半透明玻璃、高光边缘、主题色折射和 AMOLED 烟熏黑适配。
- Miuix 首页主卡改为大号核心数字与紧凑状态区，减少旧版厚重渐变和平均化卡片层级。
- 主清理按钮采用液态高光；扫描快照存在时直接执行快照清理。
- Material 皮肤、RootService、扫描、清理、历史记录和 cleaner.sh 均未修改。
""",
        encoding="utf-8",
    )
