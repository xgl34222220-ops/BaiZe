#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fnmatch.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define ENGINE_VERSION "43.1-alpha2-one-pass"
#define MAX_CANDIDATES 200000U

typedef struct { char **v; size_t n, cap; } StrVec;
typedef struct {
    uint64_t files, bytes, dirs, empty_dirs;
    uint64_t visited_files, visited_dirs;
    bool oversized, mount_conflict, incomplete;
} Stats;
typedef struct {
    const char *media_root, *data_root, *installed_root, *whitelist_path;
    const char *package_whitelist_path, *rules_path, *report_path, *targets_path;
    const char *items_path, *manifest_path, *summary_path, *progress_path, *stop_path;
    const char *corpse_report_path, *corpse_targets_path, *corpse_summary_path;
    uint64_t max_file_bytes;
    int min_age_days;
    bool allow_high_risk;
} Options;
typedef struct {
    uint64_t files, bytes, dirs, empty_dirs, skipped, errors;
    uint64_t protected_items, protected_bytes, candidates, targets;
    uint64_t risk_low, risk_medium, risk_high, risk_critical;
    uint64_t mount_items, truncated, whitelisted;
    uint64_t visited_files, visited_dirs;
    uint64_t package_index_entries, package_index_files, package_lookups;
    uint64_t first_result_ms;
    uint64_t one_pass_app_dirs, one_pass_installed_dirs, one_pass_orphan_dirs;
} Totals;

static StrVec g_whitelist = {0}, g_package_whitelist = {0}, g_installed_index = {0};
static time_t g_started;
static uint64_t g_started_ms;
static uint64_t g_last_progress_ms;

