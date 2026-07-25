from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old!r}")
    file.write_text(text.replace(old, new, 1))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    file = Path(path)
    text = file.read_text()
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{path}: expected one regex match, found {count}: {pattern!r}")
    file.write_text(updated)


def append_unique_rules(path: str, marker: str, rules: list[str]) -> None:
    file = Path(path)
    text = file.read_text().rstrip() + "\n"
    if marker in text:
        raise SystemExit(f"{path}: marker already exists: {marker}")
    existing = {
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    additions = [line for line in rules if line not in existing]
    if not additions:
        raise SystemExit(f"{path}: no new rules to append")
    file.write_text(text + "\n" + marker + "\n" + "\n".join(additions) + "\n")


# Remove persistent floating entries while retaining their backend diagnostics.
Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt").write_text('''package io.github.xgl34222220.baize.ui.settings

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.SchedulerUiState
import io.github.xgl34222220.baize.ui.appearance.AppearanceSettings
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.settings.material.SettingsScreenMaterial
import io.github.xgl34222220.baize.ui.settings.miuix.SettingsScreenMiuix

@Composable
fun SettingsRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    scheduler: SchedulerUiState,
    appearance: AppearanceSettings,
    dashboardActions: DashboardActions,
    onOpenDetails: () -> Unit
) {
    val state = dashboard.toSettingsUiState(scheduler, appearance)
    val actions = SettingsUiActions(
        onUpdateScheduler = dashboardActions.updateScheduler,
        onSaveScheduler = dashboardActions.saveScheduler,
        onSchedulerCommand = dashboardActions.schedulerCommand,
        onOpenAppearance = dashboardActions.theme,
        onOpenWhitelist = dashboardActions.whitelist,
        onReconnect = dashboardActions.reconnect,
        onOpenAudit = onOpenDetails,
        onOpenCrashDiagnostics = dashboardActions.crash
    )

    when (style) {
        UiStyle.MATERIAL -> SettingsScreenMaterial(state, actions)
        UiStyle.MIUIX -> SettingsScreenMiuix(state, actions)
    }
}
''')

Path("v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/HistoryRoute.kt").write_text('''package io.github.xgl34222220.baize.ui.history

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.history.material.HistoryScreenMaterial
import io.github.xgl34222220.baize.ui.history.miuix.HistoryScreenMiuix

