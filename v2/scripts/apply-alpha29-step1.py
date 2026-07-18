#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Alpha29 patch target not found: {label}")
    return text.replace(old, new, 1)


def shell_function_bounds(text: str, name: str) -> tuple[int, int]:
    marker = f"{name}() {{"
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"Shell function not found: {name}")
    match = re.search(r"\n[A-Za-z_][A-Za-z0-9_]*\(\) \{", text[start + len(marker):])
    end = len(text) if match is None else start + len(marker) + match.start() + 1
    return start, end


def replace_shell_function(text: str, name: str, replacement: str) -> str:
    start, end = shell_function_bounds(text, name)
    return text[:start] + replacement.rstrip() + "\n\n" + text[end:]


def patch_shell_function(text: str, name: str, transform) -> str:
    start, end = shell_function_bounds(text, name)
    body = text[start:end]
    updated = transform(body)
    if updated == body:
        raise SystemExit(f"Shell function patch produced no change: {name}")
    return text[:start] + updated + text[end:]


def kotlin_function_bounds(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Kotlin function not found: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Kotlin function has no body: {signature}")
    depth = 0
    in_string = False
    escaped = False
    index = brace
    while index < len(text):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        else:
            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return start, index + 1
        index += 1
    raise SystemExit(f"Unclosed Kotlin function: {signature}")


def replace_kotlin_function(text: str, signature: str, replacement: str) -> str:
    start, end = kotlin_function_bounds(text, signature)
    return text[:start] + replacement.rstrip() + text[end:]


# ---------------------------------------------------------------------------
# cleaner.sh: preserve Alpha 28 behavior while adding a normalized per-category
# result stream and exact-path deduplication for overlapping cache/rule matches.
# ---------------------------------------------------------------------------
cleaner_path = Path("cleaner.sh")
cleaner = cleaner_path.read_text(encoding="utf-8")

cleaner = replace_once(
    cleaner,
    'APP_DETAILS="$REPORT_DIR/apps-latest.tsv"\n',
    'APP_DETAILS="$REPORT_DIR/apps-latest.tsv"\nAPP_ITEMS="$REPORT_DIR/app-items-latest.tsv"\n',
    "APP_ITEMS variable",
)
cleaner = replace_once(
    cleaner,
    'TMP_DIR="$LOCK_DIR/tmp"\nmkdir -p "$TMP_DIR"\n',
    'TMP_DIR="$LOCK_DIR/tmp"\nmkdir -p "$TMP_DIR"\nPROCESSED_PATHS="$TMP_DIR/processed-paths"\n: >"$PROCESSED_PATHS"\n',
    "processed path state",
)
cleaner = replace_once(
    cleaner,
    "printf 'package\\tcategory\\tfiles\\tbytes\\n' >\"$APP_DETAILS\"\n",
    "printf 'package\\tcategory\\tfiles\\tbytes\\n' >\"$APP_DETAILS\"\nprintf 'package\\tcategory\\tfiles\\tbytes\\terrors\\tsample_path\\n' >\"$APP_ITEMS\"\n",
    "app item header",
)

cleaner = replace_shell_function(
    cleaner,
    "append_app_detail",
    r'''append_app_detail() {
  package=$1
  category=$2
  files=$3
  bytes=$4
  errors=${5:-0}
  sample_path=${6:-}
  valid_package_name "$package" || return 0
  case "$files" in ''|*[!0-9]*) files=0 ;; esac
  case "$bytes" in ''|*[!0-9]*) bytes=0 ;; esac
  case "$errors" in ''|*[!0-9]*) errors=0 ;; esac
  [ "$files" -gt 0 ] || [ "$bytes" -gt 0 ] || [ "$errors" -gt 0 ] || return 0
  package=$(sanitize_report_field "$package")
  category=$(sanitize_report_field "$category")
  sample_path=$(sanitize_report_field "$sample_path")
  printf '%s\t%s\t%s\t%s\n' "$package" "$category" "$files" "$bytes" >>"$APP_DETAILS"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$package" "$category" "$files" "$bytes" "$errors" "$sample_path" >>"$APP_ITEMS"
}''',
)
cleaner = replace_shell_function(
    cleaner,
    "record_app_detail_for",
    r'''record_app_detail_for() {
  target=$1
  category=$2
  files=$3
  bytes=$4
  errors=${5:-0}
  sample_path=${6:-$target}
  package=$(package_for_detail "$target" "$category" 2>/dev/null) || return 0
  append_app_detail "$package" "$category" "$files" "$bytes" "$errors" "$sample_path"
}''',
)

helper_anchor = r'''existing_paths_to_list() {
  source_list=$1
  target_list=$2
  : >"$target_list"
  while IFS= read -r -d '' candidate; do
    { [ -e "$candidate" ] || [ -L "$candidate" ]; } && printf '%s\0' "$candidate" >>"$target_list"
  done <"$source_list"
}
'''
helper_replacement = helper_anchor + r'''
first_nul_path() {
  source_list=$1
  while IFS= read -r -d '' candidate; do
    printf '%s\n' "$candidate"
    return 0
  done <"$source_list"
  return 1
}

filter_processed_list() {
  source_list=$1
  unique_list="$source_list.unique"
  : >"$unique_list"
  while IFS= read -r -d '' candidate; do
    [ -n "$candidate" ] || continue
    canonical=$(canonical_rule_path "$candidate" 2>/dev/null)
    [ -n "$canonical" ] || canonical=$candidate
    key=$(printf '%s' "$canonical" | tr '\r\n' '  ')
    grep -Fqx -- "$key" "$PROCESSED_PATHS" 2>/dev/null && continue
    printf '%s\n' "$key" >>"$PROCESSED_PATHS"
    printf '%s\0' "$candidate" >>"$unique_list"
  done <"$source_list"
  mv -f "$unique_list" "$source_list"
}
'''
cleaner = replace_once(cleaner, helper_anchor, helper_replacement, "dedup helpers")


def patch_cache(body: str) -> str:
    body = replace_once(
        body,
        '  filter_whitelist_list "$list"\n  count=$(count_nul "$list")\n',
        '  filter_whitelist_list "$list"\n  filter_processed_list "$list"\n  count=$(count_nul "$list")\n',
        "cache dedup",
    )
    body = replace_once(
        body,
        '  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }\n\n',
        '  [ "$count" -gt 0 ] || { rm -f "$list"; return 0; }\n  sample_path=$(first_nul_path "$list" 2>/dev/null)\n\n',
        "cache sample",
    )
    body = replace_once(
        body,
        '    append_app_detail "$app_package" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES"\n',
        '    append_app_detail "$app_package" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$REMAINING_COUNT" "$sample_path"\n',
        "cache clean detail",
    )
    body = replace_once(
        body,
        '    append_app_detail "$app_package" "$CATEGORY" "$count" "$estimated"\n',
        '    append_app_detail "$app_package" "$CATEGORY" "$count" "$estimated" 0 "$sample_path"\n',
        "cache scan detail",
    )
    return body


cleaner = patch_shell_function(cleaner, "process_cache_candidates", patch_cache)


def patch_clean_dir(body: str) -> str:
    old_filter = '    filter_whitelist_list "$list"\n'
    new_filter = '    filter_whitelist_list "$list"\n    filter_processed_list "$list"\n'
    occurrences = body.count(old_filter)
    if occurrences < 2:
        raise SystemExit(f"Expected clean_dir list filters, found {occurrences}")
    body = body.replace(old_filter, new_filter)
    body = body.replace(
        '  filter_whitelist_list "$list"\n  count=$(count_nul "$list")\n',
        '  filter_whitelist_list "$list"\n  filter_processed_list "$list"\n  count=$(count_nul "$list")\n',
        1,
    )
    body = body.replace(
        '  case "$count" in \'\'|*[!0-9]*) count=0 ;; esac\n\n  if [ "$count" -gt 0 ]; then',
        '  case "$count" in \'\'|*[!0-9]*) count=0 ;; esac\n  sample_path=$(first_nul_path "$list" 2>/dev/null)\n\n  if [ "$count" -gt 0 ]; then',
        1,
    )
    body = body.replace(
        '    case "$count" in \'\'|*[!0-9]*) count=0 ;; esac\n    if [ "$count" -gt 0 ]; then',
        '    case "$count" in \'\'|*[!0-9]*) count=0 ;; esac\n    sample_path=$(first_nul_path "$list" 2>/dev/null)\n    if [ "$count" -gt 0 ]; then',
    )
    body = replace_once(
        body,
        '      record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES"\n',
        '      record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" "$ACTUAL_BYTES" "$REMAINING_COUNT" "$sample_path"\n',
        "regular app detail",
    )
    body = replace_once(
        body,
        '        record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" 0\n',
        '        record_app_detail_for "$dir" "空文件:$CATEGORY" "$ACTUAL_COUNT" 0 "$REMAINING_COUNT" "$sample_path"\n',
        "empty file detail",
    )
    body = replace_once(
        body,
        '        record_app_detail_for "$dir" "$CATEGORY" "$ACTUAL_COUNT" 0\n',
        '        record_app_detail_for "$dir" "空目录:$CATEGORY" "$ACTUAL_COUNT" 0 "$REMAINING_COUNT" "$sample_path"\n',
        "empty dir detail",
    )
    return body


cleaner = patch_shell_function(cleaner, "clean_dir", patch_clean_dir)
cleaner_path.write_text(cleaner, encoding="utf-8")


# ---------------------------------------------------------------------------
# Root service: expose nested per-category details while keeping the existing
# Alpha 28 top-level fields so the current UI remains fully compatible.
# ---------------------------------------------------------------------------
service_path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt")
service = service_path.read_text(encoding="utf-8")
service = replace_once(
    service,
    '        val appDetails = appDetailsJson(File(stateDir, "reports/apps-latest.tsv"))\n',
    '        val appDetails = appDetailsJson(\n            File(stateDir, "reports/apps-latest.tsv"),\n            File(stateDir, "reports/app-items-latest.tsv")\n        )\n',
    "task app detail files",
)

new_parser = r'''    private fun appDetailsJson(summaryFile: File, itemFile: File): JSONArray {
        val filesByPackage = linkedMapOf<String, Long>()
        val bytesByPackage = linkedMapOf<String, Long>()
        val errorsByPackage = linkedMapOf<String, Long>()
        val categoryFiles = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val categoryBytes = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val categoryErrors = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val categorySamples = linkedMapOf<String, LinkedHashMap<String, String>>()
        var itemRows = 0

        fun add(packageName: String, category: String, files: Long, bytes: Long, errors: Long, samplePath: String) {
            if (!PACKAGE_NAME.matches(packageName)) return
            val safeCategory = category.trim().take(80).ifBlank { "应用缓存" }
            val safeFiles = files.coerceAtLeast(0L)
            val safeBytes = bytes.coerceAtLeast(0L)
            val safeErrors = errors.coerceAtLeast(0L)
            filesByPackage[packageName] = (filesByPackage[packageName] ?: 0L) + safeFiles
            bytesByPackage[packageName] = (bytesByPackage[packageName] ?: 0L) + safeBytes
            errorsByPackage[packageName] = (errorsByPackage[packageName] ?: 0L) + safeErrors
            val filesMap = categoryFiles.getOrPut(packageName) { linkedMapOf() }
            val bytesMap = categoryBytes.getOrPut(packageName) { linkedMapOf() }
            val errorsMap = categoryErrors.getOrPut(packageName) { linkedMapOf() }
            filesMap[safeCategory] = (filesMap[safeCategory] ?: 0L) + safeFiles
            bytesMap[safeCategory] = (bytesMap[safeCategory] ?: 0L) + safeBytes
            errorsMap[safeCategory] = (errorsMap[safeCategory] ?: 0L) + safeErrors
            val sample = samplePath.trim().take(240)
            if (sample.isNotBlank()) categorySamples.getOrPut(packageName) { linkedMapOf() }.putIfAbsent(safeCategory, sample)
        }

        runCatching {
            if (!itemFile.isFile) return@runCatching
            itemFile.forEachLine { raw ->
                val columns = raw.split('\t', limit = 6)
                if (columns.size < 6 || columns[0] == "package") return@forEachLine
                add(
                    packageName = columns[0].trim(),
                    category = columns[1],
                    files = columns[2].toLongOrNull() ?: 0L,
                    bytes = columns[3].toLongOrNull() ?: 0L,
                    errors = columns[4].toLongOrNull() ?: 0L,
                    samplePath = columns[5]
                )
                itemRows += 1
            }
        }

        if (itemRows == 0) {
            runCatching {
                if (!summaryFile.isFile) return@runCatching
                summaryFile.forEachLine { raw ->
                    val columns = raw.split('\t', limit = 4)
                    if (columns.size < 4 || columns[0] == "package") return@forEachLine
                    add(
                        packageName = columns[0].trim(),
                        category = columns[1],
                        files = columns[2].toLongOrNull() ?: 0L,
                        bytes = columns[3].toLongOrNull() ?: 0L,
                        errors = 0L,
                        samplePath = ""
                    )
                }
            }
        }

        val result = JSONArray()
        bytesByPackage.keys
            .sortedWith(
                compareByDescending<String> { bytesByPackage[it] ?: 0L }
                    .thenByDescending { filesByPackage[it] ?: 0L }
                    .thenBy { it }
            )
            .take(100)
            .forEach { packageName ->
                val categories = JSONArray()
                val names = categoryBytes[packageName].orEmpty().keys
                    .sortedWith(compareByDescending<String> { categoryBytes[packageName]?.get(it) ?: 0L }.thenBy { it })
                names.forEach { name ->
                    categories.put(
                        JSONObject()
                            .put("name", name)
                            .put("files", categoryFiles[packageName]?.get(name) ?: 0L)
                            .put("bytes", categoryBytes[packageName]?.get(name) ?: 0L)
                            .put("errors", categoryErrors[packageName]?.get(name) ?: 0L)
                            .put("samplePath", categorySamples[packageName]?.get(name).orEmpty())
                    )
                }
                result.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("files", filesByPackage[packageName] ?: 0L)
                        .put("bytes", bytesByPackage[packageName] ?: 0L)
                        .put("errors", errorsByPackage[packageName] ?: 0L)
                        .put("category", names.joinToString("、"))
                        .put("categories", categories)
                )
            }
        return result
    }'''
service = replace_kotlin_function(service, "    private fun appDetailsJson(", new_parser)
service = replace_once(
    service,
    '            .put("appDetails", appDetailsJson(File(stateDir, "reports/apps-latest.tsv")))\n',
    '            .put(\n                "appDetails",\n                appDetailsJson(\n                    File(stateDir, "reports/apps-latest.tsv"),\n                    File(stateDir, "reports/app-items-latest.tsv")\n                )\n            )\n',
    "module state details",
)
service = replace_once(
    service,
    '        File(STATE_DIR, "reports/apps-latest.tsv").delete()\n',
    '        File(STATE_DIR, "reports/apps-latest.tsv").delete()\n        File(STATE_DIR, "reports/app-items-latest.tsv").delete()\n',
    "clear item report",
)
service_path.write_text(service, encoding="utf-8")

print("Alpha 29 step 1 data-layer patch applied")
