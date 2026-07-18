#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"patch target not found in {path}:\n{old[:240]}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_shell_function(text: str, name: str, replacement: str) -> str:
    marker = f"{name}() {{"
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"shell function not found: {name}")
    next_match = re.search(r"\n[A-Za-z_][A-Za-z0-9_]*\(\) \{", text[start + len(marker):])
    end = len(text) if next_match is None else start + len(marker) + next_match.start() + 1
    return text[:start] + replacement.rstrip() + "\n\n" + text[end:]


def replace_kotlin_method(text: str, name: str, replacement: str) -> str:
    pattern = re.compile(
        rf"\n    private fun {re.escape(name)}\([^\n]*\) \{{.*?(?=\n    private fun |\n    override fun |\n    companion object)",
        re.S,
    )
    match = pattern.search(text)
    if not match:
        raise SystemExit(f"Kotlin method not found: {name}")
    return text[:match.start()] + "\n" + replacement.rstrip() + "\n" + text[match.end():]


# ---------------------------------------------------------------------------
# Cleaner: keep the existing engine, but split cache work by package so the UI
# can show the current app and exact per-app results.
# ---------------------------------------------------------------------------
cleaner_path = Path("cleaner.sh")
cleaner = cleaner_path.read_text(encoding="utf-8")

cleaner = cleaner.replace(
    'LATEST_REPORT="$REPORT_DIR/latest.tsv"\n',
    'LATEST_REPORT="$REPORT_DIR/latest.tsv"\nAPP_DETAILS="$REPORT_DIR/apps-latest.tsv"\n',
    1,
)
cleaner = cleaner.replace(
    "printf 'action\\trisk\\tcategory\\titems\\tbytes\\tpath\\n' >\"$REPORT_FILE\"\n",
    "printf 'action\\trisk\\tcategory\\titems\\tbytes\\tpath\\n' >\"$REPORT_FILE\"\nprintf 'package\\tcategory\\tfiles\\tbytes\\n' >\"$APP_DETAILS\"\n",
    1,
)

report_anchor = '''report_line() {
  action=$(sanitize_report_field "$1")
  risk=$(sanitize_report_field "$2")
  category=$(sanitize_report_field "$3")
  items=$4
  bytes=$5
  path=$(sanitize_report_field "$6")
  printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "$action" "$risk" "$category" "$items" "$bytes" "$path" >>"$REPORT_FILE"
}
'''
report_helpers = report_anchor + r'''
valid_package_name() {
  printf '%s' "$1" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]*\.[A-Za-z0-9._-]+$'
}

package_from_target() {
  target=${1%/}
  case "$target" in
    /data/user/[0-9]*/*/*)
      rest=${target#/data/user/}; rest=${rest#*/}; printf '%s\n' "${rest%%/*}"; return 0 ;;
    /data/user_de/[0-9]*/*/*)
      rest=${target#/data/user_de/}; rest=${rest#*/}; printf '%s\n' "${rest%%/*}"; return 0 ;;
    /data/media/[0-9]*/Android/data/*/*)
      rest=${target#*/Android/data/}; printf '%s\n' "${rest%%/*}"; return 0 ;;
  esac
  return 1
}

package_for_detail() {
  target=$1
  category=$2
  candidate=""
  case "$category" in
    应用扩展规则:*|外部应用扩展规则:*|WebView缓存:*) candidate=${category##*:} ;;
  esac
  if valid_package_name "$candidate"; then
    printf '%s\n' "$candidate"
    return 0
  fi
  candidate=$(package_from_target "$target" 2>/dev/null)
  valid_package_name "$candidate" || return 1
  printf '%s\n' "$candidate"
}

append_app_detail() {
  package=$1
  category=$2
  files=$3
  bytes=$4
  valid_package_name "$package" || return 0
  case "$files" in ''|*[!0-9]*) files=0 ;; esac
  case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
  [ "$files" -gt 0 ] || [ "$bytes" -gt 0 ] || return 0
  package=$(sanitize_report_field "$package")
  category=$(sanitize_report_field "$category")
  printf '%s\t%s\t%s\t%s\n' "$package" "$category" "$files" "$bytes" >>"$APP_DETAILS"
}

record_app_detail_for() {
  target=$1
  category=$2
  files=$3
  bytes=$4
  package=$(package_for_detail "$target" "$category" 2>/dev/null) || return 0
  append_app_detail "$package" "$category" "$files" "$bytes"
}
'''
if report_anchor not in cleaner:
    raise SystemExit("report helper insertion point not found")