@Composable
fun HistoryRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    dashboardActions: DashboardActions
) {
    val state = dashboard.toHistoryUiState()
    val actions = HistoryUiActions(
        onRefresh = dashboardActions.refresh,
        onClearHistory = dashboardActions.clearHistory,
        onReviewProtected = dashboardActions.reviewProtected
    )

    when (style) {
        UiStyle.MATERIAL -> HistoryScreenMaterial(state, actions)
        UiStyle.MIUIX -> HistoryScreenMiuix(state, actions)
    }
}
''')

# Clean page: tighter hierarchy, shorter save action, no duplicated service status row.
miuix_clean = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt"
replace_once(miuix_clean, "import androidx.compose.foundation.shape.CircleShape\n", "")
replace_once(miuix_clean, "contentPadding = PaddingValues(bottom = bottomInset + 100.dp)", "contentPadding = PaddingValues(bottom = bottomInset + 112.dp)")
replace_once(miuix_clean, "verticalArrangement = Arrangement.spacedBy(18.dp)", "verticalArrangement = Arrangement.spacedBy(16.dp)")
replace_once(miuix_clean, 'Text("清理计划", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)', 'Text("清理计划", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)')
replace_once(miuix_clean, '            "自动清理与文件归类",', '            "选择执行模式与清理项目",')
replace_once(miuix_clean, '''                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = !state.saving, onClick = actions.onSave),
                shape = RoundedCornerShape(18.dp),''', '''                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = !state.saving, onClick = actions.onSave),
                shape = RoundedCornerShape(20.dp),''')
replace_once(miuix_clean, 'if (state.saving) "正在保存…" else "保存自动任务设置"', 'if (state.saving) "正在保存…" else "保存设置"')
replace_once(miuix_clean, "        item { MiuixEngineStatus(state) }\n", "")
regex_once(
    miuix_clean,
    r"\n@Composable\nprivate fun MiuixEngineStatus\(state: CleanUiState\) \{.*?\n\}\n\nprivate fun categoryIcon",
    "\nprivate fun categoryIcon",
)

material_clean = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/material/CleanScreenMaterial.kt"
replace_once(material_clean, "import androidx.compose.foundation.shape.CircleShape\n", "")
replace_once(material_clean, "import io.github.xgl34222220.baize.ui.theme.BaiZeTokens\n", "")
replace_once(material_clean, "contentPadding = PaddingValues(bottom = bottomInset + 104.dp)", "contentPadding = PaddingValues(bottom = bottomInset + 112.dp)")
replace_once(material_clean, '            "设置自动清理和文件归类的执行周期",', '            "选择执行模式与清理项目",')
replace_once(material_clean, ".height(56.dp),", ".height(52.dp),")
replace_once(material_clean, 'Text(if (state.saving) "正在保存…" else "保存自动任务设置", fontWeight = FontWeight.Bold)', 'Text(if (state.saving) "正在保存…" else "保存设置", fontWeight = FontWeight.Bold)')
replace_once(material_clean, "        item { MaterialEngineStatus(state) }\n", "")
regex_once(
    material_clean,
    r"\n@Composable\nprivate fun MaterialEngineStatus\(state: CleanUiState\) \{.*?\n\}\n\nprivate fun categoryIcon",
    "\nprivate fun categoryIcon",
)

# Settings: no floating health entry; service state becomes a compact tonal pill.
miuix_settings = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/miuix/SettingsScreenMiuix.kt"
replace_once(miuix_settings, "verticalArrangement = Arrangement.spacedBy(18.dp)", "verticalArrangement = Arrangement.spacedBy(16.dp)")
replace_once(miuix_settings, '''                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))''', '''                    .height(52.dp)
                    .clip(RoundedCornerShape(20.dp))''')
replace_once(miuix_settings, "                shape = RoundedCornerShape(18.dp),\n                color = MaterialTheme.colorScheme.primary", "                shape = RoundedCornerShape(20.dp),\n                color = MaterialTheme.colorScheme.primary")
replace_once(miuix_settings, '''            Text(
                if (state.running) "执行中" else if (state.ready) "运行正常" else "自动恢复中",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )''', '''            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)
            ) {
                Text(
                    if (state.running) "执行中" else if (state.ready) "正常" else "恢复中",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }''')

material_settings = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/material/SettingsScreenMaterial.kt"
replace_once(material_settings, ".height(56.dp),", ".height(52.dp),")
replace_once(material_settings, '''            Text(
                if (state.running) "执行中" else if (state.ready) "运行正常" else "自动恢复中",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )''', '''            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    if (state.running) "执行中" else if (state.ready) "正常" else "恢复中",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium
                )
            }''')

# History: remove excess bottom space and normalize the latest result title instead of showing a long raw sentence.
miuix_history = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/miuix/HistoryScreenMiuix.kt"
replace_once(miuix_history, "contentPadding = PaddingValues(bottom = bottomInset + 136.dp)", "contentPadding = PaddingValues(bottom = bottomInset + 104.dp)")
replace_once(miuix_history, 'Text("清理记录", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)', 'Text("清理记录", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)')
replace_once(miuix_history, '''                    Text(
                        sanitizeText(state.latestResult).ifBlank {
                            if (state.hasCurrentResult) "任务已完成" else "暂无最近结果"
                        },''', '''                    Text(
                        currentResultTitle(state.latestResult, state.hasCurrentResult),''')
replace_once(miuix_history, '''@Composable
private fun MiuixCurrentResultGroup(state: HistoryUiState) {''', '''private fun currentResultTitle(raw: String, hasResult: Boolean): String {
    if (!hasResult) return "暂无最近结果"
    val value = sanitizeText(raw)
    return when {
        value.contains("扫描") -> "最近一次扫描已完成"
        value.contains("归类") -> "文件归类已完成"
        value.contains("清理") -> "最近一次清理已完成"
        else -> "最近一次任务已完成"
    }
}

@Composable
private fun MiuixCurrentResultGroup(state: HistoryUiState) {''')

material_history = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/history/material/HistoryScreenMaterial.kt"
replace_once(material_history, '''                Text(
                    state.latestResult.ifBlank { if (state.hasCurrentResult) "任务已完成" else "暂无最近结果" },''', '''                Text(
                    materialCurrentResultTitle(state.latestResult, state.hasCurrentResult),''')
replace_once(material_history, '''@Composable
private fun MaterialCurrentResult(state: HistoryUiState) {''', '''private fun materialCurrentResultTitle(raw: String, hasResult: Boolean): String {
    if (!hasResult) return "暂无最近结果"
    val value = raw.trim()
    return when {
        value.contains("扫描") -> "最近一次扫描已完成"
        value.contains("归类") -> "文件归类已完成"
        value.contains("清理") -> "最近一次清理已完成"
        else -> "最近一次任务已完成"
    }
}

@Composable
private fun MaterialCurrentResult(state: HistoryUiState) {''')

# Temperature remains telemetry only; it no longer blocks any of the three schedule modes.
scheduler = "v2/module/scheduler-v2.5.sh"
regex_once(
    scheduler,
    r"\nmax_battery_temp_value\(\) \{.*?\n\}\nvalid_interval_seconds",
    "\nvalid_interval_seconds",
)
regex_once(
    scheduler,
    r'''  maximum_temp=\$\(max_battery_temp_value\)\n  temperature=.*?\n  case "\$temperature" in .*?\n  if \[ "\$temperature" -gt 0 \] && \[ "\$temperature" -ge \$\(\(maximum_temp \* 10\)\) \]; then\n    temp_whole=.*?\n    SCHEDULE_REASON=.*?\n    return 1\n  fi\n''',
    "",
)

autopilot = "v2/module/autopilot-controller.sh"
replace_once(autopilot, '  [ "$HOT" = 1 ] && { GLOBAL_STATUS=waiting; GLOBAL_REASON=temperature_high; }\n', "")
regex_once(
    autopilot,
    r'''  if \[ "\$HOT" = 1 \]; then\n    hot_due=.*?\n    \[ "\$hot_due" -gt "\$desired_due" \] && desired_due=\$hot_due\n  fi\n''',
    "",
)
replace_once(autopilot, "temperature_hold=$HOT", "temperature_hold=0")

# Remove dead temperature-wait presentation paths.
activity = "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
replace_once(activity, '''                    val blocked = reason.contains("息屏") || reason.contains("充电") || reason.contains("电量") ||
                        reason.contains("温度") || reason.contains("过热") || reason.contains("空闲") ||
                        reason.contains("当前任务") || reason.contains("自动重试") || reason.contains("自动恢复")''', '''                    val blocked = reason.contains("息屏") || reason.contains("充电") || reason.contains("电量") ||
                        reason.contains("空闲") || reason.contains("当前任务") || reason.contains("自动重试") || reason.contains("自动恢复")''')
repository = "v2/app/src/main/java/io/github/xgl34222220/baize/root/SchedulerRepository.kt"
replace_once(repository, '            reason.contains("温度") || reason.contains("过热") -> "WAIT_TEMPERATURE"\n', "")
replace_once(repository, '        raw.contains("温度") || raw.contains("过热") -> "等待设备降温后执行"\n', "")
labels = "v2/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SchedulerText.kt"
replace_once(labels, '        value.contains("温度") || value.contains("过热") -> "等待设备降温后执行"\n', "")

# Curated regenerable rules. No chats, downloads, drafts, databases or user media.
app_marker = "# 2026.07.25：补充常见应用的可再生日志、崩溃记录、性能与 WebView 渲染缓存。"
app_rules = [
    "com.xingin.xhs|app_bugly|0", "com.xingin.xhs|app_crashrecord|0", "com.xingin.xhs|app_textures|0",
    "com.xingin.xhs|app_webview/Default/GPUCache|0", "com.xingin.xhs|app_webview/Default/Code Cache|0",
    "com.xingin.xhs|app_webview/Default/Service Worker/CacheStorage|0", "com.xingin.xhs|files/log|0",
    "com.xingin.xhs|files/logs|0", "com.xingin.xhs|files/xlog|0", "com.xingin.xhs|files/MiPushLog|0",
    "com.sina.weibo|app_bugly|0", "com.sina.weibo|app_crashrecord|0", "com.sina.weibo|app_textures|0",
    "com.sina.weibo|app_webview/Default/GPUCache|0", "com.sina.weibo|app_webview/Default/Code Cache|0",
    "com.sina.weibo|app_webview/Default/Service Worker/CacheStorage|0", "com.sina.weibo|files/log|0",
    "com.sina.weibo|files/logs|0", "com.sina.weibo|files/xlog|0", "com.sina.weibo|files/MiPushLog|0",
    "com.zhihu.android|app_bugly|0", "com.zhihu.android|app_crashrecord|0", "com.zhihu.android|app_textures|0",
    "com.zhihu.android|app_webview/Default/GPUCache|0", "com.zhihu.android|app_webview/Default/Code Cache|0",
    "com.zhihu.android|app_webview/Default/Service Worker/CacheStorage|0", "com.zhihu.android|files/log|0",
    "com.zhihu.android|files/logs|0", "com.zhihu.android|files/xlog|0", "com.zhihu.android|files/MiPushLog|0",
    "com.ss.android.ugc.aweme|app_webview/Default/GPUCache|0", "com.ss.android.ugc.aweme|app_webview/Default/Code Cache|0",
    "com.ss.android.ugc.aweme|app_webview/Default/Service Worker/CacheStorage|0", "com.ss.android.ugc.aweme|files/log|0",
    "com.ss.android.ugc.aweme|files/logs|0", "com.ss.android.ugc.aweme|files/xlog|0", "com.ss.android.ugc.aweme|files/MiPushLog|0",
    "com.ss.android.ugc.aweme|files/perf|0", "com.ss.android.ugc.aweme|files/perfUploading|0",
    "com.smile.gifmaker|app_bugly|0", "com.smile.gifmaker|app_crashrecord|0",
    "com.smile.gifmaker|app_webview/Default/GPUCache|0", "com.smile.gifmaker|app_webview/Default/Code Cache|0",
    "com.smile.gifmaker|app_webview/Default/Service Worker/CacheStorage|0", "com.smile.gifmaker|files/log|0",
    "com.smile.gifmaker|files/logs|0", "com.smile.gifmaker|files/xlog|0", "com.smile.gifmaker|files/MiPushLog|0",
    "com.smile.gifmaker|files/perf|0", "com.smile.gifmaker|files/perfUploading|0",
    "tv.danmaku.bili|app_crashrecord|0", "tv.danmaku.bili|app_webview/Default/GPUCache|0",
    "tv.danmaku.bili|app_webview/Default/Code Cache|0", "tv.danmaku.bili|app_webview/Default/Service Worker/CacheStorage|0",
    "tv.danmaku.bili|files/log|0", "tv.danmaku.bili|files/logs|0", "tv.danmaku.bili|files/xlog|0",
    "tv.danmaku.bili|files/MiPushLog|0", "tv.danmaku.bili|files/perf|0", "tv.danmaku.bili|files/perfUploading|0",
    "com.netease.cloudmusic|app_bugly|0", "com.netease.cloudmusic|app_crashrecord|0", "com.netease.cloudmusic|app_textures|0",
    "com.netease.cloudmusic|app_webview/Default/GPUCache|0", "com.netease.cloudmusic|app_webview/Default/Code Cache|0",
    "com.netease.cloudmusic|files/log|0", "com.netease.cloudmusic|files/logs|0", "com.netease.cloudmusic|files/xlog|0",
    "com.netease.cloudmusic|files/MiPushLog|0",
    "com.eg.android.AlipayGphone|app_bugly|0", "com.eg.android.AlipayGphone|app_crashrecord|0",
    "com.eg.android.AlipayGphone|app_webview/Default/GPUCache|0", "com.eg.android.AlipayGphone|app_webview/Default/Code Cache|0",
    "com.eg.android.AlipayGphone|app_webview/Default/Service Worker/CacheStorage|0", "com.eg.android.AlipayGphone|files/log|0",
    "com.eg.android.AlipayGphone|files/logs|0", "com.eg.android.AlipayGphone|files/tnetlogs|0", "com.eg.android.AlipayGphone|files/MiPushLog|0",
    "com.taobao.taobao|app_bugly|0", "com.taobao.taobao|app_crashrecord|0",
    "com.taobao.taobao|app_webview/Default/GPUCache|0", "com.taobao.taobao|app_webview/Default/Code Cache|0",
    "com.taobao.taobao|app_webview/Default/Service Worker/CacheStorage|0", "com.taobao.taobao|files/log|0",
    "com.taobao.taobao|files/logs|0", "com.taobao.taobao|files/tnetlogs|0", "com.taobao.taobao|files/MiPushLog|0",
    "com.jingdong.app.mall|app_bugly|0", "com.jingdong.app.mall|app_crashrecord|0",
    "com.jingdong.app.mall|app_webview/Default/GPUCache|0", "com.jingdong.app.mall|app_webview/Default/Code Cache|0",
    "com.jingdong.app.mall|app_webview/Default/Service Worker/CacheStorage|0", "com.jingdong.app.mall|files/log|0",
    "com.jingdong.app.mall|files/logs|0", "com.jingdong.app.mall|files/tnetlogs|0", "com.jingdong.app.mall|files/MiPushLog|0",
    "com.xunmeng.pinduoduo|app_bugly|0", "com.xunmeng.pinduoduo|app_crashrecord|0",
    "com.xunmeng.pinduoduo|app_webview/Default/Code Cache|0", "com.xunmeng.pinduoduo|app_webview/Default/Service Worker/CacheStorage|0",
    "com.xunmeng.pinduoduo|files/log|0", "com.xunmeng.pinduoduo|files/logs|0", "com.xunmeng.pinduoduo|files/tnetlogs|0",
    "com.xunmeng.pinduoduo|files/MiPushLog|0",
    "com.sankuai.meituan|app_bugly|0", "com.sankuai.meituan|app_crashrecord|0",
    "com.sankuai.meituan|app_webview/Default/GPUCache|0", "com.sankuai.meituan|app_webview/Default/Code Cache|0",
    "com.sankuai.meituan|app_webview/Default/Service Worker/CacheStorage|0", "com.sankuai.meituan|files/log|0",
    "com.sankuai.meituan|files/logs|0", "com.sankuai.meituan|files/tnetlogs|0", "com.sankuai.meituan|files/MiPushLog|0",
    "me.ele|app_bugly|0", "me.ele|app_crashrecord|0", "me.ele|app_textures|0",
    "me.ele|app_webview/Default/GPUCache|0", "me.ele|app_webview/Default/Code Cache|0",
    "me.ele|app_webview/Default/Service Worker/CacheStorage|0", "me.ele|files/log|0", "me.ele|files/logs|0",
    "me.ele|files/tnetlogs|0", "me.ele|files/MiPushLog|0",
    "com.autonavi.minimap|app_bugly|0", "com.autonavi.minimap|app_crashrecord|0", "com.autonavi.minimap|app_textures|0",
    "com.autonavi.minimap|app_webview/Default/GPUCache|0", "com.autonavi.minimap|app_webview/Default/Code Cache|0",
    "com.autonavi.minimap|files/log|0", "com.autonavi.minimap|files/logs|0", "com.autonavi.minimap|files/MiPushLog|0",
    "com.baidu.BaiduMap|app_bugly|0", "com.baidu.BaiduMap|app_crashrecord|0", "com.baidu.BaiduMap|app_textures|0",
    "com.baidu.BaiduMap|app_webview/Default/GPUCache|0", "com.baidu.BaiduMap|app_webview/Default/Code Cache|0",
    "com.baidu.BaiduMap|files/log|0", "com.baidu.BaiduMap|files/logs|0", "com.baidu.BaiduMap|files/MiPushLog|0",
    "com.android.chrome|app_chrome/Default/GPUCache|0", "com.android.chrome|app_chrome/Default/Code Cache|0",
    "com.android.chrome|app_chrome/Default/Service Worker/CacheStorage|0", "com.android.chrome|app_chrome/component_crx_cache|0",
    "com.xiaomi.market|app_bugly|0", "com.xiaomi.market|app_crashrecord|0", "com.xiaomi.market|files/log|0", "com.xiaomi.market|files/logs|0",
    "com.miui.gallery|app_bugly|0", "com.miui.gallery|app_crashrecord|0", "com.miui.gallery|files/log|0", "com.miui.gallery|files/logs|0",
    "com.miui.video|app_bugly|0", "com.miui.video|app_crashrecord|0", "com.miui.video|app_textures|0", "com.miui.video|files/log|0", "com.miui.video|files/logs|0",
]
append_unique_rules("config/app.rules", app_marker, app_rules)

external_marker = "# 2026.07.25：补充 Android/data 下明确的诊断、崩溃、性能与网络日志目录。"
external_rules = [
    "com.tencent.mm|files/log|0", "com.tencent.mm|files/logs|0", "com.tencent.mm|files/xlog|0", "com.tencent.mm|files/crash|0", "com.tencent.mm|files/MiPushLog|0",
    "com.tencent.mobileqq|files/log|0", "com.tencent.mobileqq|files/logs|0", "com.tencent.mobileqq|files/xlog|0", "com.tencent.mobileqq|files/crash|0", "com.tencent.mobileqq|files/MiPushLog|0",
    "com.xingin.xhs|files/log|0", "com.xingin.xhs|files/logs|0", "com.xingin.xhs|files/xlog|0", "com.xingin.xhs|files/crash|0", "com.xingin.xhs|files/MiPushLog|0", "com.xingin.xhs|files/perfUploading|0",
    "com.sina.weibo|files/log|0", "com.sina.weibo|files/logs|0", "com.sina.weibo|files/xlog|0", "com.sina.weibo|files/crash|0", "com.sina.weibo|files/MiPushLog|0", "com.sina.weibo|files/perfUploading|0",
    "com.zhihu.android|files/log|0", "com.zhihu.android|files/logs|0", "com.zhihu.android|files/xlog|0", "com.zhihu.android|files/crash|0", "com.zhihu.android|files/MiPushLog|0",
    "com.ss.android.ugc.aweme|files/logs|0", "com.ss.android.ugc.aweme|files/crash|0", "com.ss.android.ugc.aweme|files/perfUploading|0",
    "com.smile.gifmaker|files/logs|0", "com.smile.gifmaker|files/crash|0", "com.smile.gifmaker|files/MiPushLog|0", "com.smile.gifmaker|files/perf|0", "com.smile.gifmaker|files/perfUploading|0",
    "tv.danmaku.bili|files/log|0", "tv.danmaku.bili|files/logs|0", "tv.danmaku.bili|files/xlog|0", "tv.danmaku.bili|files/crash|0", "tv.danmaku.bili|files/MiPushLog|0", "tv.danmaku.bili|files/perfUploading|0",
    "com.netease.cloudmusic|files/log|0", "com.netease.cloudmusic|files/logs|0", "com.netease.cloudmusic|files/xlog|0", "com.netease.cloudmusic|files/crash|0", "com.netease.cloudmusic|files/MiPushLog|0",
    "com.eg.android.AlipayGphone|files/log|0", "com.eg.android.AlipayGphone|files/logs|0", "com.eg.android.AlipayGphone|files/xlog|0", "com.eg.android.AlipayGphone|files/crash|0", "com.eg.android.AlipayGphone|files/MiPushLog|0", "com.eg.android.AlipayGphone|files/tnetlogs|0",
    "com.taobao.taobao|files/log|0", "com.taobao.taobao|files/logs|0", "com.taobao.taobao|files/xlog|0", "com.taobao.taobao|files/crash|0", "com.taobao.taobao|files/MiPushLog|0", "com.taobao.taobao|files/tnetlogs|0",
    "com.jingdong.app.mall|files/log|0", "com.jingdong.app.mall|files/logs|0", "com.jingdong.app.mall|files/xlog|0", "com.jingdong.app.mall|files/crash|0", "com.jingdong.app.mall|files/MiPushLog|0", "com.jingdong.app.mall|files/tnetlogs|0",
    "com.xunmeng.pinduoduo|files/log|0", "com.xunmeng.pinduoduo|files/logs|0", "com.xunmeng.pinduoduo|files/xlog|0", "com.xunmeng.pinduoduo|files/crash|0", "com.xunmeng.pinduoduo|files/MiPushLog|0", "com.xunmeng.pinduoduo|files/tnetlogs|0",
    "com.sankuai.meituan|files/log|0", "com.sankuai.meituan|files/logs|0", "com.sankuai.meituan|files/xlog|0", "com.sankuai.meituan|files/crash|0", "com.sankuai.meituan|files/MiPushLog|0", "com.sankuai.meituan|files/tnetlogs|0",
    "me.ele|files/log|0", "me.ele|files/logs|0", "me.ele|files/xlog|0", "me.ele|files/crash|0", "me.ele|files/MiPushLog|0", "me.ele|files/tnetlogs|0",
    "com.autonavi.minimap|files/log|0", "com.autonavi.minimap|files/logs|0", "com.autonavi.minimap|files/xlog|0", "com.autonavi.minimap|files/crash|0", "com.autonavi.minimap|files/MiPushLog|0",
    "com.baidu.BaiduMap|files/log|0", "com.baidu.BaiduMap|files/logs|0", "com.baidu.BaiduMap|files/xlog|0", "com.baidu.BaiduMap|files/crash|0", "com.baidu.BaiduMap|files/MiPushLog|0",
    "com.xiaomi.market|files/log|0", "com.xiaomi.market|files/logs|0", "com.xiaomi.market|files/xlog|0", "com.xiaomi.market|files/crash|0",
    "com.miui.gallery|files/log|0", "com.miui.gallery|files/logs|0", "com.miui.gallery|files/xlog|0", "com.miui.gallery|files/crash|0", "com.miui.gallery|files/MiPushLog|0",
    "com.miui.video|files/log|0", "com.miui.video|files/logs|0", "com.miui.video|files/xlog|0", "com.miui.video|files/crash|0", "com.miui.video|files/MiPushLog|0",
    "com.tencent.wemeet.app|files/log|0", "com.tencent.wemeet.app|files/logs|0", "com.tencent.wemeet.app|files/xlog|0", "com.tencent.wemeet.app|files/crash|0", "com.tencent.wemeet.app|files/MiPushLog|0",
    "com.microsoft.office.outlook|files/log|0", "com.microsoft.office.outlook|files/logs|0", "com.microsoft.office.outlook|files/xlog|0", "com.microsoft.office.outlook|files/crash|0", "com.microsoft.office.outlook|files/MiPushLog|0",
]
append_unique_rules("config/external.rules", external_marker, external_rules)

# Focused regressions.
Path("v2/tests/test-scheduler-thermal-contract.sh").write_text(r'''#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP=${TMPDIR:-/tmp}/baize-scheduler-temperature-nonblocking-$$
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/module" "$TMP/state" "$TMP/bin"
cp "$ROOT/v2/module/scheduler-v2.5.sh" "$TMP/module/scheduler.sh"

cat >"$TMP/module/task-worker.sh" <<'EOF_WORKER'
#!/bin/sh
echo "$*" >>"${BAIZE_STATE_DIR}/worker-invocations.log"
exit 0
EOF_WORKER
chmod +x "$TMP/module/task-worker.sh"

cat >"$TMP/bin/dumpsys" <<'EOF_DUMPSYS'
#!/bin/sh
case "${1:-}" in
  power) echo 'mInteractive=false' ;;
  deviceidle) echo 'mState=IDLE' ;;
  battery)
    cat <<EOF_BATTERY
AC powered: false
USB powered: false
Wireless powered: false
status: 3
level: 80
temperature: ${BAIZE_TEST_TEMP:-430}
EOF_BATTERY
    ;;
esac
EOF_DUMPSYS
chmod +x "$TMP/bin/dumpsys"

cat >"$TMP/state/config.conf" <<'EOF_CONFIG'
enabled=1
schedule_mode=1
autopilot_enabled=0
daily_schedule_enabled=0
screen_off_only=0
charging_only=0
device_idle_only=0
min_battery=0
max_battery_temp=42
schedule_cache_enabled=1
schedule_cache_minutes=5
schedule_empty_enabled=0
schedule_rules_enabled=0
schedule_fragment_enabled=0
schedule_deep_enabled=0
schedule_organize_enabled=0
EOF_CONFIG

PATH="$TMP/bin:$PATH" BAIZE_TEST_TEMP=430 BAIZE_SKIP_BOOT_WAIT=1 BAIZE_SCHEDULER_ONCE=1 \
  BAIZE_MODULE_DIR="$TMP/module" BAIZE_STATE_DIR="$TMP/state" BAIZE_CONFIG_PATH="$TMP/state/config.conf" \
  sh "$TMP/module/scheduler.sh"
grep -q 'cache-auto scheduler:interval' "$TMP/state/worker-invocations.log"
! grep -q '温度' "$TMP/state/scheduler.env"

echo 'scheduler temperature nonblocking contract passed'
''')
Path("v2/tests/test-scheduler-thermal-contract.sh").chmod(0o755)

Path("v2/tests/test-curated-rules-contract.sh").write_text(r'''#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP="$ROOT/config/app.rules"
EXTERNAL="$ROOT/config/external.rules"

for expected in \
  'com.xingin.xhs|app_webview/Default/Code Cache|0' \
  'com.sina.weibo|files/xlog|0' \
  'com.zhihu.android|app_crashrecord|0' \
  'com.ss.android.ugc.aweme|files/perfUploading|0' \
  'tv.danmaku.bili|app_webview/Default/Service Worker/CacheStorage|0' \
  'com.eg.android.AlipayGphone|files/tnetlogs|0' \
  'com.taobao.taobao|app_webview/Default/Code Cache|0' \
  'com.jingdong.app.mall|files/logs|0' \
  'com.autonavi.minimap|app_crashrecord|0' \
  'com.android.chrome|app_chrome/Default/Code Cache|0'; do
  grep -Fqx "$expected" "$APP"
done

for expected in \
  'com.xingin.xhs|files/crash|0' \
  'com.sina.weibo|files/perfUploading|0' \
  'tv.danmaku.bili|files/xlog|0' \
  'com.eg.android.AlipayGphone|files/tnetlogs|0' \
  'com.xunmeng.pinduoduo|files/crash|0' \
  'com.miui.gallery|files/MiPushLog|0'; do
  grep -Fqx "$expected" "$EXTERNAL"
done

python3 - "$APP" "$EXTERNAL" <<'PY'
from pathlib import Path
import sys

markers = {
    'app.rules': '# 2026.07.25：补充常见应用的可再生日志、崩溃记录、性能与 WebView 渲染缓存。',
    'external.rules': '# 2026.07.25：补充 Android/data 下明确的诊断、崩溃、性能与网络日志目录。',
}
for raw in sys.argv[1:]:
    path = Path(raw)
    lines = path.read_text().splitlines()
    active = [line.strip() for line in lines if line.strip() and not line.lstrip().startswith('#')]
    if len(active) != len(set(active)):
        raise SystemExit(f'duplicate active rule in {path}')
    marker = markers[path.name]
    start = lines.index(marker) + 1
    forbidden = ('download', 'draft', 'database', 'databases', 'shared_prefs', 'dcim', 'pictures', 'movies', 'chat', 'message', 'attachment', 'voice')
    for line in lines[start:]:
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = line.split('|')
        if len(parts) != 3 or parts[2] != '0':
            raise SystemExit(f'invalid curated rule: {line}')
        relative = parts[1].lower()
        if any(token in relative for token in forbidden):
            raise SystemExit(f'user-data-like path rejected: {line}')
PY

echo 'curated rules contract passed'
''')
Path("v2/tests/test-curated-rules-contract.sh").chmod(0o755)

schedule_contract = "v2/tests/test-schedule-modes-contract.sh"
replace_once(schedule_contract, "grep -q 'maximum_temp=$(max_battery_temp_value)' \"$SCHEDULER\"", "! grep -q 'max_battery_temp_value' \"$SCHEDULER\"\n! grep -q '等待电池温度降低' \"$SCHEDULER\"")
replace_once(schedule_contract, "grep -q 'CleanScheduleMode.entries.forEach' \"$MIUIX\"\n", "grep -q 'CleanScheduleMode.entries.forEach' \"$MIUIX\"\n! grep -q 'MaterialEngineStatus' \"$MATERIAL\"\n! grep -q 'MiuixEngineStatus' \"$MIUIX\"\n")
replace_once(schedule_contract, 'bash "$ROOT/v2/tests/test-scheduler-thermal-contract.sh"\n', 'bash "$ROOT/v2/tests/test-scheduler-thermal-contract.sh"\nbash "$ROOT/v2/tests/test-curated-rules-contract.sh"\n')

autopilot_test = "v2/tests/test-autopilot-controller.sh"
replace_once(autopilot_test, '''# High battery temperature extends the next interval task beyond the thermal hold window.
run_controller 5000 50 0 430
[ "$(value "$STATE/autopilot-empty.env" temperature_hold)" = 1 ]
[ "$(sed -n '1p' "$STATE/last_empty_run.epoch")" = 2000 ]
[ "$(value "$STATE/autopilot.env" reason)" = temperature_high ]''', '''# Battery temperature remains diagnostic telemetry and no longer delays any schedule mode.
run_controller 5000 50 0 430
[ "$(value "$STATE/autopilot-empty.env" temperature_hold)" = 0 ]
[ "$(sed -n '1p' "$STATE/last_empty_run.epoch")" = 500 ]
[ "$(value "$STATE/autopilot.env" reason)" = normal ]''')

health_contract = "v2/tests/test-scheduler-health-contract.sh"
replace_once(health_contract, 'OVERLAY="$ROOT/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SchedulerHealthOverlay.kt"\n', 'OVERLAY="$ROOT/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SchedulerHealthOverlay.kt"\nSETTINGS_ROUTE="$ROOT/app/src/main/java/io/github/xgl34222220/baize/ui/settings/SettingsRoute.kt"\n')
replace_once(health_contract, "grep -q '不会修改任何定时周期' \"$OVERLAY\"\n", "grep -q '不会修改任何定时周期' \"$OVERLAY\"\n! grep -q 'SchedulerHealthOverlay(' \"$SETTINGS_ROUTE\"\n")

audit_contract = "v2/tests/test-audit-center-contract.sh"
replace_once(audit_contract, '''# Both themes reach the same audit activity from the history page.
grep -Fq 'AuditActivity::class.java' "$ROUTE"
grep -Fq 'Text("审计中心")' "$ROUTE"
grep -Fq '<activity android:name=".AuditActivity"' "$MANIFEST"''', '''# The audit backend stays available for compatibility, but no persistent floating entry covers the history page.
! grep -Fq 'AuditActivity::class.java' "$ROUTE"
! grep -Fq 'Text("审计中心")' "$ROUTE"
grep -Fq '<activity android:name=".AuditActivity"' "$MANIFEST"''')

# The helper is not part of the final pull request.
Path("v2/scripts/apply-ui-simplify-rules.py").unlink(missing_ok=True)
