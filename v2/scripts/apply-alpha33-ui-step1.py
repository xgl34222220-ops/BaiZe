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


activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
replace_once(
    activity,
    "import androidx.activity.compose.setContent",
    """import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xgl34222220.baize.ui.appearance.AppearanceViewModel""",
)
replace_once(
    activity,
    "class MiuixDashboardActivity : ComponentActivity() {\n    private val preferences",
    """class MiuixDashboardActivity : ComponentActivity() {
    private val appearanceViewModel: AppearanceViewModel by viewModels()
    private val preferences""",
)
replace_once(
    activity,
    """        setContent {
            BaiZeMiuixApp(
                state = dashboardState.value,""",
    """        setContent {
            val appearance = appearanceViewModel.settings.collectAsStateWithLifecycle().value
            BaiZeMiuixApp(
                state = dashboardState.value,""",
)
replace_once(
    activity,
    """                    reconnect = { reconnectService() },
                    crash = { showCrashDialog() }
                )
            )""",
    """                    reconnect = { reconnectService() },
                    crash = { showCrashDialog() }
                ),
                appearance = appearance
            )""",
)

app = "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
replace_once(
    app,
    "import androidx.compose.runtime.Composable",
    """import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider""",
)
replace_once(
    app,
    "import org.json.JSONObject",
    """import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.LocalAppearanceSettings
import io.github.xgl34222220.baize.ui.home.HomeRoute
import io.github.xgl34222220.baize.ui.theme.BaiZeTheme
import org.json.JSONObject""",
)
old_app = '''@Composable
fun BaiZeMiuixApp(state: DashboardUiState, scheduler: SchedulerUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (ThemeManager.currentMode(context)) {
        ThemeManager.MODE_LIGHT -> false
        ThemeManager.MODE_DARK -> true
        else -> systemDark
    }
    val amoled = dark && ThemeManager.isAmoledEnabled(context)
    val resolvedPrimary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF3975F4.toInt()))
    val resolvedSecondary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, 0xFF7658E8.toInt()))
    val resolvedTertiary = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, 0xFFFF91D0.toInt()))
    val resolvedSurface = if (amoled) Color(0xFF080808) else Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, if (dark) 0xFF191B24.toInt() else 0xFFFFFFFF.toInt()))
    val resolvedOnSurface = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, if (dark) 0xFFF0F1F8.toInt() else 0xFF151722.toInt()))
    val resolvedOnSurfaceVariant = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, if (dark) 0xFFBFC2D0.toInt() else 0xFF6D7080.toInt()))
    val colors = if (dark) {
        darkColorScheme(
            primary = resolvedPrimary,
            secondary = resolvedSecondary,
            tertiary = resolvedTertiary,
            background = if (amoled) Color.Black else Color(0xFF101117),
            surface = resolvedSurface,
            surfaceVariant = if (amoled) Color(0xFF101010) else Color(0xFF20232D),
            onSurface = resolvedOnSurface,
            onSurfaceVariant = resolvedOnSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = resolvedPrimary,
            secondary = resolvedSecondary,
            tertiary = resolvedTertiary,
            background = Color(0xFFF4F5FB),
            surface = resolvedSurface,
            onSurface = resolvedOnSurface,
            onSurfaceVariant = resolvedOnSurfaceVariant
        )
    }
    MaterialTheme(colorScheme = colors) {
        var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
        Box(modifier = Modifier.fillMaxSize()) {
            MiuiXBackdrop(dark, amoled)
            when (page) {
                BaiZePage.Home -> HomePage(state, actions)
                BaiZePage.Plan -> PlanPage(scheduler, actions)
                BaiZePage.Records -> RecordsPage(state, actions)
                BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
            }
            FloatingDock(
                selected = page,
                onSelected = { page = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}'''
new_app = '''@Composable
fun BaiZeMiuixApp(
    state: DashboardUiState,
    scheduler: SchedulerUiState,
    actions: DashboardActions,
    appearance: AppearanceSettings
) {
    BaiZeTheme(appearance) {
        CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            val amoled = dark && appearance.amoledBlack
            var page by rememberSaveable { mutableStateOf(BaiZePage.Home) }
            Box(modifier = Modifier.fillMaxSize()) {
                MiuiXBackdrop(dark, amoled)
                when (page) {
                    BaiZePage.Home -> HomeRoute(appearance.uiStyle, state, actions)
                    BaiZePage.Plan -> PlanPage(scheduler, actions)
                    BaiZePage.Records -> RecordsPage(state, actions)
                    BaiZePage.Settings -> SettingsPage(state, scheduler, actions)
                }
                FloatingDock(
                    selected = page,
                    onSelected = { page = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}'''