cleaner = cleaner.replace(report_anchor, report_helpers, 1)

new_process_cache = r'''process_cache_candidates() {
  list=$1
  CATEGORY=$2
  app_package=${3:-}
  app_done=${4:-0}
  app_total=${5:-0}
  filter_whitelist_list "$list"
  count=$(count_nul "$list")
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }

  if [ -n "$app_package" ]; then
    if [ "$MODE" = "clean" ]; then
      set_phase "正在清理应用缓存" "$app_done" "$app_total" "$app_package"
    else
      set_phase "正在统计应用缓存" "$app_done" "$app_total" "$app_package"
    fi
  fi

  estimated=$(bytes_from_list "$list")
  case "$estimated" in ''|*[!0-9]*) estimated=0 ;; esac
  if [ "$MODE" = "clean" ]; then
    err_file="$TMP_DIR/rm-cache.err.$LIST_SEQ"
    xargs -0 -n 200 rm -f -- <"$list" 2>"$err_file"
    remaining="$list.remaining"
    existing_files_to_list "$list" "$remaining"
    batch_actuals "$list" "$remaining" "$estimated"
    [ "$REMAINING_COUNT" -gt 0 ] && ERRORS=$((ERRORS + REMAINING_COUNT))
    reason=$(tail -n 1 "$err_file" 2>/dev/null)
    [ "$REMAINING_COUNT" -gt 0 ] && log_line "[部分未清理][$CATEGORY] ${reason:-系统拒绝删除部分文件}"
    log_line "[应用清理][$app_package][$CATEGORY] $ACTUAL_COUNT 个缓存文件，$ACTUAL_BYTES bytes，未清理 $REMAINING_COUNT 个"
    report_line cleaned low "$CATEGORY:$app_package" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$app_package"
    [ "$REMAINING_COUNT" -gt 0 ] && report_line failed low "$CATEGORY:$app_package" "$REMAINING_COUNT" "$REMAINING_BYTES" "$app_package"
    append_app_detail "$app_package" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES"
    FILES=$((FILES + ACTUAL_COUNT))
    add_bytes "$ACTUAL_BYTES"
    rm -f "$remaining" "$err_file"
  else
    log_line "[应用扫描][$app_package][$CATEGORY] $count 个缓存文件，$estimated bytes"
    report_line candidate low "$CATEGORY:$app_package" "$count" "$estimated" "$app_package"
    append_app_detail "$app_package" "$CATEGORY" "$count" "$estimated"
    FILES=$((FILES + count))
    add_bytes "$estimated"
  fi
  rm -f "$list"
  return 0
}'''
cleaner = replace_shell_function(cleaner, "process_cache_candidates", new_process_cache)