static void die(const char *msg) { fprintf(stderr, "%s\n", msg); exit(2); }
static void *xmalloc(size_t n) { void *p = malloc(n ? n : 1); if (!p) die("out of memory"); return p; }
static char *xstrdup(const char *s) { char *p = strdup(s ? s : ""); if (!p) die("out of memory"); return p; }
static void vec_add(StrVec *a, const char *s) {
    if (a->n >= MAX_CANDIDATES) return;
    if (a->n == a->cap) {
        a->cap = a->cap ? a->cap * 2 : 128;
        a->v = realloc(a->v, a->cap * sizeof(*a->v));
        if (!a->v) die("out of memory");
    }
    a->v[a->n++] = xstrdup(s);
}
static void vec_free(StrVec *a) {
    for (size_t i = 0; i < a->n; i++) free(a->v[i]);
    free(a->v);
    memset(a, 0, sizeof(*a));
}
static int cmp_str(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }
static uint64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000U + (uint64_t)ts.tv_nsec / 1000000U;
}
static bool vec_contains_sorted(const StrVec *a, const char *value) {
    size_t low = 0, high = a ? a->n : 0;
    while (low < high) {
        size_t mid = low + (high - low) / 2U;
        int cmp = strcmp(a->v[mid], value);
        if (cmp < 0) low = mid + 1U;
        else high = mid;
    }
    return a && low < a->n && strcmp(a->v[low], value) == 0;
}
static void mark_first_result(Totals *totals) {
    if (!totals || totals->first_result_ms != 0U || g_started_ms == 0U) return;
    uint64_t now = monotonic_ms();
    totals->first_result_ms = now >= g_started_ms ? now - g_started_ms : 0U;
}
static void vec_sort_unique(StrVec *a) {
    if (a->n < 2) return;
    qsort(a->v, a->n, sizeof(*a->v), cmp_str);
    size_t w = 1;
    for (size_t i = 1; i < a->n; i++) {
        if (strcmp(a->v[i], a->v[w - 1]) != 0) a->v[w++] = a->v[i];
        else free(a->v[i]);
    }
    a->n = w;
}
static bool file_exists(const char *p) { struct stat st; return lstat(p, &st) == 0; }
static bool is_dir_nofollow(const char *p) { struct stat st; return lstat(p, &st) == 0 && S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode); }
static bool has_glob(const char *s) { return strpbrk(s, "*?[") != NULL; }
static bool stop_requested(const Options *o) { return o->stop_path && access(o->stop_path, F_OK) == 0; }
static void sanitize(char *s) { for (; *s; s++) if (*s == '\t' || *s == '\r' || *s == '\n') *s = ' '; }
static void atomic_progress(const Options *o, const char *mode, const char *phase, uint64_t cur, uint64_t total, const char *path) {
    if (!o->progress_path) return;
    uint64_t now_ms = monotonic_ms();
    bool force = cur == 0U || (total > 0U && cur >= total);
    if (!force && g_last_progress_ms != 0U && now_ms >= g_last_progress_ms && now_ms - g_last_progress_ms < 250U) return;
    g_last_progress_ms = now_ms;
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s.tmp.%ld", o->progress_path, (long)getpid());
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    char clean[PATH_MAX];
    snprintf(clean, sizeof(clean), "%s", path ? path : "");
    sanitize(clean);
    fprintf(f, "mode=%s\nphase=%s\nstarted=%ld\nprogress_current=%" PRIu64 "\nprogress_total=%" PRIu64 "\ncurrent_path=%s\nengine=native-c-arm64\n", mode, phase, (long)g_started, cur, total, clean);
    fclose(f);
    rename(tmp, o->progress_path);
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
        while (e > p && isspace((unsigned char)e[-1])) *--e = '\0';
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
static bool package_whitelisted(const char *pkg) {
    return pkg && vec_contains_sorted(&g_package_whitelist, pkg);
}
static bool safe_package(const char *s) {
    bool dot = false;
    if (!s || !*s || strlen(s) > 255) return false;
    for (; *s; s++) {
        if (*s == '.') dot = true;
        else if (!(isalnum((unsigned char)*s) || *s == '_' || *s == '-')) return false;
    }
    return dot;
}
static void load_installed_index(const char *root, Totals *totals) {
    if (!root) return;
    DIR *directory = opendir(root);
    if (!directory) return;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        size_t length = strlen(entry->d_name);
        if (length <= 4U || strcmp(entry->d_name + length - 4U, ".txt") != 0) continue;
        char user[128];
        size_t user_length = length - 4U;
        if (user_length == 0U || user_length >= sizeof(user)) continue;
        memcpy(user, entry->d_name, user_length);
        user[user_length] = '\0';
        for (size_t i = 0; i < user_length; i++) {
            if (!isdigit((unsigned char)user[i])) { user[0] = '\0'; break; }
        }
        if (!user[0]) continue;
        char path[PATH_MAX];
        int written = snprintf(path, sizeof(path), "%s/%s", root, entry->d_name);
        if (written < 0 || (size_t)written >= sizeof(path)) continue;
        FILE *file = fopen(path, "r");
        if (!file) continue;
        if (totals) totals->package_index_files++;
        char *line = NULL;
        size_t capacity = 0;
        while (getline(&line, &capacity, file) >= 0) {
            char *begin = line;
            while (isspace((unsigned char)*begin)) begin++;
            char *finish = begin + strlen(begin);
            while (finish > begin && isspace((unsigned char)finish[-1])) *--finish = '\0';
            if (!safe_package(begin)) continue;
            char key[512];
            written = snprintf(key, sizeof(key), "%s\t%s", user, begin);
            if (written < 0 || (size_t)written >= sizeof(key)) continue;
            vec_add(&g_installed_index, key);
        }
        free(line);
        fclose(file);
    }
    closedir(directory);
    vec_sort_unique(&g_installed_index);
    if (totals) totals->package_index_entries = (uint64_t)g_installed_index.n;
}
static bool installed_index_contains(const char *user, const char *pkg, Totals *totals) {
    if (totals) totals->package_lookups++;
    char key[512];
    int written = snprintf(key, sizeof(key), "%s\t%s", user ? user : "", pkg ? pkg : "");
    if (written < 0 || (size_t)written >= sizeof(key)) return false;
    return vec_contains_sorted(&g_installed_index, key);
}
static bool eligible_mtime(const struct stat *st, int days) {
    if (days <= 0) return true;
    time_t cutoff = time(NULL) - (time_t)days * 86400;
    return st->st_mtime <= cutoff;
}
static int stat_tree_rec(const char *path, dev_t root_dev, uint64_t max_bytes, int days, const Options *o, Stats *s, unsigned depth) {
    if (depth > 512) { s->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
    struct stat st;
    if (lstat(path, &st) != 0) { s->incomplete = true; return -1; }
    if (S_ISLNK(st.st_mode)) return 0;
    if (S_ISREG(st.st_mode)) {
        s->visited_files++;
        if (eligible_mtime(&st, days)) {
            s->files++;
            s->bytes += (uint64_t)st.st_size;
            if ((uint64_t)st.st_size > max_bytes) s->oversized = true;
        }
        return 0;
    }
    if (!S_ISDIR(st.st_mode)) return 0;
    s->visited_dirs++;
    if (depth > 0 && st.st_dev != root_dev) { s->mount_conflict = true; return 0; }
    DIR *d = opendir(path);
    if (!d) { s->incomplete = true; return -1; }
    uint64_t before = s->files;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        char child[PATH_MAX];
        int n = snprintf(child, sizeof(child), "%s/%s", path, de->d_name);
        if (n < 0 || (size_t)n >= sizeof(child)) { s->incomplete = true; continue; }
        int rc = stat_tree_rec(child, root_dev, max_bytes, days, o, s, depth + 1);
        if (rc == 9) { closedir(d); return 9; }
    }
    closedir(d);
    s->dirs++;
    if (s->files == before) s->empty_dirs++;
    return 0;
}
static int stat_tree(const char *path, const Options *o, int days, Stats *s) {
    memset(s, 0, sizeof(*s));
    struct stat st;
    if (lstat(path, &st) != 0) return -1;
    return stat_tree_rec(path, st.st_dev, o->max_file_bytes, days, o, s, 0);
}
static FILE *open_report(const char *p) {
    FILE *f = fopen(p, "w");
    if (f) fprintf(f, "action\trisk\tcategory\titems\tbytes\tpath\n");
    return f;
}
static void report_row(FILE *f, const char *a, const char *r, const char *c, uint64_t items, uint64_t bytes, const char *p) {
    if (!f) return;
    char cc[256], pp[PATH_MAX];
    snprintf(cc, sizeof(cc), "%s", c);
    snprintf(pp, sizeof(pp), "%s", p);
    sanitize(cc);
    sanitize(pp);
    fprintf(f, "%s\t%s\t%s\t%" PRIu64 "\t%" PRIu64 "\t%s\n", a, r, cc, items, bytes, pp);
}
static void write_summary_path(const char *path, const Totals *t) {
    if (!path) return;
    FILE *f = fopen(path, "w");
    if (!f) return;
    uint64_t now_ms = monotonic_ms();
    uint64_t elapsed_ms = now_ms >= g_started_ms ? now_ms - g_started_ms : 0U;
    uint64_t visited = t->visited_files + t->visited_dirs;
    uint64_t throughput = elapsed_ms > 0U ? visited * 1000U / elapsed_ms : 0U;
    fprintf(f,
        "files=%" PRIu64 "\nbytes=%" PRIu64 "\ndirs=%" PRIu64 "\nempty_dirs=%" PRIu64 "\nskipped=%" PRIu64 "\nerrors=%" PRIu64 "\nprotected_items=%" PRIu64 "\nprotected_bytes=%" PRIu64 "\ncandidates=%" PRIu64 "\ntargets=%" PRIu64 "\nrisk_low=%" PRIu64 "\nrisk_medium=%" PRIu64 "\nrisk_high=%" PRIu64 "\nrisk_critical=%" PRIu64 "\nmount_items=%" PRIu64 "\ntruncated=%" PRIu64 "\nwhitelisted=%" PRIu64 "\nvisited_files=%" PRIu64 "\nvisited_dirs=%" PRIu64 "\npackage_index_entries=%" PRIu64 "\npackage_index_files=%" PRIu64 "\npackage_lookups=%" PRIu64 "\nfirst_result_ms=%" PRIu64 "\none_pass_app_dirs=%" PRIu64 "\none_pass_installed_dirs=%" PRIu64 "\none_pass_orphan_dirs=%" PRIu64 "\nelapsed_ms=%" PRIu64 "\nitems_per_second=%" PRIu64 "\nengine=native-c-arm64\nversion=%s\n",
        t->files, t->bytes, t->dirs, t->empty_dirs, t->skipped, t->errors,
        t->protected_items, t->protected_bytes, t->candidates, t->targets,
        t->risk_low, t->risk_medium, t->risk_high, t->risk_critical,
        t->mount_items, t->truncated, t->whitelisted,
        t->visited_files, t->visited_dirs, t->package_index_entries,
        t->package_index_files, t->package_lookups, t->first_result_ms,
        t->one_pass_app_dirs, t->one_pass_installed_dirs, t->one_pass_orphan_dirs,
        elapsed_ms, throughput, ENGINE_VERSION);
    fclose(f);
}
static void write_summary(const Options *o, const Totals *t) {
    write_summary_path(o->summary_path, t);
}
static const char *arg_value(int argc, char **argv, int *i) {
    if (*i + 1 >= argc) die("missing option value");
    return argv[++*i];
}
static void parse_options(int argc, char **argv, Options *o) {
    memset(o, 0, sizeof(*o));
    o->media_root = "/data/media";
    o->data_root = "/data";
    o->max_file_bytes = 256ULL * 1024ULL * 1024ULL;
    for (int i = 2; i < argc; i++) {
        const char *a = argv[i];
        if (strcmp(a, "--media-root") == 0) o->media_root = arg_value(argc, argv, &i);
        else if (strcmp(a, "--data-root") == 0) o->data_root = arg_value(argc, argv, &i);
        else if (strcmp(a, "--installed-root") == 0) o->installed_root = arg_value(argc, argv, &i);
        else if (strcmp(a, "--whitelist") == 0) o->whitelist_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--package-whitelist") == 0) o->package_whitelist_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--rules") == 0) o->rules_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--report") == 0) o->report_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--targets") == 0) o->targets_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--items") == 0) o->items_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--manifest") == 0) o->manifest_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--summary") == 0) o->summary_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--progress") == 0) o->progress_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--stop") == 0) o->stop_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--corpse-report") == 0) o->corpse_report_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--corpse-targets") == 0) o->corpse_targets_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--corpse-summary") == 0) o->corpse_summary_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--max-file-bytes") == 0) o->max_file_bytes = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--min-age-days") == 0) o->min_age_days = atoi(arg_value(argc, argv, &i));
        else if (strcmp(a, "--allow-high-risk") == 0) o->allow_high_risk = atoi(arg_value(argc, argv, &i)) != 0;
        else die("unknown option");
    }
}
static void require_outputs(const Options *o) {
    if (!o->report_path || !o->targets_path || !o->summary_path) die("missing output paths");
}

