from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "v2/native/baize_engine_42_4.c"
text = path.read_text()


def rep(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing native anchor: {label}")
    text = text.replace(old, new, 1)

rep('#define ENGINE_VERSION "43.3-alpha4-bounded-parallel"', '#define ENGINE_VERSION "43.5-v225-deep-budget"', 'engine version')
rep(
    """    uint64_t visited_files, visited_dirs;
    bool oversized, mount_conflict, incomplete;
} Stats;
""",
    """    uint64_t visited_files, visited_dirs;
    uint64_t elapsed_ms;
    bool oversized, mount_conflict, incomplete, timed_out;
} Stats;
""",
    'stats budget fields',
)
rep(
    """    uint64_t max_file_bytes;
    int min_age_days;
    bool allow_high_risk;
""",
    """    uint64_t max_file_bytes;
    uint64_t dir_budget_ms;
    uint64_t global_budget_ms;
    int min_age_days;
    bool allow_high_risk;
""",
    'option budget fields',
)
rep(
    """    uint64_t first_result_ms;
    uint64_t one_pass_app_dirs, one_pass_installed_dirs, one_pass_orphan_dirs;
""",
    """    uint64_t first_result_ms;
    uint64_t timed_out_dirs;
    uint64_t one_pass_app_dirs, one_pass_installed_dirs, one_pass_orphan_dirs;
""",
    'total timeout field',
)
rep(
    """static uint64_t g_last_progress_ms;
""",
    """static uint64_t g_last_progress_ms;
static uint64_t g_deep_parse_ms;
static uint64_t g_deep_stage_ms;
static uint64_t g_deep_slowest_ms;
static char g_deep_slowest_path[PATH_MAX];
""",
    'global deep metrics',
)
rep(
    """static int stat_tree_rec(const char *path, dev_t root_dev, uint64_t max_bytes, int days, const Options *o, Stats *s, unsigned depth) {
    if (depth > 512) { s->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
""",
    """static int stat_tree_rec(const char *path, dev_t root_dev, uint64_t max_bytes, int days, const Options *o, Stats *s, unsigned depth, uint64_t deadline_ms) {
    if (depth > 512) { s->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
    if (deadline_ms > 0U && monotonic_ms() >= deadline_ms) { s->timed_out = true; s->incomplete = true; return 124; }
""",
    'recursive deadline signature',
)
rep(
    """    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
""",
    """    while ((de = readdir(d)) != NULL) {
        if (deadline_ms > 0U && monotonic_ms() >= deadline_ms) { s->timed_out = true; s->incomplete = true; closedir(d); return 124; }
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
""",
    'loop deadline check',
)
rep(
    """        int rc = stat_tree_rec(child, root_dev, max_bytes, days, o, s, depth + 1);
        if (rc == 9) { closedir(d); return 9; }
""",
    """        int rc = stat_tree_rec(child, root_dev, max_bytes, days, o, s, depth + 1, deadline_ms);
        if (rc == 9 || rc == 124) { closedir(d); return rc; }
""",
    'recursive deadline pass',
)
rep(
    """static int stat_tree(const char *path, const Options *o, int days, Stats *s) {
    memset(s, 0, sizeof(*s));
    struct stat st;
    if (lstat(path, &st) != 0) return -1;
    return stat_tree_rec(path, st.st_dev, o->max_file_bytes, days, o, s, 0);
}
""",
    """static int stat_tree_budgeted(const char *path, const Options *o, int days, Stats *s, uint64_t budget_ms) {
    memset(s, 0, sizeof(*s));
    struct stat st;
    if (lstat(path, &st) != 0) return -1;
    uint64_t started_ms = monotonic_ms();
    uint64_t deadline_ms = budget_ms > 0U ? started_ms + budget_ms : 0U;
    int result = stat_tree_rec(path, st.st_dev, o->max_file_bytes, days, o, s, 0, deadline_ms);
    uint64_t finished_ms = monotonic_ms();
    s->elapsed_ms = finished_ms >= started_ms ? finished_ms - started_ms : 0U;
    return result;
}
static int stat_tree(const char *path, const Options *o, int days, Stats *s) {
    return stat_tree_budgeted(path, o, days, s, 0U);
}
""",
    'budgeted tree wrapper',
)
rep(
    """    fprintf(f,
        "files=%" PRIu64 "\\nbytes=%" PRIu64 "\\ndirs=%" PRIu64 "\\nempty_dirs=%" PRIu64 "\\nskipped=%" PRIu64 "\\nerrors=%" PRIu64 "\\nprotected_items=%" PRIu64 "\\nprotected_bytes=%" PRIu64 "\\ncandidates=%" PRIu64 "\\ntargets=%" PRIu64 "\\nrisk_low=%" PRIu64 "\\nrisk_medium=%" PRIu64 "\\nrisk_high=%" PRIu64 "\\nrisk_critical=%" PRIu64 "\\nmount_items=%" PRIu64 "\\ntruncated=%" PRIu64 "\\nwhitelisted=%" PRIu64 "\\nvisited_files=%" PRIu64 "\\nvisited_dirs=%" PRIu64 "\\npackage_index_entries=%" PRIu64 "\\npackage_index_files=%" PRIu64 "\\npackage_lookups=%" PRIu64 "\\nfirst_result_ms=%" PRIu64 "\\none_pass_app_dirs=%" PRIu64 "\\none_pass_installed_dirs=%" PRIu64 "\\none_pass_orphan_dirs=%" PRIu64 "\\nwhitelist_index_entries=%" PRIu64 "\\nwhitelist_index_queries=%" PRIu64 "\\nwhitelist_ancestor_hits=%" PRIu64 "\\nwhitelist_descendant_hits=%" PRIu64 "\\npruned_subtrees=%" PRIu64 "\\nelapsed_ms=%" PRIu64 "\\nitems_per_second=%" PRIu64 "\\nengine=native-c-arm64\\nversion=%s\\n",
""",
    """    char safe_slowest[PATH_MAX];
    snprintf(safe_slowest, sizeof(safe_slowest), "%s", g_deep_slowest_path);
    sanitize(safe_slowest);
    fprintf(f,
        "files=%" PRIu64 "\\nbytes=%" PRIu64 "\\ndirs=%" PRIu64 "\\nempty_dirs=%" PRIu64 "\\nskipped=%" PRIu64 "\\nerrors=%" PRIu64 "\\nprotected_items=%" PRIu64 "\\nprotected_bytes=%" PRIu64 "\\ncandidates=%" PRIu64 "\\ntargets=%" PRIu64 "\\nrisk_low=%" PRIu64 "\\nrisk_medium=%" PRIu64 "\\nrisk_high=%" PRIu64 "\\nrisk_critical=%" PRIu64 "\\nmount_items=%" PRIu64 "\\ntruncated=%" PRIu64 "\\nwhitelisted=%" PRIu64 "\\nvisited_files=%" PRIu64 "\\nvisited_dirs=%" PRIu64 "\\npackage_index_entries=%" PRIu64 "\\npackage_index_files=%" PRIu64 "\\npackage_lookups=%" PRIu64 "\\nfirst_result_ms=%" PRIu64 "\\ntimed_out_dirs=%" PRIu64 "\\ndeep_parse_ms=%" PRIu64 "\\ndeep_stage_ms=%" PRIu64 "\\ndeep_slowest_ms=%" PRIu64 "\\ndeep_slowest_path=%s\\none_pass_app_dirs=%" PRIu64 "\\none_pass_installed_dirs=%" PRIu64 "\\none_pass_orphan_dirs=%" PRIu64 "\\nwhitelist_index_entries=%" PRIu64 "\\nwhitelist_index_queries=%" PRIu64 "\\nwhitelist_ancestor_hits=%" PRIu64 "\\nwhitelist_descendant_hits=%" PRIu64 "\\npruned_subtrees=%" PRIu64 "\\nelapsed_ms=%" PRIu64 "\\nitems_per_second=%" PRIu64 "\\nengine=native-c-arm64\\nversion=%s\\n",
""",
    'summary metrics format',
)
rep(
    """        t->package_index_files, t->package_lookups, t->first_result_ms,
        t->one_pass_app_dirs, t->one_pass_installed_dirs, t->one_pass_orphan_dirs,
""",
    """        t->package_index_files, t->package_lookups, t->first_result_ms,
        t->timed_out_dirs, g_deep_parse_ms, g_deep_stage_ms, g_deep_slowest_ms, safe_slowest,
        t->one_pass_app_dirs, t->one_pass_installed_dirs, t->one_pass_orphan_dirs,
""",
    'summary metrics args',
)
rep(
    """    o->max_file_bytes = 256ULL * 1024ULL * 1024ULL;
""",
    """    o->max_file_bytes = 256ULL * 1024ULL * 1024ULL;
    o->dir_budget_ms = 8000U;
    o->global_budget_ms = 180000U;
""",
    'default native budgets',
)
rep(
    """        else if (strcmp(a, "--max-file-bytes") == 0) o->max_file_bytes = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--min-age-days") == 0) o->min_age_days = atoi(arg_value(argc, argv, &i));
""",
    """        else if (strcmp(a, "--max-file-bytes") == 0) o->max_file_bytes = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--dir-budget-ms") == 0) o->dir_budget_ms = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--global-budget-ms") == 0) o->global_budget_ms = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--min-age-days") == 0) o->min_age_days = atoi(arg_value(argc, argv, &i));
""",
    'parse native budgets',
)
rep(
    """    StrVec cand = {0};
    char *line = NULL;
""",
    """    StrVec cand = {0};
    uint64_t parse_started_ms = monotonic_ms();
    char *line = NULL;
""",
    'parse stage start',
)
rep(
    """    vec_sort_unique(&cand);
    FILE *rep = open_report(o->report_path);
""",
    """    vec_sort_unique(&cand);
    uint64_t parse_finished_ms = monotonic_ms();
    g_deep_parse_ms = parse_finished_ms >= parse_started_ms ? parse_finished_ms - parse_started_ms : 0U;
    FILE *rep = open_report(o->report_path);
""",
    'parse stage timing',
)
rep(
    """    char covered_all[PATH_MAX] = "", covered_protected[PATH_MAX] = "";
    time_t stage = time(NULL);
""",
    """    char covered_all[PATH_MAX] = "", covered_protected[PATH_MAX] = "";
    uint64_t stage_started_ms = monotonic_ms();
""",
    'native stage start',
)
rep(
    """        if (time(NULL) - stage >= 300) { t.truncated = 1; break; }
""",
    """        uint64_t stage_now_ms = monotonic_ms();
        if (o->global_budget_ms > 0U && stage_now_ms >= stage_started_ms && stage_now_ms - stage_started_ms >= o->global_budget_ms) { t.truncated = 1; break; }
""",
    'global native budget',
)
rep(
    """        Stats s;
        int rc = stat_tree(p, o, 0, &s);
        if (rc == 9) { if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); vec_free(&cand); return 9; }
        if (rc < 0 || s.incomplete) t.errors++;
""",
    """        Stats s;
        int rc = stat_tree_budgeted(p, o, 0, &s, o->dir_budget_ms);
        if (s.elapsed_ms > g_deep_slowest_ms) {
            g_deep_slowest_ms = s.elapsed_ms;
            snprintf(g_deep_slowest_path, sizeof(g_deep_slowest_path), "%s", p);
        }
        if (rc == 9) { if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); vec_free(&cand); return 9; }
        if (rc == 124 || s.timed_out) {
            t.timed_out_dirs++;
            t.protected_items++;
            report_row(rep, "protected", "slow", "深度规则", 1, 0, p);
            continue;
        }
        if (rc < 0 || s.incomplete) t.errors++;
""",
    'per-directory native budget',
)
rep(
    """    if (rep) fclose(rep);
    if (targets) fclose(targets);
    write_summary(o, &t);
""",
    """    uint64_t stage_finished_ms = monotonic_ms();
    g_deep_stage_ms = stage_finished_ms >= stage_started_ms ? stage_finished_ms - stage_started_ms : 0U;
    if (rep) fclose(rep);
    if (targets) fclose(targets);
    write_summary(o, &t);
""",
    'native stage final timing',
)
path.write_text(text)
print("v2.2.5 native deep budgets applied")