new_scan_internal = r'''scan_cache_roots() {
  roots=$1
  days=$2
  category=$3
  packages="$TMP_DIR/cache-packages.internal"
  : >"$packages"

  for root in $roots; do
    [ -d "$root" ] || continue
    for user_root in "$root"/[0-9]*; do
      [ -d "$user_root" ] || continue
      for app_dir in "$user_root"/*; do
        [ -d "$app_dir" ] || continue
        package=${app_dir##*/}
        valid_package_name "$package" || continue
        { [ -d "$app_dir/cache" ] || [ -d "$app_dir/code_cache" ]; } || continue
        grep -Fqx -- "$package" "$packages" 2>/dev/null || printf '%s\n' "$package" >>"$packages"
      done
    done
  done

  total=$(wc -l <"$packages" 2>/dev/null | tr -d ' ')
  case "$total" in ''|*[!0-9]*) total=0 ;; esac
  done_count=0
  stage_started=$(date +%s)
  while IFS= read -r package || [ -n "$package" ]; do
    valid_package_name "$package" || continue
    done_count=$((done_count + 1))
    set --
    for root in $roots; do
      for user_root in "$root"/[0-9]*; do
        [ -d "$user_root/$package/cache" ] && [ ! -L "$user_root/$package/cache" ] && set -- "$@" "$user_root/$package/cache"
        [ -d "$user_root/$package/code_cache" ] && [ ! -L "$user_root/$package/code_cache" ] && set -- "$@" "$user_root/$package/code_cache"
      done
    done
    [ "$#" -gt 0 ] || continue
    should_stop && { rm -f "$packages"; return 9; }
    set_phase "正在扫描应用缓存" "$done_count" "$total" "$package"
    LIST_SEQ=$((LIST_SEQ + 1))
    candidates="$TMP_DIR/cache-internal.$LIST_SEQ.nul"
    run_cache_find 10 "$days" "$candidates" "$@"
    code=$?
    if [ "$code" -eq 0 ]; then
      process_cache_candidates "$candidates" "$category" "$package" "$done_count" "$total"
    else
      CACHE_SLOW_DIRS=$((CACHE_SLOW_DIRS + 1))
      PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
      report_line protected timeout "$category:$package" 1 0 "$package"
      rm -f "$candidates"
    fi
    now=$(date +%s)
    if [ $((now - stage_started)) -ge 240 ]; then
      CACHE_TRUNCATED=1
      log_line "[应用缓存提前结束] 已达到 240 秒上限"
      break
    fi
  done <"$packages"
  rm -f "$packages"
  return 0
}'''
cleaner = replace_shell_function(cleaner, "scan_cache_roots", new_scan_internal)

new_scan_external = r'''scan_external_cache() {
  days=$1
  packages="$TMP_DIR/cache-packages.external"
  : >"$packages"
  for app_dir in /data/media/[0-9]*/Android/data/*; do
    [ -d "$app_dir" ] || continue
    package=${app_dir##*/}
    valid_package_name "$package" || continue
    { [ -d "$app_dir/cache" ] || [ -d "$app_dir/code_cache" ]; } || continue
    grep -Fqx -- "$package" "$packages" 2>/dev/null || printf '%s\n' "$package" >>"$packages"
  done

  total=$(wc -l <"$packages" 2>/dev/null | tr -d ' ')
  case "$total" in ''|*[!0-9]*) total=0 ;; esac
  done_count=0
  stage_started=$(date +%s)
  while IFS= read -r package || [ -n "$package" ]; do
    valid_package_name "$package" || continue
    done_count=$((done_count + 1))
    set --
    for app_dir in /data/media/[0-9]*/Android/data/"$package"; do
      [ -d "$app_dir/cache" ] && [ ! -L "$app_dir/cache" ] && set -- "$@" "$app_dir/cache"
      [ -d "$app_dir/code_cache" ] && [ ! -L "$app_dir/code_cache" ] && set -- "$@" "$app_dir/code_cache"
    done
    [ "$#" -gt 0 ] || continue
    should_stop && { rm -f "$packages"; return 9; }
    set_phase "正在扫描外部应用缓存" "$done_count" "$total" "$package"
    LIST_SEQ=$((LIST_SEQ + 1))
    candidates="$TMP_DIR/cache-external.$LIST_SEQ.nul"
    run_cache_find 10 "$days" "$candidates" "$@"
    code=$?
    if [ "$code" -eq 0 ]; then
      process_cache_candidates "$candidates" "外部应用缓存" "$package" "$done_count" "$total"
    else
      CACHE_SLOW_DIRS=$((CACHE_SLOW_DIRS + 1))
      PROTECTED_ITEMS=$((PROTECTED_ITEMS + 1))
      report_line protected timeout "外部应用缓存:$package" 1 0 "$package"
      rm -f "$candidates"
    fi
    now=$(date +%s)
    if [ $((now - stage_started)) -ge 180 ]; then
      CACHE_TRUNCATED=1
      log_line "[外部缓存提前结束] 已达到 180 秒上限"
      break
    fi
  done <"$packages"
  rm -f "$packages"
  return 0
}'''
cleaner = replace_shell_function(cleaner, "scan_external_cache", new_scan_external)