static int scan_corpses(const Options *o) {
    require_outputs(o);
    if (!o->installed_root) die("installed root required");
    load_lines(o->whitelist_path, &g_whitelist, true);
    FILE *rep = open_report(o->report_path);
    FILE *targets = fopen(o->targets_path, "w");
    Totals t = {0};
    load_installed_index(o->installed_root, &t);
    DIR *users = opendir(o->media_root);
    if (!users) { if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); return 0; }
    struct dirent *ue;
    uint64_t current = 0;
    while ((ue = readdir(users)) != NULL) {
        if (!isdigit((unsigned char)ue->d_name[0])) continue;
        char android[PATH_MAX];
        snprintf(android, sizeof(android), "%s/%s/Android", o->media_root, ue->d_name);
        const char *sub[] = {"data", "obb", "media"};
        for (size_t si = 0; si < 3; si++) {
            char root[PATH_MAX];
            snprintf(root, sizeof(root), "%s/%s", android, sub[si]);
            DIR *d = opendir(root);
            if (!d) continue;
            struct dirent *de;
            while ((de = readdir(d)) != NULL) {
                if (de->d_name[0] == '.' || !safe_package(de->d_name)) continue;
                if (stop_requested(o)) { closedir(d); closedir(users); if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); return 9; }
                if (installed_index_contains(ue->d_name, de->d_name, &t)) continue;
                char path[PATH_MAX];
                snprintf(path, sizeof(path), "%s/%s", root, de->d_name);
                if (!is_dir_nofollow(path)) { t.skipped++; continue; }
                current++;
                atomic_progress(o, "corpse-scan", "C 原生卸载残留扫描", current, 0, path);
                if (whitelist_conflict(path)) { t.skipped++; t.whitelisted++; report_row(rep, "skipped", "protected", "卸载残留", 0, 0, path); continue; }
                Stats st;
                int rc = stat_tree(path, o, 0, &st);
                if (rc == 9) { closedir(d); closedir(users); if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); return 9; }
                if (rc < 0 || st.incomplete) t.errors++;
                t.visited_files += st.visited_files;
                t.visited_dirs += st.visited_dirs;
                uint64_t item_count = st.files ? st.files : 1;
                if (st.oversized || st.mount_conflict) {
                    t.protected_items += item_count;
                    t.protected_bytes += st.bytes;
                    if (st.mount_conflict) t.mount_items++;
                    report_row(rep, "protected", "high", "卸载残留", item_count, st.bytes, path);
                    continue;
                }
                t.files += st.files;
                t.bytes += st.bytes;
                t.dirs += st.dirs;
                t.empty_dirs += (st.files == 0);
                t.candidates++;
                t.targets++;
                mark_first_result(&t);
                report_row(rep, "candidate", "high", "卸载残留", item_count, st.bytes, path);
                if (targets) fprintf(targets, "%s\n", path);
            }
            closedir(d);
        }
    }
    closedir(users);
    if (rep) fclose(rep);
    if (targets) fclose(targets);
    write_summary(o, &t);
    return 0;
}


static int corpse_candidate(const Options *o, FILE *report, FILE *targets, Totals *totals,
                            const char *path, const char *category, const char *progress_phase,
                            uint64_t current) {
    if (!is_dir_nofollow(path)) { totals->skipped++; return 0; }
    atomic_progress(o, "one-pass-scan", progress_phase, current, 0, path);
    if (whitelist_conflict(path)) {
        totals->skipped++;
        totals->whitelisted++;
        report_row(report, "skipped", "protected", category, 0, 0, path);
        return 0;
    }
    Stats stats;
    int code = stat_tree(path, o, 0, &stats);
    if (code == 9) return 9;
    if (code < 0 || stats.incomplete) totals->errors++;
    totals->visited_files += stats.visited_files;
    totals->visited_dirs += stats.visited_dirs;
    uint64_t item_count = stats.files ? stats.files : 1U;
    if (stats.oversized || stats.mount_conflict || stats.incomplete) {
        totals->protected_items += item_count;
        totals->protected_bytes += stats.bytes;
        if (stats.mount_conflict) totals->mount_items++;
        report_row(report, "protected", "high", category, item_count, stats.bytes, path);
        return 0;
    }
    totals->files += stats.files;
    totals->bytes += stats.bytes;
    totals->dirs += stats.dirs;
    totals->empty_dirs += stats.files == 0U ? 1U : 0U;
    totals->candidates++;
    totals->targets++;
    mark_first_result(totals);
    report_row(report, "candidate", "high", category, item_count, stats.bytes, path);
    if (targets) fprintf(targets, "%s\n", path);
    return 0;
}

