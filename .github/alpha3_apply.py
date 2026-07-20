from pathlib import Path


def edit(path: str, old: str, new: str, count: int = -1) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:80]!r}")
    file.write_text(text.replace(old, new, count))


ENGINE = "v2/native/baize_engine_42_4.c"
edit(ENGINE, '#define ENGINE_VERSION "43.1-alpha2-one-pass"', '#define ENGINE_VERSION "43.2-alpha3-path-index"')
edit(
    ENGINE,
    "static StrVec g_whitelist = {0}, g_package_whitelist = {0}, g_installed_index = {0};\nstatic time_t g_started;",
    "static StrVec g_whitelist = {0}, g_package_whitelist = {0}, g_installed_index = {0};\n"
    "static uint64_t g_whitelist_index_queries;\n"
    "static uint64_t g_whitelist_ancestor_hits;\n"
    "static uint64_t g_whitelist_descendant_hits;\n"
    "static uint64_t g_whitelist_pruned_subtrees;\n"
    "static time_t g_started;",
)
edit(
    ENGINE,
    '''static bool vec_contains_sorted(const StrVec *a, const char *value) {
    size_t low = 0, high = a ? a->n : 0;
    while (low < high) {
        size_t mid = low + (high - low) / 2U;
        int cmp = strcmp(a->v[mid], value);
        if (cmp < 0) low = mid + 1U;
        else high = mid;
    }
    return a && low < a->n && strcmp(a->v[low], value) == 0;
}
''',
    '''static size_t vec_lower_bound(const StrVec *a, const char *value) {
    size_t low = 0, high = a ? a->n : 0;
    while (low < high) {
        size_t mid = low + (high - low) / 2U;
        int cmp = strcmp(a->v[mid], value);
        if (cmp < 0) low = mid + 1U;
        else high = mid;
    }
    return low;
}
static bool vec_contains_sorted(const StrVec *a, const char *value) {
    size_t position = vec_lower_bound(a, value);
    return a && position < a->n && strcmp(a->v[position], value) == 0;
}
''',
)
edit(
    ENGINE,
    '''static void load_lines(const char *path, StrVec *out, bool paths_only) {
    if (!path) return;
    FILE *f = fopen(path, "r");
    if (!f) return;
    char *line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, f) >= 0) {
        char *p = line;
        while (isspace((unsigned char)*p)) p++;
        char *e = p + strlen(p);
        while (e > p && isspace((unsigned char)e[-1])) *--e = '\\0';
        if (!*p || *p == '#') continue;
        if (paths_only && *p != '/') continue;
        vec_add(out, p);
    }
    free(line);
    fclose(f);
    vec_sort_unique(out);
}
static bool path_relation(const char *a, const char *b) {
    size_t n = strlen(a);
    return strcmp(a, b) == 0 || (strncmp(a, b, n) == 0 && b[n] == '/');
}
static bool whitelist_conflict(const char *target) {
    for (size_t i = 0; i < g_whitelist.n; i++) {
        const char *p = g_whitelist.v[i];
        if (strcmp(p, "/") == 0 || path_relation(p, target) || path_relation(target, p)) return true;
    }
    return false;
}
''',
    '''static void normalize_index_path(char *path) {
    size_t length = path ? strlen(path) : 0U;
    while (length > 1U && path[length - 1U] == '/') path[--length] = '\\0';
}
static void load_lines(const char *path, StrVec *out, bool paths_only) {
    if (!path) return;
    FILE *f = fopen(path, "r");
    if (!f) return;
    char *line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, f) >= 0) {
        char *p = line;
        while (isspace((unsigned char)*p)) p++;
        char *e = p + strlen(p);
        while (e > p && isspace((unsigned char)e[-1])) *--e = '\\0';
        if (!*p || *p == '#') continue;
        if (paths_only && *p != '/') continue;
        if (paths_only) normalize_index_path(p);
        vec_add(out, p);
    }
    free(line);
    fclose(f);
    vec_sort_unique(out);
}
static bool path_relation(const char *a, const char *b) {
    size_t n = strlen(a);
    return strcmp(a, b) == 0 || (strncmp(a, b, n) == 0 && b[n] == '/');
}
enum {
    WHITELIST_NONE = 0U,
    WHITELIST_ANCESTOR = 1U,
    WHITELIST_DESCENDANT = 2U
};
static unsigned whitelist_relation(const char *target) {
    g_whitelist_index_queries++;
    if (!target || target[0] != '/' || g_whitelist.n == 0U) return WHITELIST_NONE;
    char normalized[PATH_MAX];
    int written = snprintf(normalized, sizeof(normalized), "%s", target);
    if (written < 0 || (size_t)written >= sizeof(normalized)) return WHITELIST_NONE;
    normalize_index_path(normalized);
    if (vec_contains_sorted(&g_whitelist, "/") || vec_contains_sorted(&g_whitelist, normalized)) {
        g_whitelist_ancestor_hits++;
        return WHITELIST_ANCESTOR;
    }
    char prefix[PATH_MAX];
    written = snprintf(prefix, sizeof(prefix), "%s", normalized);
    if (written < 0 || (size_t)written >= sizeof(prefix)) return WHITELIST_NONE;
    char *cursor = prefix + 1;
    while ((cursor = strchr(cursor, '/')) != NULL) {
        *cursor = '\\0';
        bool protected = vec_contains_sorted(&g_whitelist, prefix);
        *cursor = '/';
        if (protected) {
            g_whitelist_ancestor_hits++;
            return WHITELIST_ANCESTOR;
        }
        cursor++;
    }
    size_t position = vec_lower_bound(&g_whitelist, normalized);
    if (position < g_whitelist.n && path_relation(normalized, g_whitelist.v[position])) {
        g_whitelist_descendant_hits++;
        return WHITELIST_DESCENDANT;
    }
    return WHITELIST_NONE;
}
static bool whitelist_conflict(const char *target) {
    return whitelist_relation(target) != WHITELIST_NONE;
}
''',
)
edit(
    ENGINE,
    '\\nfirst_result_ms=%" PRIu64 "\\none_pass_app_dirs=%" PRIu64 "\\none_pass_installed_dirs=%" PRIu64 "\\none_pass_orphan_dirs=%" PRIu64 "\\nelapsed_ms=%" PRIu64 "\\nitems_per_second=%" PRIu64 "\\nengine=native-c-arm64\\nversion=%s\\n",',
    '\\nfirst_result_ms=%" PRIu64 "\\none_pass_app_dirs=%" PRIu64 "\\none_pass_installed_dirs=%" PRIu64 "\\none_pass_orphan_dirs=%" PRIu64 "\\nwhitelist_index_entries=%" PRIu64 "\\nwhitelist_index_queries=%" PRIu64 "\\nwhitelist_ancestor_hits=%" PRIu64 "\\nwhitelist_descendant_hits=%" PRIu64 "\\npruned_subtrees=%" PRIu64 "\\nelapsed_ms=%" PRIu64 "\\nitems_per_second=%" PRIu64 "\\nengine=native-c-arm64\\nversion=%s\\n",',
)
edit(
    ENGINE,
    '''        t->one_pass_app_dirs, t->one_pass_installed_dirs, t->one_pass_orphan_dirs,
        elapsed_ms, throughput, ENGINE_VERSION);''',
    '''        t->one_pass_app_dirs, t->one_pass_installed_dirs, t->one_pass_orphan_dirs,
        (uint64_t)g_whitelist.n, g_whitelist_index_queries,
        g_whitelist_ancestor_hits, g_whitelist_descendant_hits,
        g_whitelist_pruned_subtrees, elapsed_ms, throughput, ENGINE_VERSION);''',
)
edit(
    ENGINE,
    '''static int snapshot_cache_rec(const char *path, dev_t root_dev, const Options *o, int days,
                              FILE *manifest, const char *pkg, const char *category,
                              Stats *stats, unsigned depth) {''',
    '''static int snapshot_cache_rec(const char *path, dev_t root_dev, const Options *o, int days,
                              FILE *manifest, const char *pkg, const char *category,
                              Stats *stats, bool may_contain_whitelist, unsigned depth) {''',
)
anchor = '''    if (depth > 512U) { stats->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
    struct stat st;'''