# Show the current package while app-specific rule/WebView directories are processed.
cleaner = cleaner.replace(
    '''  CATEGORY=$3
  [ -d "$dir" ] || return 0''',
    '''  CATEGORY=$3
  detail_package=$(package_for_detail "$dir" "$CATEGORY" 2>/dev/null)
  [ -n "$detail_package" ] && set_phase "正在处理应用垃圾" 0 0 "$detail_package"
  [ -d "$dir" ] || return 0''',
    1,
)
cleaner = cleaner.replace(
    '      report_line cleaned low "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$dir"\n',
    '      report_line cleaned low "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$dir"\n      record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES"\n',
    1,
)
cleaner = cleaner.replace(
    '        report_line cleaned low "空文件:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"\n',
    '        report_line cleaned low "空文件:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"\n        record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" 0\n',
    1,
)
cleaner = cleaner.replace(
    '        report_line cleaned low "空目录:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"\n',
    '        report_line cleaned low "空目录:$CATEGORY" "$ACTUAL_COUNT" 0 "$dir"\n        record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" 0\n',
    1,
)

# Give WebView cache entries a package-aware category.
cleaner = cleaner.replace(
    '    clean_dir "$dir" 0 "WebView可再生缓存" || return $?\n',
    '    package=$(package_from_target "$dir" 2>/dev/null)\n    if valid_package_name "$package"; then\n      clean_dir "$dir" 0 "WebView缓存:$package" || return $?\n    else\n      clean_dir "$dir" 0 "WebView缓存" || return $?\n    fi\n',
    1,
)
cleaner_path.write_text(cleaner, encoding="utf-8")


# ---------------------------------------------------------------------------
# RootService: expose the per-app report to the App and keep the latest result.
# ---------------------------------------------------------------------------
service_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt")
service = service_path.read_text(encoding="utf-8")
service = service.replace(
    '''        val latestReport = File(stateDir, "reports/latest.tsv")
        val output = tailText(log, 12_000)''',
    '''        val latestReport = File(stateDir, "reports/latest.tsv")
        val appDetails = appDetailsJson(File(stateDir, "reports/apps-latest.tsv"))
        val output = tailText(log, 12_000)''',
    1,
)
service = service.replace(
    '''            .put("latestReport", if (latestReport.isFile) latestReport.absolutePath else "")
            .put("message", when (code) {''',
    '''            .put("latestReport", if (latestReport.isFile) latestReport.absolutePath else "")
            .put("appDetails", appDetails)
            .put("message", when (code) {''',
    1,
)
service = service.replace(
    '''            .put("latest", latest)
            .put("running", running)''',
    '''            .put("latest", latest)
            .put("appDetails", appDetailsJson(File(stateDir, "reports/apps-latest.tsv")))
            .put("running", running)''',
    1,
)
service = service.replace(
    '''        File(STATE_DIR, "reports/latest.tsv").delete()
        JSONObject().put("success", true).toString()''',
    '''        File(STATE_DIR, "reports/latest.tsv").delete()
        File(STATE_DIR, "reports/apps-latest.tsv").delete()
        JSONObject().put("success", true).toString()''',
    1,
)