replace_once(app, old_app, new_app)
replace_once(
    app,
    "private fun HomePage(state: DashboardUiState, actions: DashboardActions)",
    "internal fun HomeScreenMiuix(state: DashboardUiState, actions: DashboardActions)",
)
replace_once(app, "原生清理引擎 · Alpha 32", "Miuix 清理概览 · Alpha 33")

layout = "v2/app/src/main/res/layout/activity_theme_settings.xml"
ui_style_card = '''        <com.google.android.material.card.MaterialCardView
            style="@style/Widget.BaiZe.GroupCard"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp">

            <LinearLayout
                android:id="@+id/uiStyleRow"
                android:layout_width="match_parent"
                android:layout_height="86dp"
                android:background="?attr/selectableItemBackground"
                android:clickable="true"
                android:focusable="true"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:paddingStart="20dp"
                android:paddingEnd="14dp">

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:fontFamily="sans-serif-medium"
                        android:text="界面风格"
                        android:textColor="?attr/colorOnSurface"
                        android:textSize="18sp" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="3dp"
                        android:text="Material 3 玻璃拟态 / Miuix HyperOS"
                        android:textColor="?attr/colorOnSurfaceVariant"
                        android:textSize="12sp" />
                </LinearLayout>

                <TextView
                    android:id="@+id/uiStyleValue"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Miuix"
                    android:textColor="?attr/colorOnSurfaceVariant"
                    android:textSize="16sp" />

                <io.github.xgl34222220.baize.ui.ChevronPairView
                    android:layout_width="24dp"
                    android:layout_height="34dp"
                    android:layout_marginStart="8dp" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

'''
anchor = '''        <com.google.android.material.card.MaterialCardView
            style="@style/Widget.BaiZe.GroupCard"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp">

            <LinearLayout
                android:id="@+id/modeRow"'''
replace_once(layout, anchor, ui_style_card + anchor)

replacements = {
    "v2/module/module.prop": [
        ("version=v2.0.0-alpha32", "version=v2.0.0-alpha33"),
        ("versionCode=21200", "versionCode=21300"),
    ],
    "v2/module/customize.sh": [("白泽 v2 Alpha 32", "白泽 v2 Alpha 33")],
    "v2/scripts/package-module.sh": [
        ("BaiZe-v2-Alpha32-Module.zip", "BaiZe-v2-Alpha33-Module.zip"),
        ("Alpha 32", "Alpha 33"),
    ],
    "v2/README.md": [
        ("# 白泽 v2 Alpha 32", "# 白泽 v2 Alpha 33"),
        ("当前开发分支：`v2-alpha32`。", "当前开发分支：`v2-alpha33-ui-step1`。"),
        ("BaiZe-v2-Alpha32-Module.zip", "BaiZe-v2-Alpha33-Module.zip"),
    ],
}
for path, pairs in replacements.items():
    for old, new in pairs:
        replace_once(path, old, new)

changes = Path("v2/ALPHA33-UI-STEP1.md")
if not changes.exists():
    changes.write_text(
        """# Alpha 33 双皮肤第一阶段

- 新增 `UiStyle.MATERIAL / UiStyle.MIUIX`，使用 Preferences DataStore 持久化并由 `AppearanceViewModel` 全局暴露。
- 增加 `LocalAppearanceSettings` 与 `BaiZeTheme`，Material、Miuix 共用种子色、三态明暗、纯黑、玻璃和取色风格。
- Material 主题通过 MaterialKolor 2.0.0 生成柔和、鲜艳、中性三套算法色板。
- 首页在 `HomeRoute` 按界面风格分流：原有首页作为 Miuix 实现，新建 Material 3 玻璃拟态首页。
- 主题设置新增“界面风格”选项，切换后主界面即时换皮。
- RootService、扫描、清理、历史数据和脚本业务逻辑均未修改。
""",
        encoding="utf-8",
    )