static bool write_nul_field(FILE *file, const char *value) {
    if (!file || !value) return false;
    size_t length = strlen(value) + 1U;
    return fwrite(value, 1, length, file) == length;
}
static bool write_nul_u64(FILE *file, uint64_t value) {
    char text[32];
    snprintf(text, sizeof(text), "%" PRIu64, value);
    return write_nul_field(file, text);
}
static int snapshot_cache_rec(const char *path, dev_t root_dev, const Options *o, int days,
                              FILE *manifest, const char *pkg, const char *category,
                              Stats *stats, unsigned depth) {
    if (depth > 512U) { stats->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
    struct stat st;
    if (lstat(path, &st) != 0) { stats->incomplete = true; return -1; }
    if (S_ISLNK(st.st_mode)) return 0;
    if (S_ISREG(st.st_mode)) {
        stats->visited_files++;
        if (!eligible_mtime(&st, days)) return 0;
        stats->files++;
        uint64_t size = st.st_size > 0 ? (uint64_t)st.st_size : 0U;
        stats->bytes += size;
        if (size > o->max_file_bytes) stats->oversized = true;
        if (!write_nul_field(manifest, pkg) || !write_nul_field(manifest, category) ||
            !write_nul_u64(manifest, (uint64_t)st.st_dev) ||
            !write_nul_u64(manifest, (uint64_t)st.st_ino) ||
            !write_nul_u64(manifest, size) ||
            !write_nul_u64(manifest, (uint64_t)st.st_mtim.tv_sec) ||
            !write_nul_u64(manifest, (uint64_t)st.st_mtim.tv_nsec) ||
            !write_nul_u64(manifest, (uint64_t)st.st_ctim.tv_sec) ||
            !write_nul_u64(manifest, (uint64_t)st.st_ctim.tv_nsec) ||
            !write_nul_field(manifest, path)) {
            stats->incomplete = true;
            return -1;
        }
        return 0;
    }
    if (!S_ISDIR(st.st_mode)) return 0;
    stats->visited_dirs++;
    if (depth > 0U && st.st_dev != root_dev) { stats->mount_conflict = true; return 0; }
    DIR *dir = opendir(path);
    if (!dir) { stats->incomplete = true; return -1; }
    uint64_t before = stats->files;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char child[PATH_MAX];
        int written = snprintf(child, sizeof(child), "%s/%s", path, entry->d_name);
        if (written < 0 || (size_t)written >= sizeof(child)) { stats->incomplete = true; continue; }
        int code = snapshot_cache_rec(child, root_dev, o, days, manifest, pkg, category, stats, depth + 1U);
        if (code == 9) { closedir(dir); return 9; }
    }
    closedir(dir);
    stats->dirs++;
    if (stats->files == before) stats->empty_dirs++;
    return 0;
}
static int snapshot_cache_tree(const char *path, const Options *o, const char *pkg,
                               const char *category, FILE *manifest, Stats *stats) {
    memset(stats, 0, sizeof(*stats));
    struct stat root;
    if (lstat(path, &root) != 0 || !S_ISDIR(root.st_mode) || S_ISLNK(root.st_mode)) return -1;
    FILE *temporary = tmpfile();
    if (!temporary) return -1;
    int code = snapshot_cache_rec(path, root.st_dev, o, o->min_age_days, temporary, pkg, category, stats, 0U);
    if (code == 0 && !stats->oversized && !stats->mount_conflict && !stats->incomplete && stats->files > 0U) {
        rewind(temporary);
        char buffer[16384];
        size_t count;
        while ((count = fread(buffer, 1, sizeof(buffer), temporary)) > 0U) {
            if (fwrite(buffer, 1, count, manifest) != count) {
                stats->incomplete = true;
                code = -1;
                break;
            }
        }
    }
    fclose(temporary);
    return code;
}
static void cache_candidate(const Options *o, FILE *rep, FILE *targets, FILE *items, FILE *manifest,
                            Totals *totals, const char *pkg, const char *category,
                            const char *path, uint64_t current) {
    if (!is_dir_nofollow(path)) return;
    atomic_progress(o, "cache-scan", "C 原生应用缓存扫描", current, 0, path);
    if (package_whitelisted(pkg) || whitelist_conflict(path)) {
        totals->skipped++;
        totals->whitelisted++;
        report_row(rep, "skipped", "protected", category, 0, 0, path);
        return;
    }
    Stats stats;
    int code = snapshot_cache_tree(path, o, pkg, category, manifest, &stats);
    if (code == 9) return;
    if (code < 0 || stats.incomplete) totals->errors++;
    totals->visited_files += stats.visited_files;
    totals->visited_dirs += stats.visited_dirs;
    if (stats.files == 0U) return;
    uint64_t count = stats.files;
    if (stats.oversized || stats.mount_conflict || stats.incomplete) {
        totals->protected_items += count;
        totals->protected_bytes += stats.bytes;
        if (stats.mount_conflict) totals->mount_items++;
        report_row(rep, "protected", "low", category, count, stats.bytes, path);
        return;
    }
    totals->files += stats.files;
    totals->bytes += stats.bytes;
    totals->dirs += stats.dirs;
    totals->candidates++;
    totals->targets++;
    mark_first_result(totals);
    report_row(rep, "candidate", "low", category, count, stats.bytes, path);
    if (targets) fprintf(targets, "%s\n", path);
    if (items) {
        fprintf(items, "%s\t%s\t%" PRIu64 "\t%" PRIu64 "\t%" PRIu64 "\t%s\n",
                pkg, category, stats.files, stats.bytes, stats.dirs, path);
    }
}
static void scan_cache_user_root(const Options *o, const char *root, const char *prefix,
                                 FILE *rep, FILE *targets, FILE *items, FILE *manifest,
                                 Totals *totals, uint64_t *current) {
    DIR *users = opendir(root);
    if (!users) return;
    struct dirent *user_entry;
    while ((user_entry = readdir(users)) != NULL) {
        if (!isdigit((unsigned char)user_entry->d_name[0])) continue;
        char user[PATH_MAX];
        snprintf(user, sizeof(user), "%s/%s", root, user_entry->d_name);
        DIR *apps = opendir(user);
        if (!apps) continue;
        struct dirent *app_entry;
        while ((app_entry = readdir(apps)) != NULL) {
            if (app_entry->d_name[0] == '.' || !safe_package(app_entry->d_name)) continue;
            char app[PATH_MAX];
            snprintf(app, sizeof(app), "%s/%s", user, app_entry->d_name);
            const char *leaves[] = {"cache", "code_cache"};
            for (size_t i = 0; i < 2U; i++) {
                if (stop_requested(o)) { closedir(apps); closedir(users); return; }
                char path[PATH_MAX];
                snprintf(path, sizeof(path), "%s/%s", app, leaves[i]);
                (*current)++;
                char category[320];
                snprintf(category, sizeof(category), "%s:%s", prefix, app_entry->d_name);
                cache_candidate(o, rep, targets, items, manifest, totals,
                                app_entry->d_name, category, path, *current);
            }
        }
        closedir(apps);
    }
    closedir(users);
}
static int scan_cache(const Options *o) {
    require_outputs(o);
    if (!o->manifest_path) die("cache manifest required");
    load_lines(o->whitelist_path, &g_whitelist, true);
    load_lines(o->package_whitelist_path, &g_package_whitelist, false);
    FILE *report = open_report(o->report_path);
    FILE *targets = fopen(o->targets_path, "w");
    FILE *items = o->items_path ? fopen(o->items_path, "w") : NULL;
    FILE *manifest = fopen(o->manifest_path, "wb");
    if (!report || !targets || !items || !manifest) {
        if (report) fclose(report);
        if (targets) fclose(targets);
        if (items) fclose(items);
        if (manifest) fclose(manifest);
        return 71;
    }
    fprintf(items, "package\tcategory\tfiles\tbytes\tdirectories\tpath\n");
    Totals totals = {0};
    uint64_t current = 0;
    char root[PATH_MAX];
    snprintf(root, sizeof(root), "%s/user", o->data_root);
    scan_cache_user_root(o, root, "内部应用缓存", report, targets, items, manifest, &totals, &current);
    if (stop_requested(o)) goto stopped;
    snprintf(root, sizeof(root), "%s/user_de", o->data_root);
    scan_cache_user_root(o, root, "设备保护缓存", report, targets, items, manifest, &totals, &current);
    if (stop_requested(o)) goto stopped;
    DIR *users = opendir(o->media_root);
    if (users) {
        struct dirent *user_entry;
        while ((user_entry = readdir(users)) != NULL) {
            if (!isdigit((unsigned char)user_entry->d_name[0])) continue;
            char apps_root[PATH_MAX];
            snprintf(apps_root, sizeof(apps_root), "%s/%s/Android/data", o->media_root, user_entry->d_name);
            DIR *apps = opendir(apps_root);
            if (!apps) continue;
            struct dirent *app_entry;
            while ((app_entry = readdir(apps)) != NULL) {
                if (app_entry->d_name[0] == '.' || !safe_package(app_entry->d_name)) continue;
                char app[PATH_MAX];
                snprintf(app, sizeof(app), "%s/%s", apps_root, app_entry->d_name);
                const char *leaves[] = {"cache", "code_cache"};
                for (size_t i = 0; i < 2U; i++) {
                    if (stop_requested(o)) { closedir(apps); closedir(users); goto stopped; }
                    char path[PATH_MAX];
                    snprintf(path, sizeof(path), "%s/%s", app, leaves[i]);
                    current++;
                    char category[320];
                    snprintf(category, sizeof(category), "外部应用缓存:%s", app_entry->d_name);
                    cache_candidate(o, report, targets, items, manifest, &totals,
                                    app_entry->d_name, category, path, current);
                }
            }
            closedir(apps);
        }
        closedir(users);
    }
    fflush(manifest);
    fsync(fileno(manifest));
    fclose(report);
    fclose(targets);
    fclose(items);
    fclose(manifest);
    write_summary(o, &totals);
    return 0;
stopped:
    fclose(report);
    fclose(targets);
    fclose(items);
    fclose(manifest);
    write_summary(o, &totals);
    return 9;
}