replacement = '''    if (depth > 512U) { stats->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
    if (depth > 0U && may_contain_whitelist) {
        unsigned relation = whitelist_relation(path);
        if ((relation & WHITELIST_ANCESTOR) != 0U) {
            g_whitelist_pruned_subtrees++;
            return 0;
        }
        may_contain_whitelist = (relation & WHITELIST_DESCENDANT) != 0U;
    }
    struct stat st;'''
text = Path(ENGINE).read_text()
start = text.index("static int snapshot_cache_rec")
pos = text.index(anchor, start)
Path(ENGINE).write_text(text[:pos] + replacement + text[pos + len(anchor):])
edit(
    ENGINE,
    '''        int code = snapshot_cache_rec(child, root_dev, o, days, manifest, pkg, category, stats, depth + 1U);''',
    '''        int code = snapshot_cache_rec(child, root_dev, o, days, manifest, pkg, category,
                                      stats, may_contain_whitelist, depth + 1U);''',
)
edit(
    ENGINE,
    '''static int snapshot_cache_tree(const char *path, const Options *o, const char *pkg,
                               const char *category, FILE *manifest, Stats *stats) {''',
    '''static int snapshot_cache_tree(const char *path, const Options *o, const char *pkg,
                               const char *category, FILE *manifest, Stats *stats,
                               bool may_contain_whitelist) {''',
)
edit(
    ENGINE,
    '''    int code = snapshot_cache_rec(path, root.st_dev, o, o->min_age_days, temporary, pkg, category, stats, 0U);''',
    '''    int code = snapshot_cache_rec(path, root.st_dev, o, o->min_age_days, temporary,
                                  pkg, category, stats, may_contain_whitelist, 0U);''',
)
edit(
    ENGINE,
    '''    if (package_whitelisted(pkg) || whitelist_conflict(path)) {
        totals->skipped++;
        totals->whitelisted++;
        report_row(rep, "skipped", "protected", category, 0, 0, path);
        return;
    }
    Stats stats;
    int code = snapshot_cache_tree(path, o, pkg, category, manifest, &stats);''',
    '''    unsigned relation = whitelist_relation(path);
    if (package_whitelisted(pkg) || (relation & WHITELIST_ANCESTOR) != 0U) {
        totals->skipped++;
        totals->whitelisted++;
        report_row(rep, "skipped", "protected", category, 0, 0, path);
        return;
    }
    Stats stats;
    int code = snapshot_cache_tree(path, o, pkg, category, manifest, &stats,
                                   (relation & WHITELIST_DESCENDANT) != 0U);''',
)