module_anchor = '''    private fun moduleState(): String {'''
app_parser = r'''    private fun appDetailsJson(file: File): JSONArray {
        val filesByPackage = linkedMapOf<String, Long>()
        val bytesByPackage = linkedMapOf<String, Long>()
        val categoriesByPackage = linkedMapOf<String, LinkedHashSet<String>>()
        runCatching {
            if (!file.isFile) return@runCatching
            file.forEachLine { raw ->
                val columns = raw.split('\t', limit = 4)
                if (columns.size < 4 || columns[0] == "package") return@forEachLine
                val packageName = columns[0].trim()
                if (!PACKAGE_NAME.matches(packageName)) return@forEachLine
                val category = columns[1].trim().take(80)
                val files = columns[2].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val bytes = columns[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                filesByPackage[packageName] = (filesByPackage[packageName] ?: 0L) + files
                bytesByPackage[packageName] = (bytesByPackage[packageName] ?: 0L) + bytes
                if (category.isNotBlank()) categoriesByPackage.getOrPut(packageName) { linkedSetOf() } += category
            }
        }
        val result = JSONArray()
        bytesByPackage.keys
            .sortedWith(compareByDescending<String> { bytesByPackage[it] ?: 0L }.thenBy { it })
            .take(100)
            .forEach { packageName ->
                result.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("files", filesByPackage[packageName] ?: 0L)
                        .put("bytes", bytesByPackage[packageName] ?: 0L)
                        .put("category", categoriesByPackage[packageName].orEmpty().joinToString("、"))
                )
            }
        return result
    }

'''
if module_anchor not in service:
    raise SystemExit("moduleState insertion point not found")
service = service.replace(module_anchor, app_parser + module_anchor, 1)
service_path.write_text(service, encoding="utf-8")


# ---------------------------------------------------------------------------
# Activity: resolve package labels, show live app progress, and load app results.
# ---------------------------------------------------------------------------
activity_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt")
activity = activity_path.read_text(encoding="utf-8")
activity = activity.replace(
    '''            val detailLine = "文件 $files · 空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · 异常 $errors · ${formatElapsed(elapsed)}"''',
    '''            val appDetails = parseAppDetails(json.optJSONArray("appDetails"))
            val detailLine = "文件 $files · 空文件 $emptyFiles · 空目录 $emptyDirs · 碎片 $fragments · 异常 $errors · ${formatElapsed(elapsed)}"''',
    1,
)
activity = activity.replace(
    '''                running = false,
                lastReleased = bytes,
                taskPhase = "$resultLine\n$detailLine"''',
    '''                running = false,
                lastReleased = bytes,
                recentApps = appDetails,
                taskPhase = "$resultLine\n$detailLine"''',
    1,
)

old_render = '''    private fun renderTaskState(json: JSONObject) {
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val path = json.optString("current_path", json.optString("currentPath"))
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (path.isNotBlank()) append("\n").append(path.takeLast(64))
            if (json.optBoolean("cancelRequested")) append("\n正在安全停止…")
        }
        dashboardState.value = dashboardState.value.copy(taskPhase = text)
    }'''
new_render = '''    private fun renderTaskState(json: JSONObject) {
        val current = json.optInt("progress_current", json.optInt("current", 0))
        val total = json.optInt("progress_total", json.optInt("total", 0))
        val target = json.optString("current_path", json.optString("currentPath")).trim()
        val targetText = when {
            looksLikePackageName(target) -> "${appLabel(target)} · $target"
            target.isNotBlank() -> target.takeLast(72)
            else -> ""
        }
        val text = buildString {
            append(json.optString("phase", "任务执行中"))
            if (total > 0) append(" · $current/$total")
            if (targetText.isNotBlank()) append("\n").append(targetText)
            if (json.optBoolean("cancelRequested")) append("\n正在停止…")
        }
        dashboardState.value = dashboardState.value.copy(taskPhase = text)
    }'''