static int scan_external_one_pass(const Options *o) {
    require_outputs(o);
    if (!o->installed_root || !o->manifest_path || !o->corpse_report_path ||
        !o->corpse_targets_path || !o->corpse_summary_path) {
        die("one-pass output paths required");
    }
    load_lines(o->whitelist_path, &g_whitelist, true);
    load_lines(o->package_whitelist_path, &g_package_whitelist, false);
    FILE *cache_report = open_report(o->report_path);
    FILE *cache_targets = fopen(o->targets_path, "w");
    FILE *cache_items = o->items_path ? fopen(o->items_path, "w") : NULL;
    FILE *cache_manifest = fopen(o->manifest_path, "wb");
    FILE *corpse_report = open_report(o->corpse_report_path);
    FILE *corpse_targets = fopen(o->corpse_targets_path, "w");
    if (!cache_report || !cache_targets || !cache_items || !cache_manifest ||
        !corpse_report || !corpse_targets) {
        if (cache_report) fclose(cache_report);
        if (cache_targets) fclose(cache_targets);
        if (cache_items) fclose(cache_items);
        if (cache_manifest) fclose(cache_manifest);
        if (corpse_report) fclose(corpse_report);
        if (corpse_targets) fclose(corpse_targets);
        return 71;
    }
    fprintf(cache_items, "package\tcategory\tfiles\tbytes\tdirectories\tpath\n");
    Totals cache_totals = {0};
    Totals corpse_totals = {0};
    load_installed_index(o->installed_root, &cache_totals);
    uint64_t current = 0;

    char root[PATH_MAX];
    snprintf(root, sizeof(root), "%s/user", o->data_root);
    scan_cache_user_root(o, root, "内部应用缓存", cache_report, cache_targets,
                         cache_items, cache_manifest, &cache_totals, &current);
    if (stop_requested(o)) goto stopped;
    snprintf(root, sizeof(root), "%s/user_de", o->data_root);
    scan_cache_user_root(o, root, "设备保护缓存", cache_report, cache_targets,
                         cache_items, cache_manifest, &cache_totals, &current);
    if (stop_requested(o)) goto stopped;

    DIR *users = opendir(o->media_root);
    if (users) {
        struct dirent *user_entry;
        while ((user_entry = readdir(users)) != NULL) {
            if (!isdigit((unsigned char)user_entry->d_name[0])) continue;
            char android_root[PATH_MAX];
            snprintf(android_root, sizeof(android_root), "%s/%s/Android", o->media_root, user_entry->d_name);
            char data_root[PATH_MAX];
            snprintf(data_root, sizeof(data_root), "%s/data", android_root);
            DIR *apps = opendir(data_root);
            if (apps) {
                struct dirent *app_entry;
                while ((app_entry = readdir(apps)) != NULL) {
                    if (app_entry->d_name[0] == '.' || !safe_package(app_entry->d_name)) continue;
                    if (stop_requested(o)) { closedir(apps); closedir(users); goto stopped; }
                    char app_path[PATH_MAX];
                    snprintf(app_path, sizeof(app_path), "%s/%s", data_root, app_entry->d_name);
                    if (!is_dir_nofollow(app_path)) { cache_totals.skipped++; continue; }
                    current++;
                    cache_totals.one_pass_app_dirs++;
                    corpse_totals.one_pass_app_dirs++;
                    bool installed = installed_index_contains(user_entry->d_name, app_entry->d_name, &cache_totals);
                    if (installed) {
                        cache_totals.one_pass_installed_dirs++;
                        corpse_totals.one_pass_installed_dirs++;
                        const char *leaves[] = {"cache", "code_cache"};
                        for (size_t i = 0; i < 2U; i++) {
                            char cache_path[PATH_MAX];
                            snprintf(cache_path, sizeof(cache_path), "%s/%s", app_path, leaves[i]);
                            char category[320];
                            snprintf(category, sizeof(category), "外部应用缓存:%s", app_entry->d_name);
                            cache_candidate(o, cache_report, cache_targets, cache_items, cache_manifest,
                                            &cache_totals, app_entry->d_name, category, cache_path, current);
                        }
                    } else {
                        cache_totals.one_pass_orphan_dirs++;
                        corpse_totals.one_pass_orphan_dirs++;
                        int code = corpse_candidate(o, corpse_report, corpse_targets, &corpse_totals,
                                                    app_path, "卸载残留:data",
                                                    "C 原生外部目录 One-pass", current);
                        if (code == 9) { closedir(apps); closedir(users); goto stopped; }
                    }
                }
                closedir(apps);
            }
            const char *secondary[] = {"obb", "media"};
            for (size_t si = 0; si < 2U; si++) {
                char secondary_root[PATH_MAX];
                snprintf(secondary_root, sizeof(secondary_root), "%s/%s", android_root, secondary[si]);
                DIR *entries = opendir(secondary_root);
                if (!entries) continue;
                struct dirent *entry;
                while ((entry = readdir(entries)) != NULL) {
                    if (entry->d_name[0] == '.' || !safe_package(entry->d_name)) continue;
                    if (stop_requested(o)) { closedir(entries); closedir(users); goto stopped; }
                    current++;
                    if (installed_index_contains(user_entry->d_name, entry->d_name, &cache_totals)) continue;
                    char path[PATH_MAX];
                    snprintf(path, sizeof(path), "%s/%s", secondary_root, entry->d_name);
                    char category[64];
                    snprintf(category, sizeof(category), "卸载残留:%s", secondary[si]);
                    int code = corpse_candidate(o, corpse_report, corpse_targets, &corpse_totals,
                                                path, category, "C 原生卸载残留补充扫描", current);
                    if (code == 9) { closedir(entries); closedir(users); goto stopped; }
                }
                closedir(entries);
            }
        }
        closedir(users);
    }

    corpse_totals.package_index_entries = cache_totals.package_index_entries;
    corpse_totals.package_index_files = cache_totals.package_index_files;
    corpse_totals.package_lookups = cache_totals.package_lookups;
    fflush(cache_manifest);
    fsync(fileno(cache_manifest));
    fclose(cache_report);
    fclose(cache_targets);
    fclose(cache_items);
    fclose(cache_manifest);
    fclose(corpse_report);
    fclose(corpse_targets);
    write_summary_path(o->summary_path, &cache_totals);
    write_summary_path(o->corpse_summary_path, &corpse_totals);
    return 0;

stopped:
    corpse_totals.package_index_entries = cache_totals.package_index_entries;
    corpse_totals.package_index_files = cache_totals.package_index_files;
    corpse_totals.package_lookups = cache_totals.package_lookups;
    fclose(cache_report);
    fclose(cache_targets);
    fclose(cache_items);
    fclose(cache_manifest);
    fclose(corpse_report);
    fclose(corpse_targets);
    write_summary_path(o->summary_path, &cache_totals);
    write_summary_path(o->corpse_summary_path, &corpse_totals);
    return 9;
}