NATIVE_SCAN = "v2/module/native-scan.sh"
edit(
    NATIVE_SCAN,
    "ITEMS_PER_SECOND=$(summary_number items_per_second)\nTOTAL_ITEMS=$((FILES + EMPTY_DIRS))",
    "ITEMS_PER_SECOND=$(summary_number items_per_second)\n"
    "WHITELIST_INDEX_ENTRIES=$(summary_number whitelist_index_entries)\n"
    "WHITELIST_INDEX_QUERIES=$(summary_number whitelist_index_queries)\n"
    "WHITELIST_ANCESTOR_HITS=$(summary_number whitelist_ancestor_hits)\n"
    "WHITELIST_DESCENDANT_HITS=$(summary_number whitelist_descendant_hits)\n"
    "PRUNED_SUBTREES=$(summary_number pruned_subtrees)\n"
    "TOTAL_ITEMS=$((FILES + EMPTY_DIRS))",
)
text = Path(NATIVE_SCAN).read_text()
text = text.replace(
    '        echo "items_per_second=$ITEMS_PER_SECOND"',
    '        echo "items_per_second=$ITEMS_PER_SECOND"\n'
    '        echo "whitelist_index_entries=$WHITELIST_INDEX_ENTRIES"\n'
    '        echo "whitelist_index_queries=$WHITELIST_INDEX_QUERIES"\n'
    '        echo "whitelist_ancestor_hits=$WHITELIST_ANCESTOR_HITS"\n'
    '        echo "whitelist_descendant_hits=$WHITELIST_DESCENDANT_HITS"\n'
    '        echo "pruned_subtrees=$PRUNED_SUBTREES"',
)
text = text.replace(
    '  echo "items_per_second=$ITEMS_PER_SECOND"',
    '  echo "items_per_second=$ITEMS_PER_SECOND"\n'
    '  echo "whitelist_index_entries=$WHITELIST_INDEX_ENTRIES"\n'
    '  echo "whitelist_index_queries=$WHITELIST_INDEX_QUERIES"\n'
    '  echo "whitelist_ancestor_hits=$WHITELIST_ANCESTOR_HITS"\n'
    '  echo "whitelist_descendant_hits=$WHITELIST_DESCENDANT_HITS"\n'
    '  echo "pruned_subtrees=$PRUNED_SUBTREES"',
)
text = text.replace(
    '  echo "whitelist_index_entries=$WHITELIST_INDEX_ENTRIES"\n'
    '  echo "whitelist_index_queries=$WHITELIST_INDEX_QUERIES"\n'
    '  echo "whitelist_ancestor_hits=$WHITELIST_ANCESTOR_HITS"\n'
    '  echo "whitelist_descendant_hits=$WHITELIST_DESCENDANT_HITS"\n'
    '  echo "pruned_subtrees=$PRUNED_SUBTREES"\n'
    '        echo "whitelist_index_entries=$WHITELIST_INDEX_ENTRIES"',
    '        echo "whitelist_index_entries=$WHITELIST_INDEX_ENTRIES"',
)
text = text.replace("engine=native-c-arm64-indexed", "engine=native-c-arm64-path-index")
Path(NATIVE_SCAN).write_text(text)