if old_render not in activity:
    raise SystemExit("renderTaskState target not found")
activity = activity.replace(old_render, new_render, 1)

helper_anchor = '''    private fun renderTaskState(json: JSONObject) {'''
activity_helpers = '''    private fun parseAppDetails(array: JSONArray?): List<AppJunkUiItem> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val packageName = item.optString("packageName").trim()
            if (!looksLikePackageName(packageName)) continue
            add(
                AppJunkUiItem(
                    packageName = packageName,
                    label = appLabel(packageName),
                    category = item.optString("category").ifBlank { "应用缓存" },
                    files = item.optLong("files", 0L).coerceAtLeast(0L),
                    bytes = item.optLong("bytes", 0L).coerceAtLeast(0L)
                )
            )
        }
    }.sortedByDescending { it.bytes }

    private fun looksLikePackageName(value: String): Boolean =
        value.length in 3..180 && value.contains('.') && value.none { it == '/' || it.isWhitespace() }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
    }.getOrDefault(packageName)

'''
if helper_anchor not in activity:
    raise SystemExit("activity helper insertion point not found")
activity = activity.replace(helper_anchor, activity_helpers + helper_anchor, 1)

activity = activity.replace(
    '''            val latest = json.optJSONObject("latest") ?: JSONObject()
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()''',
    '''            val latest = json.optJSONObject("latest") ?: JSONObject()
            val scheduler = json.optJSONObject("scheduler") ?: JSONObject()
            val appDetails = parseAppDetails(json.optJSONArray("appDetails"))''',
    1,
)
activity = activity.replace(
    '''                lastReleased = latestReleased,
                schedulerText = when (scheduler.optString("state", "waiting")) {''',
    '''                lastReleased = latestReleased,
                recentApps = if (appDetails.isNotEmpty()) appDetails else dashboardState.value.recentApps,
                schedulerText = when (scheduler.optString("state", "waiting")) {''',
    1,
)
activity = activity.replace('"scan" -> "智能安全扫描"', '"scan" -> "垃圾扫描"', 1)
activity = activity.replace(
    '                    toast(if (success) "最近记录已清空" else "清空失败")\n                    refreshHistory()',
    '                    toast(if (success) "最近记录已清空" else "清空失败")\n                    if (success) dashboardState.value = dashboardState.value.copy(history = emptyList(), recentApps = emptyList())\n                    refreshHistory()',
    1,
)
activity_path.write_text(activity, encoding="utf-8")


# ---------------------------------------------------------------------------
# Compose: fix duplicate history keys and add a compact per-app result section.
# No navigation, card, dock, spacing or home layout changes.
# ---------------------------------------------------------------------------
ui_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace(
    '''    val whitelistCount: Int = 0,
    val history: List<HistoryUiItem> = emptyList()
)''',
    '''    val whitelistCount: Int = 0,
    val recentApps: List<AppJunkUiItem> = emptyList(),
    val history: List<HistoryUiItem> = emptyList()
)''',
    1,
)
ui = ui.replace(
    '''data class HistoryUiItem(
    val title: String,''',
    '''data class AppJunkUiItem(
    val packageName: String,
    val label: String,
    val category: String,
    val files: Long,
    val bytes: Long
)

data class HistoryUiItem(
    val title: String,''',
    1,
)
ui = ui.replace('原生清理引擎 · Alpha 27', '原生清理引擎 · Alpha 28', 1)
ui = ui.replace('Text("安全扫描完成", fontSize = 19.sp, fontWeight = FontWeight.Black)', 'Text("垃圾扫描完成", fontSize = 19.sp, fontWeight = FontWeight.Black)', 1)
ui = ui.replace('ToolRow(Icons.Rounded.Search, "安全扫描", "只查找并统计垃圾，不删除；完成后可一键清理", actions.scan)', 'ToolRow(Icons.Rounded.Search, "垃圾扫描", "只查找并统计垃圾，不删除；完成后可一键清理", actions.scan)', 1)
ui = ui.replace('ScheduleRow("deep", "深度安全项",', 'ScheduleRow("deep", "深度清理项",', 1)