static int read_nul_field(FILE *file, char **value, size_t *capacity) {
    ssize_t length = getdelim(value, capacity, '\0', file);
    if (length < 0) return feof(file) ? 0 : -1;
    if (length == 0 || (*value)[length - 1] != '\0') return -1;
    (*value)[length - 1] = '\0';
    return 1;
}
static bool parse_u64_value(const char *text, uint64_t *value) {
    if (!text || !*text) return false;
    char *end = NULL;
    errno = 0;
    unsigned long long parsed = strtoull(text, &end, 10);
    if (errno != 0 || !end || *end != '\0') return false;
    *value = (uint64_t)parsed;
    return true;
}
static bool next_segment(const char **cursor, char *output, size_t capacity) {
    const char *start = *cursor;
    if (!start || !*start) return false;
    const char *slash = strchr(start, '/');
    size_t length = slash ? (size_t)(slash - start) : strlen(start);
    if (length == 0U || length + 1U > capacity) return false;
    memcpy(output, start, length);
    output[length] = '\0';
    *cursor = slash ? slash + 1 : start + length;
    return true;
}
static bool numeric_segment(const char *value) {
    if (!value || !*value) return false;
    for (const unsigned char *p = (const unsigned char *)value; *p; p++) {
        if (!isdigit(*p)) return false;
    }
    return true;
}
static bool safe_relative_tail(const char *tail) {
    if (!tail || !*tail || tail[0] == '/') return false;
    if (strcmp(tail, ".") == 0 || strcmp(tail, "..") == 0) return false;
    if (strstr(tail, "/../") || strstr(tail, "/./")) return false;
    size_t length = strlen(tail);
    if (length >= 3U && strcmp(tail + length - 3U, "/..") == 0) return false;
    if (length >= 2U && strcmp(tail + length - 2U, "/.") == 0) return false;
    return true;
}
static bool cache_path_matches_package(const Options *o, const char *path, const char *pkg) {
    if (!path || !pkg || !safe_package(pkg)) return false;
    const char *cursor = NULL;
    size_t data_length = strlen(o->data_root);
    size_t media_length = strlen(o->media_root);
    bool external = false;
    if (strncmp(path, o->data_root, data_length) == 0 && path[data_length] == '/') {
        cursor = path + data_length + 1U;
    } else if (strncmp(path, o->media_root, media_length) == 0 && path[media_length] == '/') {
        cursor = path + media_length + 1U;
        external = true;
    } else {
        return false;
    }
    char segment[512];
    if (external) {
        if (!next_segment(&cursor, segment, sizeof(segment)) || !numeric_segment(segment)) return false;
        if (!next_segment(&cursor, segment, sizeof(segment)) || strcmp(segment, "Android") != 0) return false;
        if (!next_segment(&cursor, segment, sizeof(segment)) || strcmp(segment, "data") != 0) return false;
    } else {
        if (!next_segment(&cursor, segment, sizeof(segment)) ||
            (strcmp(segment, "user") != 0 && strcmp(segment, "user_de") != 0)) return false;
        if (!next_segment(&cursor, segment, sizeof(segment)) || !numeric_segment(segment)) return false;
    }
    if (!next_segment(&cursor, segment, sizeof(segment)) || strcmp(segment, pkg) != 0) return false;
    if (!next_segment(&cursor, segment, sizeof(segment)) ||
        (strcmp(segment, "cache") != 0 && strcmp(segment, "code_cache") != 0)) return false;
    return safe_relative_tail(cursor);
}
typedef struct {
    char *field[10];
    size_t capacity[10];
} ManifestRecord;
static void manifest_record_free(ManifestRecord *record) {
    for (size_t i = 0; i < 10U; i++) free(record->field[i]);
    memset(record, 0, sizeof(*record));
}
static int manifest_record_read(FILE *file, ManifestRecord *record) {
    int first = read_nul_field(file, &record->field[0], &record->capacity[0]);
    if (first <= 0) return first;
    for (size_t i = 1; i < 10U; i++) {
        if (read_nul_field(file, &record->field[i], &record->capacity[i]) != 1) return -1;
    }
    return 1;
}
static bool stat_matches_manifest(const struct stat *st, uint64_t dev, uint64_t ino, uint64_t size,
                                  uint64_t mtime_sec, uint64_t mtime_nsec,
                                  uint64_t ctime_sec, uint64_t ctime_nsec) {
    uint64_t actual_size = st->st_size > 0 ? (uint64_t)st->st_size : 0U;
    return S_ISREG(st->st_mode) && !S_ISLNK(st->st_mode) &&
           (uint64_t)st->st_dev == dev && (uint64_t)st->st_ino == ino &&
           actual_size == size &&
           (uint64_t)st->st_mtim.tv_sec == mtime_sec &&
           (uint64_t)st->st_mtim.tv_nsec == mtime_nsec &&
           (uint64_t)st->st_ctim.tv_sec == ctime_sec &&
           (uint64_t)st->st_ctim.tv_nsec == ctime_nsec;
}
static int clean_cache_snapshot(const Options *o) {
    if (!o->manifest_path || !o->report_path || !o->summary_path) die("missing cache clean paths");
    load_lines(o->whitelist_path, &g_whitelist, true);
    load_lines(o->package_whitelist_path, &g_package_whitelist, false);
    FILE *manifest = fopen(o->manifest_path, "rb");
    FILE *report = open_report(o->report_path);
    if (!manifest || !report) {
        if (manifest) fclose(manifest);
        if (report) fclose(report);
        return 71;
    }
    ManifestRecord record = {0};
    uint64_t total = 0;
    int read_code;
    while ((read_code = manifest_record_read(manifest, &record)) == 1) total++;
    if (read_code < 0) {
        manifest_record_free(&record);
        fclose(manifest);
        fclose(report);
        return 7;
    }
    rewind(manifest);
    Totals totals = {0};
    uint64_t current = 0;
    int result = 0;
    while ((read_code = manifest_record_read(manifest, &record)) == 1) {
        current++;
        const char *pkg = record.field[0];
        const char *category = record.field[1];
        const char *path = record.field[9];
        if (stop_requested(o)) { result = 9; break; }
        if (current == 1U || current % 128U == 0U || current == total) {
            atomic_progress(o, "cache-clean", "C 原生校验并消费不可变缓存快照", current, total, path);
        }
        uint64_t dev, ino, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec;
        bool metadata_ok =
            parse_u64_value(record.field[2], &dev) &&
            parse_u64_value(record.field[3], &ino) &&
            parse_u64_value(record.field[4], &size) &&
            parse_u64_value(record.field[5], &mtime_sec) &&
            parse_u64_value(record.field[6], &mtime_nsec) &&
            parse_u64_value(record.field[7], &ctime_sec) &&
            parse_u64_value(record.field[8], &ctime_nsec);
        if (!metadata_ok || size > o->max_file_bytes ||
            !cache_path_matches_package(o, path, pkg) ||
            package_whitelisted(pkg) || whitelist_conflict(path)) {
            totals.skipped++;
            totals.protected_items++;
            report_row(report, "protected", "low", category, 1, 0, path);
            continue;
        }
        struct stat first_stat;
        struct stat second_stat;
        totals.visited_files++;
        if (lstat(path, &first_stat) != 0) {
            totals.skipped++;
            report_row(report, "missing", "low", category, 1, 0, path);
            continue;
        }
        if (!stat_matches_manifest(&first_stat, dev, ino, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec)) {
            totals.skipped++;
            totals.protected_items++;
            totals.protected_bytes += size;
            report_row(report, "changed", "low", category, 1, size, path);
            continue;
        }
        if (lstat(path, &second_stat) != 0 ||
            !stat_matches_manifest(&second_stat, dev, ino, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec)) {
            totals.skipped++;
            totals.protected_items++;
            totals.protected_bytes += size;
            report_row(report, "changed", "low", category, 1, size, path);
            continue;
        }
        if (unlink(path) == 0) {
            totals.files++;
            totals.bytes += size;
            totals.candidates++;
            mark_first_result(&totals);
            report_row(report, "cleaned", "low", category, 1, size, path);
        } else {
            totals.errors++;
            report_row(report, "failed", "low", category, 1, size, path);
        }
    }
    if (read_code < 0) result = 7;
    manifest_record_free(&record);
    fclose(manifest);
    fclose(report);
    totals.targets = total;
    write_summary(o, &totals);
    return result;
}