ONE_PASS = "v2/module/one-pass-scan.sh"
edit(
    ONE_PASS,
    'ONE_ORPHAN=$(summary_number "$CACHE_SUMMARY" one_pass_orphan_dirs)\n\nR_FILES=',
    'ONE_ORPHAN=$(summary_number "$CACHE_SUMMARY" one_pass_orphan_dirs)\n'
    'WL_INDEX_ENTRIES=$(summary_number "$CACHE_SUMMARY" whitelist_index_entries)\n'
    'WL_INDEX_QUERIES=$(summary_number "$CACHE_SUMMARY" whitelist_index_queries)\n'
    'WL_ANCESTOR_HITS=$(summary_number "$CACHE_SUMMARY" whitelist_ancestor_hits)\n'
    'WL_DESCENDANT_HITS=$(summary_number "$CACHE_SUMMARY" whitelist_descendant_hits)\n'
    'WL_PRUNED_SUBTREES=$(summary_number "$CACHE_SUMMARY" pruned_subtrees)\n\nR_FILES=',
)
text = Path(ONE_PASS).read_text()
text = text.replace(
    '  echo "one_pass_orphan_dirs=$ONE_ORPHAN"',
    '  echo "one_pass_orphan_dirs=$ONE_ORPHAN"\n'
    '  echo "whitelist_index_entries=$WL_INDEX_ENTRIES"\n'
    '  echo "whitelist_index_queries=$WL_INDEX_QUERIES"\n'
    '  echo "whitelist_ancestor_hits=$WL_ANCESTOR_HITS"\n'
    '  echo "whitelist_descendant_hits=$WL_DESCENDANT_HITS"\n'
    '  echo "pruned_subtrees=$WL_PRUNED_SUBTREES"',
)
text = text.replace("engine=native-c-arm64-one-pass", "engine=native-c-arm64-one-pass-path-index")
text = text.replace("原生引擎: C arm64 43.1 Alpha 2 外部目录 One-pass", "原生引擎: C arm64 43.2 Alpha 3 路径索引 One-pass")
text = text.replace(
    '  echo "共享索引: $INDEX_ENTRIES 项 / $INDEX_FILES 个用户文件 / $INDEX_LOOKUPS 次查询"',
    '  echo "共享索引: $INDEX_ENTRIES 项 / $INDEX_FILES 个用户文件 / $INDEX_LOOKUPS 次查询"\n'
    '  echo "路径索引: $WL_INDEX_ENTRIES 项 / $WL_INDEX_QUERIES 次查询 / $WL_PRUNED_SUBTREES 个子树提前剪枝"',
)
Path(ONE_PASS).write_text(text)