recent_tasks_anchor = '''        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最近任务", modifier = Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("清空", modifier = Modifier.clickable(onClick = actions.clearHistory).padding(10.dp), color = Color(0xFFC43743), fontWeight = FontWeight.Bold)
            }
        }'''
app_section = '''        if (state.recentApps.isNotEmpty()) {
            item { SectionTitle("本次应用垃圾", "按实际清理结果从大到小排列") }
            items(state.recentApps.indices.toList(), key = { index -> "app-$index-${state.recentApps[index].packageName}" }) { index ->
                AppJunkCard(state.recentApps[index])
            }
        }
''' + recent_tasks_anchor
if recent_tasks_anchor not in ui:
    raise SystemExit("records app section anchor not found")
ui = ui.replace(recent_tasks_anchor, app_section, 1)
ui = ui.replace(
    '''            items(state.history, key = { "${it.time}-${it.title}-${it.bytes}" }) { item ->
                HistoryCard(item)
            }''',
    '''            items(state.history.indices.toList(), key = { index -> "history-$index" }) { index ->
                HistoryCard(state.history[index])
            }''',
    1,
)

stat_anchor = '''@Composable
private fun StatColumn(value: String, label: String) {'''
app_card = '''@Composable
private fun AppJunkCard(item: AppJunkUiItem) {
    val context = LocalContext.current
    GlassSurface(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadow = 5,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary.copy(.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.category} · ${item.files} 个文件", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Formatter.formatFileSize(context, item.bytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
        }
    }
}

'''
if stat_anchor not in ui:
    raise SystemExit("AppJunkCard insertion point not found")
ui = ui.replace(stat_anchor, app_card + stat_anchor, 1)
ui_path.write_text(ui, encoding="utf-8")


# ---------------------------------------------------------------------------
# Version and packaging.
# ---------------------------------------------------------------------------
for path, pairs in {
    "v2/app/build.gradle.kts": [
        ("versionCode = 20700", "versionCode = 20800"),
        ('versionName = "2.0.0-alpha27"', 'versionName = "2.0.0-alpha28"'),
    ],
    "v2/module/module.prop": [
        ("version=v2.0.0-alpha27", "version=v2.0.0-alpha28"),
        ("versionCode=20700", "versionCode=20800"),
    ],
    "v2/module/customize.sh": [("白泽 v2 Alpha 27", "白泽 v2 Alpha 28")],
    "v2/scripts/package-module.sh": [
        ("BaiZe-v2-Alpha27-Module.zip", "BaiZe-v2-Alpha28-Module.zip"),
        ("Alpha 27", "Alpha 28"),
    ],
}.items():
    for old, new in pairs:
        replace_once(path, old, new)

Path("v2/ALPHA28-CHANGES.md").write_text(
    """# Alpha 28 改动摘要

- 修复记录页使用重复 LazyColumn key 导致点击第三个底栏按钮闪退。
- 一键清理保持 Alpha 27 的主 RootService 直连链路，不改首页和底栏布局。
- 应用缓存改为按包名分组处理，清理中实时显示应用名称、包名和进度。
- 模块输出每个应用的实际清理文件数与释放空间，记录页按应用展示本次结果。
- WebView 缓存和应用扩展规则也会归属到对应应用。
- 用户界面将“安全扫描”等文案简化为“垃圾扫描”，不新增规则或风险页面。
- 参考 TeeForge-CD 的自动包识别、清晰进度日志，以及 AlwaysStrong 的一键动作和自动状态更新思路。
""",
    encoding="utf-8",
)