static char *normalize_rule(const char *raw) {
    char *s = xstrdup(raw);
    char *p = s;
    while (isspace((unsigned char)*p)) p++;
    memmove(s, p, strlen(p) + 1);
    char *e = s + strlen(s);
    while (e > s && isspace((unsigned char)e[-1])) *--e = '\0';
    if (!*s || *s == '#' || *s != '/') { free(s); return NULL; }
    const char *em = "/storage/emulated/0";
    if (strncmp(s, em, strlen(em)) == 0) {
        char *n = xmalloc(strlen(s) + 16);
        sprintf(n, "/data/media/0%s", s + strlen(em));
        free(s);
        s = n;
    } else if (strncmp(s, "/sdcard", 7) == 0) {
        char *n = xmalloc(strlen(s) + 16);
        sprintf(n, "/data/media/0%s", s + 7);
        free(s);
        s = n;
    }
    size_t n = strlen(s);
    while (n > 1 && s[n - 1] == '/') s[--n] = '\0';
    if (n > 2 && strcmp(s + n - 2, "/*") == 0) {
        s[n - 2] = '\0';
        const char *b = strrchr(s, '/');
        b = b ? b + 1 : s;
        if (strcmp(b, "cache") != 0 && strcmp(b, "code_cache") != 0) s[n - 2] = '/';
    }
    return s;
}
static void split_components(const char *pattern, StrVec *comps) {
    char *copy = xstrdup(pattern);
    char *save = NULL;
    for (char *t = strtok_r(copy, "/", &save); t; t = strtok_r(NULL, "/", &save)) vec_add(comps, t);
    free(copy);
}
static void expand_rec(const char *base, const StrVec *comps, size_t idx, StrVec *out) {
    if (out->n >= MAX_CANDIDATES) return;
    if (idx >= comps->n) { if (file_exists(base)) vec_add(out, base); return; }
    const char *comp = comps->v[idx];
    if (!has_glob(comp)) {
        char p[PATH_MAX];
        if (strcmp(base, "/") == 0) snprintf(p, sizeof(p), "/%s", comp);
        else snprintf(p, sizeof(p), "%s/%s", base, comp);
        if (file_exists(p)) expand_rec(p, comps, idx + 1, out);
        return;
    }
    DIR *d = opendir(base);
    if (!d) return;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        if (fnmatch(comp, de->d_name, FNM_PERIOD) != 0) continue;
        char p[PATH_MAX];
        if (strcmp(base, "/") == 0) snprintf(p, sizeof(p), "/%s", de->d_name);
        else snprintf(p, sizeof(p), "%s/%s", base, de->d_name);
        struct stat st;
        if (lstat(p, &st) != 0 || S_ISLNK(st.st_mode)) continue;
        if (idx + 1 < comps->n && !S_ISDIR(st.st_mode)) continue;
        expand_rec(p, comps, idx + 1, out);
    }
    closedir(d);
}
static void expand_rule(const char *rule, StrVec *out) {
    char *n = normalize_rule(rule);
    if (!n) return;
    if (!has_glob(n)) { if (file_exists(n)) vec_add(out, n); free(n); return; }
    StrVec comps = {0};
    split_components(n, &comps);
    expand_rec("/", &comps, 0, out);
    vec_free(&comps);
    free(n);
}
static bool deep_allowed(const char *p) {
    if (!p || strcmp(p, "/") == 0) return false;
    const char *deny[] = {"/data/adb", "/data/app", "/data/system", "/data/misc", "/data/dalvik-cache", "/system", "/vendor", "/product", "/apex"};
    for (size_t i = 0; i < sizeof(deny) / sizeof(deny[0]); i++) if (path_relation(deny[i], p)) return false;
    return strncmp(p, "/data/data/", 11) == 0 || strncmp(p, "/data/user/", 11) == 0 ||
           strncmp(p, "/data/user_de/", 14) == 0 || strncmp(p, "/data/cache/", 12) == 0 ||
           strncmp(p, "/data/media/", 12) == 0 || strncmp(p, "/data_mirror/data_ce/", 21) == 0;
}
static void lower_copy(char *out, size_t cap, const char *in) {
    size_t i = 0;
    for (; in[i] && i + 1 < cap; i++) out[i] = (char)tolower((unsigned char)in[i]);
    out[i] = '\0';
}
static const char *deep_risk(const char *p) {
    char s[PATH_MAX];
    lower_copy(s, sizeof(s), p);
    const char *critical[] = {"/download", "/documents", "/dcim", "/pictures", "/movies", "/music", "/android/obb", "/backup", "/backups", "/draft", "/drafts", "/database", "/databases", "/shared_prefs"};
    for (size_t i = 0; i < sizeof(critical) / sizeof(critical[0]); i++) if (strstr(s, critical[i])) return "critical";
    const char *low[] = {"/cache", "/code_cache", "/gpucache", "/code cache", "/crashpad/completed", "/tmp", "/temp", "/logs", "/log", "/.cache", "/.thumbnails"};
    for (size_t i = 0; i < sizeof(low) / sizeof(low[0]); i++) if (strstr(s, low[i])) return "low";
    const char *medium[] = {"/crash", "/tombstone", "/debug", "/trace", "/dump"};
    for (size_t i = 0; i < sizeof(medium) / sizeof(medium[0]); i++) if (strstr(s, medium[i])) return "medium";
    return "high";
}
static bool child_of(const char *parent, const char *child) { return path_relation(parent, child) && strcmp(parent, child) != 0; }
static int scan_deep(const Options *o) {
    require_outputs(o);
    if (!o->rules_path) die("rules required");
    load_lines(o->whitelist_path, &g_whitelist, true);
    FILE *rules = fopen(o->rules_path, "r");
    if (!rules) die("cannot open rules");
    StrVec cand = {0};
    char *line = NULL;
    size_t cap = 0;
    uint64_t rn = 0;
    while (getline(&line, &cap, rules) >= 0) {
        if (stop_requested(o)) { free(line); fclose(rules); vec_free(&cand); return 9; }
        rn++;
        if (rn == 1 || rn % 256 == 0) atomic_progress(o, "deep-scan", "C 原生解析深度规则", rn, 0, line);
        expand_rule(line, &cand);
    }
    free(line);
    fclose(rules);
    vec_sort_unique(&cand);
    FILE *rep = open_report(o->report_path);
    FILE *targets = fopen(o->targets_path, "w");
    Totals t = {0};
    char covered_all[PATH_MAX] = "", covered_protected[PATH_MAX] = "";
    time_t stage = time(NULL);
    for (size_t i = 0; i < cand.n; i++) {
        const char *p = cand.v[i];
        if (stop_requested(o)) { if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); vec_free(&cand); return 9; }
        if (time(NULL) - stage >= 300) { t.truncated = 1; break; }
        atomic_progress(o, "deep-scan", "C 原生执行深度规则", i + 1, cand.n, p);
        const char *r = deep_risk(p);
        if (strcmp(r, "low") == 0) t.risk_low++;
        else if (strcmp(r, "medium") == 0) t.risk_medium++;
        else if (strcmp(r, "critical") == 0) t.risk_critical++;
        else t.risk_high++;
        if (*covered_all && child_of(covered_all, p)) { t.skipped++; continue; }
        if (*covered_protected && child_of(covered_protected, p) && (strcmp(r, "high") == 0 || strcmp(r, "critical") == 0)) { t.skipped++; continue; }
        if (!deep_allowed(p)) {
            t.skipped++;
            report_row(rep, "rejected", "protected", "深度规则", 0, 0, p);
            if (is_dir_nofollow(p)) snprintf(covered_all, sizeof(covered_all), "%s", p);
            continue;
        }
        if (whitelist_conflict(p)) {
            t.skipped++;
            t.whitelisted++;
            report_row(rep, "skipped", "protected", "深度规则", 0, 0, p);
            if (is_dir_nofollow(p)) snprintf(covered_all, sizeof(covered_all), "%s", p);
            continue;
        }
        bool eligible = strcmp(r, "low") == 0 || strcmp(r, "medium") == 0 || o->allow_high_risk;
        if (!eligible) {
            t.protected_items++;
            report_row(rep, "protected", r, "深度规则", 1, 0, p);
            if (is_dir_nofollow(p)) snprintf(covered_protected, sizeof(covered_protected), "%s", p);
            continue;
        }
        Stats s;
        int rc = stat_tree(p, o, 0, &s);
        if (rc == 9) { if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); vec_free(&cand); return 9; }
        if (rc < 0 || s.incomplete) t.errors++;
        t.visited_files += s.visited_files;
        t.visited_dirs += s.visited_dirs;
        uint64_t item_count = s.files ? s.files : 1;
        if (s.oversized || s.mount_conflict) {
            t.protected_items += item_count;
            t.protected_bytes += s.bytes;
            if (s.mount_conflict) t.mount_items++;
            report_row(rep, "protected", r, "深度规则", item_count, s.bytes, p);
            if (is_dir_nofollow(p)) snprintf(covered_protected, sizeof(covered_protected), "%s", p);
            continue;
        }
        t.files += s.files;
        t.bytes += s.bytes;
        t.dirs += s.dirs;
        if (s.files == 0) t.empty_dirs++;
        t.candidates++;
        t.targets++;
        mark_first_result(&t);
        report_row(rep, "candidate", r, "深度规则", item_count, s.bytes, p);
        if (targets) fprintf(targets, "%s\t%s\n", p, r);
        if (is_dir_nofollow(p)) snprintf(covered_all, sizeof(covered_all), "%s", p);
    }
    if (rep) fclose(rep);
    if (targets) fclose(targets);
    write_summary(o, &t);
    vec_free(&cand);
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) die("usage: baize_engine <scan-corpses|scan-cache|scan-external-one-pass|clean-cache-snapshot|scan-deep> [options]");
    g_started = time(NULL);
    g_started_ms = monotonic_ms();
    Options o;
    parse_options(argc, argv, &o);
    int rc;
    if (strcmp(argv[1], "scan-corpses") == 0) rc = scan_corpses(&o);
    else if (strcmp(argv[1], "scan-cache") == 0) rc = scan_cache(&o);
    else if (strcmp(argv[1], "scan-external-one-pass") == 0) rc = scan_external_one_pass(&o);
    else if (strcmp(argv[1], "clean-cache-snapshot") == 0) rc = clean_cache_snapshot(&o);
    else if (strcmp(argv[1], "scan-deep") == 0) rc = scan_deep(&o);
    else die("unsupported command");
    vec_free(&g_whitelist);
    vec_free(&g_package_whitelist);
    return rc;
}