ROOT = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeRootService.kt"
edit(ROOT, "v2.1.0 Alpha 2 One-pass cache task bridge.", "v2.1.0 Alpha 3 path-indexed One-pass cache task bridge.")
edit(ROOT, "native-c-arm64-cache-v43.1-alpha2-one-pass", "native-c-arm64-cache-v43.2-alpha3-path-index")
edit(
    ROOT,
    "    @Volatile private var snapshotOnePassOrphanDirs = 0L\n    @Volatile private var items: List<CacheItem> = emptyList()",
    "    @Volatile private var snapshotOnePassOrphanDirs = 0L\n"
    "    @Volatile private var snapshotWhitelistIndexEntries = 0L\n"
    "    @Volatile private var snapshotWhitelistIndexQueries = 0L\n"
    "    @Volatile private var snapshotWhitelistAncestorHits = 0L\n"
    "    @Volatile private var snapshotWhitelistDescendantHits = 0L\n"
    "    @Volatile private var snapshotPrunedSubtrees = 0L\n"
    "    @Volatile private var items: List<CacheItem> = emptyList()",
)
edit(
    ROOT,
    '                .put("onePassOrphanDirs", if (ready) snapshotOnePassOrphanDirs else 0L)\n                .put("snapshotCreatedAt", if (ready) snapshotCreatedAt else 0L)',
    '                .put("onePassOrphanDirs", if (ready) snapshotOnePassOrphanDirs else 0L)\n'
    '                .put("whitelistIndexEntries", if (ready) snapshotWhitelistIndexEntries else 0L)\n'
    '                .put("whitelistIndexQueries", if (ready) snapshotWhitelistIndexQueries else 0L)\n'
    '                .put("whitelistAncestorHits", if (ready) snapshotWhitelistAncestorHits else 0L)\n'
    '                .put("whitelistDescendantHits", if (ready) snapshotWhitelistDescendantHits else 0L)\n'
    '                .put("prunedSubtrees", if (ready) snapshotPrunedSubtrees else 0L)\n'
    '                .put("snapshotCreatedAt", if (ready) snapshotCreatedAt else 0L)',
)
edit(
    ROOT,
    '            .put("onePassOrphanDirs", snapshotOnePassOrphanDirs)\n            .put("engine", "native-c-arm64-indexed")',
    '            .put("onePassOrphanDirs", snapshotOnePassOrphanDirs)\n'
    '            .put("whitelistIndexEntries", snapshotWhitelistIndexEntries)\n'
    '            .put("whitelistIndexQueries", snapshotWhitelistIndexQueries)\n'
    '            .put("whitelistAncestorHits", snapshotWhitelistAncestorHits)\n'
    '            .put("whitelistDescendantHits", snapshotWhitelistDescendantHits)\n'
    '            .put("prunedSubtrees", snapshotPrunedSubtrees)\n'
    '            .put("engine", "native-c-arm64-path-index")',
)
edit(
    ROOT,
    '        snapshotOnePassOrphanDirs = state.optLong("one_pass_orphan_dirs", latest.optLong("one_pass_orphan_dirs", 0L)).coerceAtLeast(0L)\n        return true',
    '        snapshotOnePassOrphanDirs = state.optLong("one_pass_orphan_dirs", latest.optLong("one_pass_orphan_dirs", 0L)).coerceAtLeast(0L)\n'
    '        snapshotWhitelistIndexEntries = state.optLong("whitelist_index_entries", latest.optLong("whitelist_index_entries", 0L)).coerceAtLeast(0L)\n'
    '        snapshotWhitelistIndexQueries = state.optLong("whitelist_index_queries", latest.optLong("whitelist_index_queries", 0L)).coerceAtLeast(0L)\n'
    '        snapshotWhitelistAncestorHits = state.optLong("whitelist_ancestor_hits", latest.optLong("whitelist_ancestor_hits", 0L)).coerceAtLeast(0L)\n'
    '        snapshotWhitelistDescendantHits = state.optLong("whitelist_descendant_hits", latest.optLong("whitelist_descendant_hits", 0L)).coerceAtLeast(0L)\n'
    '        snapshotPrunedSubtrees = state.optLong("pruned_subtrees", latest.optLong("pruned_subtrees", 0L)).coerceAtLeast(0L)\n'
    '        return true',
)
edit(
    ROOT,
    '        snapshotWhitelisted = 0\n    }',
    '        snapshotWhitelisted = 0\n'
    '        snapshotVisitedFiles = 0L\n'
    '        snapshotVisitedDirs = 0L\n'
    '        snapshotFirstResultMs = 0L\n'
    '        snapshotEngineElapsedMs = 0L\n'
    '        snapshotItemsPerSecond = 0L\n'
    '        snapshotOnePassAppDirs = 0L\n'
    '        snapshotOnePassInstalledDirs = 0L\n'
    '        snapshotOnePassOrphanDirs = 0L\n'
    '        snapshotWhitelistIndexEntries = 0L\n'
    '        snapshotWhitelistIndexQueries = 0L\n'
    '        snapshotWhitelistAncestorHits = 0L\n'
    '        snapshotWhitelistDescendantHits = 0L\n'
    '        snapshotPrunedSubtrees = 0L\n'
    '    }',
    1,
)

edit("v2/app/build.gradle.kts", "versionCode = 22402", "versionCode = 22403")
edit("v2/app/build.gradle.kts", 'versionName = "2.1.0-alpha2"', 'versionName = "2.1.0-alpha3"')
edit("v2/module/module.prop", "version=v2.1.0-alpha2", "version=v2.1.0-alpha3")
edit("v2/module/module.prop", "versionCode=22402", "versionCode=22403")
edit(
    "v2/module/module.prop",
    "description=白泽 v2.1.0 Alpha 2：Android/data 单次枚举，同时生成应用缓存与卸载残留独立快照。",
    "description=白泽 v2.1.0 Alpha 3：路径白名单索引、受保护子树提前剪枝与 One-pass 双快照。",
)
edit("v2/module/customize.sh", "白泽 v2.1.0 Alpha 2 One-pass 预览版", "白泽 v2.1.0 Alpha 3 路径索引预览版")
edit("v2/module/service.sh", "module_version=2.1.0-alpha2", "module_version=2.1.0-alpha3")
edit("v2/scripts/build-native.sh", "白泽 v2.1.0 Alpha 2 外部目录 One-pass/不可变快照引擎", "白泽 v2.1.0 Alpha 3 路径索引/One-pass/不可变快照引擎")
edit("v2/scripts/package-module.sh", "BaiZe-v2.1.0-alpha2-Module.zip", "BaiZe-v2.1.0-alpha3-Module.zip")
edit("v2/scripts/package-module.sh", "^version=v2.1.0-alpha2$", "^version=v2.1.0-alpha3$")
edit("v2/scripts/package-module.sh", "^versionCode=22402$", "^versionCode=22403$")
edit("v2/scripts/package-module.sh", "白泽 v2.1.0 Alpha 2 One-pass 性能预览模块", "白泽 v2.1.0 Alpha 3 路径索引性能预览模块")
edit(
    "v2/scripts/package-module.sh",
    'unzip -p "$OUTPUT" one-pass-scan.sh | grep -q \'one_pass_app_dirs\'\n',
    'unzip -p "$OUTPUT" one-pass-scan.sh | grep -q \'one_pass_app_dirs\'\n'
    'unzip -p "$OUTPUT" one-pass-scan.sh | grep -q \'whitelist_index_queries\'\n'
    'unzip -p "$OUTPUT" one-pass-scan.sh | grep -q \'pruned_subtrees\'\n',
)

Path("RELEASE_NOTES_V2.md").write_text("""# 白泽 v2.1.0 Alpha 3

## 路径白名单索引与提前剪枝

- 路径白名单加载后统一去除尾部斜杠、排序并去重。
- 白名单关系判断从逐条线性比较改为路径分段二分索引。
- 应用缓存目录包含受保护子目录时，不再跳过整个缓存目录；扫描器只提前剪枝受保护子树，并继续生成其余文件的不可变快照。
- 卸载残留和深度规则仍保持整目标保护：目标自身或后代命中白名单时不会进入清理快照。
- 新增白名单索引条数、查询次数、祖先命中、后代命中和提前剪枝子树统计。
- 保留 Alpha 2 的 Android/data 单次枚举、缓存/残留双快照以及扫描后变化保护。

## 版本

- 模块：`v2.1.0-alpha3`
- App：`2.1.0-alpha3`
- versionCode：`22403`
- 原生引擎：`43.2-alpha3-path-index`
""")
