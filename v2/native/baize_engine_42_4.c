#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <errno.h>
#include <fnmatch.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define ENGINE_VERSION "43.5-v225-deep-budget"
#define MAX_CANDIDATES 200000U

typedef struct { char **v; size_t n, cap; } StrVec;
typedef struct {
    uint64_t files, bytes, dirs, empty_dirs;
    uint64_t visited_files, visited_dirs;
    uint64_t elapsed_ms;
    bool oversized, mount_conflict, incomplete, timed_out;
} Stats;
typedef struct {
    const char *media_root, *data_root, *installed_root, *whitelist_path;
    const char *package_whitelist_path, *rules_path, *report_path, *targets_path;
    const char *items_path, *manifest_path, *summary_path, *progress_path, *stop_path;
    const char *corpse_report_path, *corpse_targets_path, *corpse_summary_path;
    const char *risk_overrides_path;
    /* index-files 模式：共享存储索引 */
    const char *index_list_path, *index_seen_path, *index_records_path;
    const char *index_apk_path, *index_empty_path, *index_large_path;
    const char *index_organizer_path, *index_duplicates_path;
    const char *index_organizer_exts_path;
    uint64_t index_large_bytes;
    uint64_t max_file_bytes;
    uint64_t dir_budget_ms;
    uint64_t global_budget_ms;
    int min_age_days;
    bool allow_high_risk;
    /* 允许自动执行的最高风险等级；-1 表示回退到 allow_high_risk 的旧语义。 */
    int max_auto_risk;
} Options;

enum { RISK_LOW = 0, RISK_MEDIUM = 1, RISK_HIGH = 2, RISK_CRITICAL = 3 };

static int risk_rank(const char *risk) {
    if (!risk) return RISK_CRITICAL;
    if (strcmp(risk, "low") == 0) return RISK_LOW;
    if (strcmp(risk, "medium") == 0) return RISK_MEDIUM;
    if (strcmp(risk, "high") == 0) return RISK_HIGH;
    return RISK_CRITICAL;
}

static bool valid_risk_name(const char *risk) {
    return risk && (strcmp(risk, "low") == 0 || strcmp(risk, "medium") == 0 ||
                    strcmp(risk, "high") == 0 || strcmp(risk, "critical") == 0);
}

static const char *risk_name(int rank) {
    switch (rank) {
        case RISK_LOW: return "low";
        case RISK_MEDIUM: return "medium";
        case RISK_HIGH: return "high";
        default: return "critical";
    }
}
typedef struct {
    uint64_t files, bytes, dirs, empty_dirs, skipped, errors;
    uint64_t protected_items, protected_bytes, candidates, targets;
    uint64_t risk_low, risk_medium, risk_high, risk_critical;
    uint64_t mount_items, truncated, whitelisted;
    uint64_t visited_files, visited_dirs;
    uint64_t package_index_entries, package_index_files, package_lookups;
    uint64_t first_result_ms;
    uint64_t timed_out_dirs;
    uint64_t one_pass_app_dirs, one_pass_installed_dirs, one_pass_orphan_dirs;
} Totals;

static StrVec g_whitelist = {0}, g_package_whitelist = {0}, g_installed_index = {0};
static uint64_t g_whitelist_index_queries;
static uint64_t g_whitelist_ancestor_hits;
static uint64_t g_whitelist_descendant_hits;
static uint64_t g_whitelist_pruned_subtrees;
static time_t g_started;
static uint64_t g_started_ms;
static uint64_t g_last_progress_ms;
static uint64_t g_deep_parse_ms;
static uint64_t g_deep_stage_ms;
static uint64_t g_deep_slowest_ms;
static char g_deep_slowest_path[PATH_MAX];

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
static size_t vec_lower_bound(const StrVec *a, const char *value) {
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
static bool path_component_safe(const char *component) {
    if (!component || !*component || strcmp(component, ".") == 0 || strcmp(component, "..") == 0) return false;
    return strchr(component, '/') == NULL;
}
static bool path_join(char *out, size_t capacity, const char *base, const char *component) {
    if (!out || capacity == 0U || !base || !path_component_safe(component)) return false;
    int written = snprintf(out, capacity, "%s/%s", base, component);
    return written >= 0 && (size_t)written < capacity;
}
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
static void normalize_index_path(char *path) {
    size_t length = path ? strlen(path) : 0U;
    while (length > 1U && path[length - 1U] == '/') path[--length] = '\0';
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
        *cursor = '\0';
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
        if (!path_join(path, sizeof(path), root, entry->d_name)) continue;
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
            int written = snprintf(key, sizeof(key), "%s\t%s", user, begin);
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

/*
 * 遍历中止检查的节流计数器。
 *
 * 此前 stat_tree_rec() 在每次递归入口调用 stop_requested()，而它是
 * access(stop_path, F_OK) —— 一次完整的路径解析系统调用。strace 实测
 * 21600 个文件产生 24004 次 access（正好等于文件数+目录数），全部返回失败。
 * Android 上这条路径还要逐层过 SELinux avc 检查，成本更高。
 *
 * 同理 deadline 检查的 monotonic_ms() 在递归入口和 readdir 循环里各一次，
 * 等于每个文件两次 clock_gettime。
 *
 * 改为按条目计数节流：每 WALK_CHECK_INTERVAL 个条目才真正检查一次。
 * 用户点停止到实际停下最多晚几毫秒，完全无感。
 */
#define WALK_CHECK_MASK 0x1FFU   /* 每 512 个条目检查一次 */
static uint64_t g_walk_counter;

static int walk_abort_now(const Options *o, uint64_t deadline_ms) {
    if (deadline_ms > 0U && monotonic_ms() >= deadline_ms) return 124;
    if (stop_requested(o)) return 9;
    return 0;
}

/* 返回 0 继续，9 用户中止，124 超时。 */
static int walk_should_abort(const Options *o, uint64_t deadline_ms) {
    if ((++g_walk_counter & WALK_CHECK_MASK) != 0U) return 0;
    return walk_abort_now(o, deadline_ms);
}

static void walk_count_file(const struct stat *st, uint64_t max_bytes, int days, Stats *s) {
    s->visited_files++;
    if (!eligible_mtime(st, days)) return;
    s->files++;
    s->bytes += (uint64_t)st->st_size;
    if ((uint64_t)st->st_size > max_bytes) s->oversized = true;
}

/*
 * 目录遍历。
 *
 * 与旧实现的两点差异，语义完全等价但省掉大量系统调用：
 *   1. 用 fstatat(dirfd, name) 取代 lstat(完整路径)。旧写法每个文件都从 /
 *      重新解析整条路径，9 层深的路径就是 9 次 dentry 查找加 9 次 SELinux 检查；
 *      相对 dirfd 只查 1 层。
 *   2. 先看 readdir 返回的 d_type，目录和符号链接不必再 stat。
 *      d_type 为 DT_UNKNOWN 的文件系统会自动回退到 fstatat。
 */
static int walk_dir(int parent_fd, const char *name, dev_t root_dev, uint64_t max_bytes,
                    int days, const Options *o, Stats *s, unsigned depth, uint64_t deadline_ms) {
    if (depth > 512) { s->incomplete = true; return -1; }
    int entry_abort = walk_abort_now(o, deadline_ms);
    if (entry_abort != 0) {
        if (entry_abort == 124) { s->timed_out = true; s->incomplete = true; }
        return entry_abort;
    }
    int fd = openat(parent_fd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) { s->incomplete = true; return -1; }
    struct stat dst;
    if (fstat(fd, &dst) != 0) { close(fd); s->incomplete = true; return -1; }
    if (depth > 0 && dst.st_dev != root_dev) { s->mount_conflict = true; close(fd); return 0; }
    s->visited_dirs++;
    DIR *d = fdopendir(fd);
    if (!d) { close(fd); s->incomplete = true; return -1; }
    int this_fd = dirfd(d);
    uint64_t before = s->files;
    struct dirent *de;
    int rc = 0;
    while ((de = readdir(d)) != NULL) {
        int abort_code = walk_should_abort(o, deadline_ms);
        if (abort_code != 0) {
            if (abort_code == 124) { s->timed_out = true; s->incomplete = true; }
            rc = abort_code;
            break;
        }
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        if (de->d_type == DT_LNK) continue;                 /* 不跟随符号链接 */
        if (de->d_type == DT_DIR) {
            int sub = walk_dir(this_fd, de->d_name, root_dev, max_bytes, days, o, s,
                               depth + 1, deadline_ms);
            if (sub == 9 || sub == 124) { rc = sub; break; }
            continue;
        }
        if (de->d_type != DT_REG && de->d_type != DT_UNKNOWN) continue;
        struct stat st;
        if (fstatat(this_fd, de->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) { s->incomplete = true; continue; }
        if (S_ISLNK(st.st_mode)) continue;
        if (S_ISDIR(st.st_mode)) {                          /* DT_UNKNOWN 的兜底分支 */
            int sub = walk_dir(this_fd, de->d_name, root_dev, max_bytes, days, o, s,
                               depth + 1, deadline_ms);
            if (sub == 9 || sub == 124) { rc = sub; break; }
            continue;
        }
        if (!S_ISREG(st.st_mode)) continue;
        walk_count_file(&st, max_bytes, days, s);
    }
    closedir(d);
    if (rc != 0) return rc;
    s->dirs++;
    if (s->files == before) s->empty_dirs++;
    return 0;
}

static int stat_tree_budgeted(const char *path, const Options *o, int days, Stats *s, uint64_t budget_ms) {
    memset(s, 0, sizeof(*s));
    struct stat st;
    if (lstat(path, &st) != 0) return -1;
    uint64_t started_ms = monotonic_ms();
    uint64_t deadline_ms = budget_ms > 0U ? started_ms + budget_ms : 0U;
    g_walk_counter = 0;
    int result;
    if (S_ISLNK(st.st_mode)) {
        result = 0;                                          /* 不跟随符号链接 */
    } else if (S_ISREG(st.st_mode)) {
        walk_count_file(&st, o->max_file_bytes, days, s);    /* 规则可以直接指向文件 */
        result = 0;
    } else if (!S_ISDIR(st.st_mode)) {
        result = 0;
    } else {
        result = walk_dir(AT_FDCWD, path, st.st_dev, o->max_file_bytes, days, o, s, 0, deadline_ms);
    }
    uint64_t finished_ms = monotonic_ms();
    s->elapsed_ms = finished_ms >= started_ms ? finished_ms - started_ms : 0U;
    return result;
}
static int stat_tree(const char *path, const Options *o, int days, Stats *s) {
    return stat_tree_budgeted(path, o, days, s, 0U);
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
    char safe_slowest[PATH_MAX];
    snprintf(safe_slowest, sizeof(safe_slowest), "%s", g_deep_slowest_path);
    sanitize(safe_slowest);
    fprintf(f,
        "files=%" PRIu64 "\nbytes=%" PRIu64 "\ndirs=%" PRIu64 "\nempty_dirs=%" PRIu64 "\nskipped=%" PRIu64 "\nerrors=%" PRIu64 "\nprotected_items=%" PRIu64 "\nprotected_bytes=%" PRIu64 "\ncandidates=%" PRIu64 "\ntargets=%" PRIu64 "\nrisk_low=%" PRIu64 "\nrisk_medium=%" PRIu64 "\nrisk_high=%" PRIu64 "\nrisk_critical=%" PRIu64 "\nmount_items=%" PRIu64 "\ntruncated=%" PRIu64 "\nwhitelisted=%" PRIu64 "\nvisited_files=%" PRIu64 "\nvisited_dirs=%" PRIu64 "\npackage_index_entries=%" PRIu64 "\npackage_index_files=%" PRIu64 "\npackage_lookups=%" PRIu64 "\nfirst_result_ms=%" PRIu64 "\ntimed_out_dirs=%" PRIu64 "\ndeep_parse_ms=%" PRIu64 "\ndeep_stage_ms=%" PRIu64 "\ndeep_slowest_ms=%" PRIu64 "\ndeep_slowest_path=%s\none_pass_app_dirs=%" PRIu64 "\none_pass_installed_dirs=%" PRIu64 "\none_pass_orphan_dirs=%" PRIu64 "\nwhitelist_index_entries=%" PRIu64 "\nwhitelist_index_queries=%" PRIu64 "\nwhitelist_ancestor_hits=%" PRIu64 "\nwhitelist_descendant_hits=%" PRIu64 "\npruned_subtrees=%" PRIu64 "\nelapsed_ms=%" PRIu64 "\nitems_per_second=%" PRIu64 "\nengine=native-c-arm64\nversion=%s\n",
        t->files, t->bytes, t->dirs, t->empty_dirs, t->skipped, t->errors,
        t->protected_items, t->protected_bytes, t->candidates, t->targets,
        t->risk_low, t->risk_medium, t->risk_high, t->risk_critical,
        t->mount_items, t->truncated, t->whitelisted,
        t->visited_files, t->visited_dirs, t->package_index_entries,
        t->package_index_files, t->package_lookups, t->first_result_ms,
        t->timed_out_dirs, g_deep_parse_ms, g_deep_stage_ms, g_deep_slowest_ms, safe_slowest,
        t->one_pass_app_dirs, t->one_pass_installed_dirs, t->one_pass_orphan_dirs,
        (uint64_t)g_whitelist.n, g_whitelist_index_queries,
        g_whitelist_ancestor_hits, g_whitelist_descendant_hits,
        g_whitelist_pruned_subtrees, elapsed_ms, throughput, ENGINE_VERSION);
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
    o->dir_budget_ms = 8000U;
    o->global_budget_ms = 180000U;
    o->max_auto_risk = -1;
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
        else if (strcmp(a, "--dir-budget-ms") == 0) o->dir_budget_ms = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--global-budget-ms") == 0) o->global_budget_ms = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--min-age-days") == 0) o->min_age_days = atoi(arg_value(argc, argv, &i));
        else if (strcmp(a, "--allow-high-risk") == 0) o->allow_high_risk = atoi(arg_value(argc, argv, &i)) != 0;
        else if (strcmp(a, "--risk-overrides") == 0) o->risk_overrides_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--list") == 0) o->index_list_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--seen") == 0) o->index_seen_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--records") == 0) o->index_records_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--apk") == 0) o->index_apk_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--empty") == 0) o->index_empty_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--large") == 0) o->index_large_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--organizer") == 0) o->index_organizer_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--duplicates") == 0) o->index_duplicates_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--organizer-exts") == 0) o->index_organizer_exts_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--large-bytes") == 0) o->index_large_bytes = strtoull(arg_value(argc, argv, &i), NULL, 10);
        else if (strcmp(a, "--max-auto-risk") == 0) {
            const char *v = arg_value(argc, argv, &i);
            if (!valid_risk_name(v)) die("invalid --max-auto-risk");
            o->max_auto_risk = risk_rank(v);
        }
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
            if (!path_join(root, sizeof(root), android, sub[si])) continue;
            DIR *d = opendir(root);
            if (!d) continue;
            struct dirent *de;
            while ((de = readdir(d)) != NULL) {
                if (de->d_name[0] == '.' || !safe_package(de->d_name)) continue;
                if (stop_requested(o)) { closedir(d); closedir(users); if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); return 9; }
                if (installed_index_contains(ue->d_name, de->d_name, &t)) continue;
                char path[PATH_MAX];
                if (!path_join(path, sizeof(path), root, de->d_name)) continue;
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
                              Stats *stats, bool may_contain_whitelist, unsigned depth) {
    if (depth > 512U) { stats->incomplete = true; return -1; }
    if (stop_requested(o)) return 9;
    if (depth > 0U && may_contain_whitelist) {
        unsigned relation = whitelist_relation(path);
        if ((relation & WHITELIST_ANCESTOR) != 0U) {
            g_whitelist_pruned_subtrees++;
            return 0;
        }
        may_contain_whitelist = (relation & WHITELIST_DESCENDANT) != 0U;
    }
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
        int code = snapshot_cache_rec(child, root_dev, o, days, manifest, pkg, category,
                                      stats, may_contain_whitelist, depth + 1U);
        if (code == 9) { closedir(dir); return 9; }
    }
    closedir(dir);
    stats->dirs++;
    if (stats->files == before) stats->empty_dirs++;
    return 0;
}
static int snapshot_cache_tree(const char *path, const Options *o, const char *pkg,
                               const char *category, FILE *manifest, Stats *stats,
                               bool may_contain_whitelist) {
    memset(stats, 0, sizeof(*stats));
    struct stat root;
    if (lstat(path, &root) != 0 || !S_ISDIR(root.st_mode) || S_ISLNK(root.st_mode)) return -1;
    FILE *temporary = tmpfile();
    if (!temporary) return -1;
    int code = snapshot_cache_rec(path, root.st_dev, o, o->min_age_days, temporary,
                                  pkg, category, stats, may_contain_whitelist, 0U);
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
    unsigned relation = whitelist_relation(path);
    if (package_whitelisted(pkg) || (relation & WHITELIST_ANCESTOR) != 0U) {
        totals->skipped++;
        totals->whitelisted++;
        report_row(rep, "skipped", "protected", category, 0, 0, path);
        return;
    }
    Stats stats;
    int code = snapshot_cache_tree(path, o, pkg, category, manifest, &stats,
                                   (relation & WHITELIST_DESCENDANT) != 0U);
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
        if (!path_join(user, sizeof(user), root, user_entry->d_name)) continue;
        DIR *apps = opendir(user);
        if (!apps) continue;
        struct dirent *app_entry;
        while ((app_entry = readdir(apps)) != NULL) {
            if (app_entry->d_name[0] == '.' || !safe_package(app_entry->d_name)) continue;
            char app[PATH_MAX];
            if (!path_join(app, sizeof(app), user, app_entry->d_name)) continue;
            const char *leaves[] = {"cache", "code_cache"};
            for (size_t i = 0; i < 2U; i++) {
                if (stop_requested(o)) { closedir(apps); closedir(users); return; }
                char path[PATH_MAX];
                if (!path_join(path, sizeof(path), app, leaves[i])) continue;
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
            if (!path_component_safe(user_entry->d_name)) continue;
            int apps_written = snprintf(apps_root, sizeof(apps_root), "%s/%s/Android/data", o->media_root, user_entry->d_name);
            if (apps_written < 0 || (size_t)apps_written >= sizeof(apps_root)) continue;
            DIR *apps = opendir(apps_root);
            if (!apps) continue;
            struct dirent *app_entry;
            while ((app_entry = readdir(apps)) != NULL) {
                if (app_entry->d_name[0] == '.' || !safe_package(app_entry->d_name)) continue;
                char app[PATH_MAX];
                if (!path_join(app, sizeof(app), apps_root, app_entry->d_name)) continue;
                const char *leaves[] = {"cache", "code_cache"};
                for (size_t i = 0; i < 2U; i++) {
                    if (stop_requested(o)) { closedir(apps); closedir(users); goto stopped; }
                    char path[PATH_MAX];
                    if (!path_join(path, sizeof(path), app, leaves[i])) continue;
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
    if (path_join(root, sizeof(root), o->data_root, "user")) {
        scan_cache_user_root(o, root, "内部应用缓存", cache_report, cache_targets,
                             cache_items, cache_manifest, &cache_totals, &current);
    }
    if (stop_requested(o)) goto stopped;
    if (path_join(root, sizeof(root), o->data_root, "user_de")) {
        scan_cache_user_root(o, root, "设备保护缓存", cache_report, cache_targets,
                             cache_items, cache_manifest, &cache_totals, &current);
    }
    if (stop_requested(o)) goto stopped;

    DIR *users = opendir(o->media_root);
    if (users) {
        struct dirent *user_entry;
        while ((user_entry = readdir(users)) != NULL) {
            if (!isdigit((unsigned char)user_entry->d_name[0])) continue;
            char user_media_root[PATH_MAX];
            if (!path_join(user_media_root, sizeof(user_media_root), o->media_root, user_entry->d_name)) continue;
            char android_root[PATH_MAX];
            if (!path_join(android_root, sizeof(android_root), user_media_root, "Android")) continue;
            char data_root[PATH_MAX];
            if (!path_join(data_root, sizeof(data_root), android_root, "data")) continue;
            DIR *apps = opendir(data_root);
            if (apps) {
                struct dirent *app_entry;
                while ((app_entry = readdir(apps)) != NULL) {
                    if (app_entry->d_name[0] == '.' || !safe_package(app_entry->d_name)) continue;
                    if (stop_requested(o)) { closedir(apps); closedir(users); goto stopped; }
                    char app_path[PATH_MAX];
                    if (!path_join(app_path, sizeof(app_path), data_root, app_entry->d_name)) continue;
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
                            if (!path_join(cache_path, sizeof(cache_path), app_path, leaves[i])) continue;
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
                if (!path_join(secondary_root, sizeof(secondary_root), android_root, secondary[si])) continue;
                DIR *entries = opendir(secondary_root);
                if (!entries) continue;
                struct dirent *entry;
                while ((entry = readdir(entries)) != NULL) {
                    if (entry->d_name[0] == '.' || !safe_package(entry->d_name)) continue;
                    if (stop_requested(o)) { closedir(entries); closedir(users); goto stopped; }
                    current++;
                    if (installed_index_contains(user_entry->d_name, entry->d_name, &cache_totals)) continue;
                    char path[PATH_MAX];
                    if (!path_join(path, sizeof(path), secondary_root, entry->d_name)) continue;
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
        else if (!path_join(p, sizeof(p), base, de->d_name)) continue;
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
/* 返回 false 表示源路径超长被截断，调用方必须按最保守等级处理。 */
static bool lower_copy(char *out, size_t cap, const char *in) {
    size_t i = 0;
    for (; in[i] && i + 1 < cap; i++) out[i] = (char)tolower((unsigned char)in[i]);
    out[i] = '\0';
    return in[i] == '\0';
}

/*
 * 判断 seg 是否为 lower_path 中的一个完整路径分段。
 *
 * 历史实现直接用 strstr 在整条路径上找子串，导致 /nfc/logo 命中 "log"、
 * /files/login-identifier.txt 命中 "log"、/Cacheapps2sdcard 命中 "cache"，
 * 把用户数据误判为 low 风险并被定时任务自动删除。这里要求匹配部分的
 * 前一个字符是 '/'、后一个字符是 '/' 或字符串结尾。
 */
static bool has_path_segment(const char *lower_path, const char *seg) {
    size_t n = strlen(seg);
    if (n == 0) return false;
    const char *p = lower_path;
    while ((p = strstr(p, seg)) != NULL) {
        char next = p[n];
        if (p > lower_path && p[-1] == '/' && (next == '\0' || next == '/')) return true;
        p += 1;
    }
    return false;
}

/*
 * 显式风险标注表。来源有两处，后者覆盖前者：
 *   1. config/deep.rules 中 "路径|risk" 形式的规则标注；
 *   2. config/risk-overrides.conf 中用户自定义的 "路径|risk"。
 * 命中时取最长匹配的祖先路径，因此可以对某个目录整体降级或升级。
 */
typedef struct { char *path; int rank; bool user; } RiskRule;
static struct { RiskRule *v; size_t n, cap; } g_risk_rules = {0};

static void risk_rules_add(const char *path, const char *risk, bool user) {
    if (!path || !*path || path[0] != '/') return;
    int rank = risk_rank(risk);
    for (size_t i = 0; i < g_risk_rules.n; i++) {
        if (strcmp(g_risk_rules.v[i].path, path) == 0) {
            /* 用户覆盖优先；同来源时取更保守的等级。 */
            if (user && !g_risk_rules.v[i].user) {
                g_risk_rules.v[i].rank = rank;
                g_risk_rules.v[i].user = true;
            } else if (user == g_risk_rules.v[i].user && rank > g_risk_rules.v[i].rank) {
                g_risk_rules.v[i].rank = rank;
            }
            return;
        }
    }
    if (g_risk_rules.n == g_risk_rules.cap) {
        size_t cap = g_risk_rules.cap ? g_risk_rules.cap * 2 : 64;
        RiskRule *grown = realloc(g_risk_rules.v, cap * sizeof(*grown));
        if (!grown) die("out of memory loading risk rules");
        g_risk_rules.v = grown;
        g_risk_rules.cap = cap;
    }
    g_risk_rules.v[g_risk_rules.n].path = xstrdup(path);
    g_risk_rules.v[g_risk_rules.n].rank = rank;
    g_risk_rules.v[g_risk_rules.n].user = user;
    g_risk_rules.n++;
}

static void risk_rules_free(void) {
    for (size_t i = 0; i < g_risk_rules.n; i++) free(g_risk_rules.v[i].path);
    free(g_risk_rules.v);
    g_risk_rules.v = NULL;
    g_risk_rules.n = g_risk_rules.cap = 0;
}

/* 加载用户覆盖文件，每行 "绝对路径|low|medium|high|critical"。 */
static void load_risk_overrides(const char *path) {
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
        char *bar = strrchr(p, '|');
        if (!bar) continue;
        *bar = '\0';
        char *risk = bar + 1;
        while (isspace((unsigned char)*risk)) risk++;
        char *pe = p + strlen(p);
        while (pe > p && isspace((unsigned char)pe[-1])) *--pe = '\0';
        if (!valid_risk_name(risk)) continue;
        char *norm = normalize_rule(p);
        if (!norm) continue;
        risk_rules_add(norm, risk, true);
        free(norm);
    }
    free(line);
    fclose(f);
}

/* 查显式标注：取最长匹配祖先；用户覆盖优先于规则文件标注。 */
static int explicit_risk_rank(const char *p) {
    int best = -1;
    size_t best_len = 0;
    bool best_user = false;
    for (size_t i = 0; i < g_risk_rules.n; i++) {
        const RiskRule *r = &g_risk_rules.v[i];
        if (!path_relation(r->path, p)) continue;
        size_t len = strlen(r->path);
        if (r->user && !best_user) { best = r->rank; best_len = len; best_user = true; continue; }
        if (r->user == best_user && len >= best_len) { best = r->rank; best_len = len; }
    }
    return best;
}

static const char *deep_risk(const char *p) {
    int explicit_rank = explicit_risk_rank(p);
    if (explicit_rank >= 0) return risk_name(explicit_rank);

    char s[PATH_MAX];
    /* 路径超长被截断时，尾部的 critical 关键词可能丢失，按最保守等级处理。 */
    if (!lower_copy(s, sizeof(s), p)) return "critical";

    static const char *critical[] = {"download", "downloads", "documents", "dcim", "pictures",
                                     "movies", "music", "obb", "backup", "backups", "draft",
                                     "drafts", "database", "databases", "shared_prefs"};
    for (size_t i = 0; i < sizeof(critical) / sizeof(critical[0]); i++)
        if (has_path_segment(s, critical[i])) return "critical";

    static const char *low[] = {"cache", "caches", "code_cache", "code cache", "gpucache",
                                "tmp", "temp", "log", "logs", ".cache", ".thumbnails"};
    for (size_t i = 0; i < sizeof(low) / sizeof(low[0]); i++)
        if (has_path_segment(s, low[i])) return "low";
    if (strstr(s, "/crashpad/completed/") || has_path_segment(s, "completed")) {
        if (strstr(s, "/crashpad/")) return "low";
    }

    static const char *medium[] = {"crash", "tombstone", "tombstones", "debug", "trace",
                                   "traces", "dump", "dumps"};
    for (size_t i = 0; i < sizeof(medium) / sizeof(medium[0]); i++)
        if (has_path_segment(s, medium[i])) return "medium";

    return "high";
}
static bool child_of(const char *parent, const char *child) { return path_relation(parent, child) && strcmp(parent, child) != 0; }
/* ——— 共享存储索引：把 storage-index.sh 的逐文件 shell 循环搬到 C ———
 *
 * 旧实现对共享存储的每个文件跑一次 shell 迭代，每次迭代 fork 约 9 个进程：
 *   stat -c '%d_%i'            2 次（子 shell + stat）
 *   stat -c %s                 2 次（同一个文件被 stat 两遍）
 *   printf | tr（转小写）      2 次
 *   printf | base64 | tr       3 次
 * 外加为每个唯一 inode 在临时目录里创建一个标记文件做去重。
 *
 * 实测 6000 个文件耗时 51 秒（8.5 ms/文件）。真机共享存储常有 5 万到 20 万个
 * 文件，对应 7 分钟到 28 分钟，而 APK 扫描与文件归类每次都会触发它。
 *
 * 这里一次遍历完成 stat、去重、分桶，进程数从 O(文件数) 降到 1。
 */

typedef struct { dev_t dev; ino_t ino; } SeenKey;
typedef struct { SeenKey *slots; unsigned char *used; size_t cap, n; } SeenSet;
typedef enum { SEEN_ERROR = -1, SEEN_DUP = 0, SEEN_NEW = 1 } SeenResult;

static uint64_t seen_hash(dev_t dev, ino_t ino) {
    uint64_t h = 1469598103934665603ULL;
    uint64_t parts[2] = { (uint64_t)dev, (uint64_t)ino };
    for (size_t p = 0; p < 2; p++) {
        for (size_t b = 0; b < 8; b++) {
            h ^= (parts[p] >> (b * 8)) & 0xFFU;
            h *= 1099511628211ULL;
        }
    }
    return h;
}

static bool seen_init(SeenSet *s, size_t cap) {
    size_t n = 1024;
    while (n < cap * 2U) n <<= 1;
    s->slots = calloc(n, sizeof(*s->slots));
    s->used = calloc(n, 1);
    if (!s->slots || !s->used) { free(s->slots); free(s->used); return false; }
    s->cap = n; s->n = 0;
    return true;
}

static void seen_free(SeenSet *s) { free(s->slots); free(s->used); s->slots = NULL; s->used = NULL; }

static bool seen_grow(SeenSet *s);

/* 三态：新 inode、重复 inode、内存错误。错误绝不能伪装成“新 inode”。 */
static SeenResult seen_add(SeenSet *s, dev_t dev, ino_t ino) {
    if (s->n * 2U >= s->cap && !seen_grow(s)) return SEEN_ERROR;
    size_t mask = s->cap - 1U;
    size_t i = (size_t)(seen_hash(dev, ino) & mask);
    while (s->used[i]) {
        if (s->slots[i].dev == dev && s->slots[i].ino == ino) return SEEN_DUP;
        i = (i + 1U) & mask;
    }
    s->used[i] = 1U;
    s->slots[i].dev = dev;
    s->slots[i].ino = ino;
    s->n++;
    return SEEN_NEW;
}

static bool seen_grow(SeenSet *s) {
    SeenSet bigger;
    if (!seen_init(&bigger, s->cap)) return false;
    for (size_t i = 0; i < s->cap; i++) {
        if (s->used[i] && seen_add(&bigger, s->slots[i].dev, s->slots[i].ino) == SEEN_ERROR) {
            seen_free(&bigger);
            return false;
        }
    }
    seen_free(s);
    *s = bigger;
    return true;
}

/* 去重集合的持久化：shell 按存储卷分多次调用，跨调用必须共享去重状态。 */
static bool seen_load(SeenSet *s, const char *path) {
    if (!path) return true;
    FILE *f = fopen(path, "rb");
    if (!f) return errno == ENOENT;
    SeenKey key;
    bool ok = true;
    while (fread(&key, sizeof(key), 1, f) == 1) {
        if (seen_add(s, key.dev, key.ino) == SEEN_ERROR) { ok = false; break; }
    }
    if (ferror(f)) ok = false;
    fclose(f);
    return ok;
}

static bool seen_append(const SeenSet *s, const char *path, const SeenKey *added, size_t added_n) {
    if (!path) return true;
    (void)s;
    FILE *f = fopen(path, "ab+");
    if (!f) return false;
    if (fseeko(f, 0, SEEK_END) != 0) { fclose(f); return false; }
    off_t start = ftello(f);
    if (start < 0) { fclose(f); return false; }
    bool ok = added_n == 0 || fwrite(added, sizeof(*added), added_n, f) == added_n;
    if (ok && fflush(f) != 0) ok = false;
    if (!ok) {
        clearerr(f);
        (void)ftruncate(fileno(f), start);
    }
    if (fclose(f) != 0) ok = false;
    return ok;
}

static bool ext_matches(const char *name, const char *const *exts, size_t n) {
    const char *dot = strrchr(name, '.');
    if (!dot) return false;
    for (size_t i = 0; i < n; i++) {
        if (strcasecmp(dot, exts[i]) == 0) return true;
    }
    return false;
}

/* 名称以这些后缀结尾的下载中间态直接跳过，与旧实现一致。 */
static bool is_partial(const char *name) {
    static const char *const partial[] = { ".part", ".partial", ".download", ".crdownload" };
    return ext_matches(name, partial, sizeof(partial) / sizeof(partial[0]));
}

static bool is_apk(const char *name) {
    static const char *const apk[] = { ".apk", ".apks", ".xapk", ".apkm" };
    if (ext_matches(name, apk, sizeof(apk) / sizeof(apk[0]))) return true;
    size_t len = strlen(name);
    return len >= 8U && strcasecmp(name + len - 8, ".zip.apk") == 0;
}

/*
 * 归类扩展名集合。此前是写死在这里的 31 个，而 organizer-worker.sh 的
 * category_for() 认识 68 个——差集里的 .m4a .aac .ogg .opus .webm .flv
 * .3gp .epub 等文件永远进不了索引，也就永远归类不到。
 * 现在改为运行时从 config/organizer-categories.conf 载入，与归类器同源。
 */
static StrVec g_organizer_exts = {0};

/* 载入 "分类名=ext1 ext2 ..." 形式的分类表，只取扩展名集合。 */
static bool load_organizer_exts(const char *path) {
    vec_free(&g_organizer_exts);
    if (!path) return false;
    FILE *f = fopen(path, "r");
    if (!f) return false;
    char *line = NULL;
    size_t cap = 0;
    while (getline(&line, &cap, f) >= 0) {
        char *p = line;
        while (isspace((unsigned char)*p)) p++;
        if (!*p || *p == '#') continue;
        char *eq = strchr(p, '=');
        if (!eq) continue;
        char *list = eq + 1;
        char *saveptr = NULL;
        for (char *tok = strtok_r(list, " \t\r\n", &saveptr); tok;
             tok = strtok_r(NULL, " \t\r\n", &saveptr)) {
            if (!*tok) continue;
            char dotted[64];
            if ((size_t)snprintf(dotted, sizeof(dotted), ".%s", tok) >= sizeof(dotted)) continue;
            vec_add(&g_organizer_exts, dotted);
        }
    }
    free(line);
    fclose(f);
    vec_sort_unique(&g_organizer_exts);
    return g_organizer_exts.n > 0U;
}

static bool is_organizer(const char *name) {
    if (g_organizer_exts.n == 0U) return false;
    const char *dot = strrchr(name, '.');
    if (!dot) return false;
    for (size_t i = 0; i < g_organizer_exts.n; i++) {
        if (strcasecmp(dot, g_organizer_exts.v[i]) == 0) return true;
    }
    return false;
}

static bool b64_encode(const char *in, FILE *out) {
    static const char *tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t len = strlen(in);
    size_t i = 0;
    while (i + 2U < len) {
        unsigned v = ((unsigned char)in[i] << 16) | ((unsigned char)in[i + 1] << 8) | (unsigned char)in[i + 2];
        fputc(tbl[(v >> 18) & 63U], out); fputc(tbl[(v >> 12) & 63U], out);
        fputc(tbl[(v >> 6) & 63U], out);  fputc(tbl[v & 63U], out);
        i += 3U;
    }
    if (len - i == 1U) {
        unsigned v = (unsigned)((unsigned char)in[i] << 16);
        fputc(tbl[(v >> 18) & 63U], out); fputc(tbl[(v >> 12) & 63U], out);
        fputc('=', out); fputc('=', out);
    } else if (len - i == 2U) {
        unsigned v = ((unsigned)(unsigned char)in[i] << 16) | ((unsigned)(unsigned char)in[i + 1] << 8);
        fputc(tbl[(v >> 18) & 63U], out); fputc(tbl[(v >> 12) & 63U], out);
        fputc(tbl[(v >> 6) & 63U], out);  fputc('=', out);
    }
    return !ferror(out);
}

static int index_files(const Options *o) {
    if (!o->index_list_path) die("index-files requires --list");
    FILE *list = fopen(o->index_list_path, "rb");
    if (!list) die("cannot open --list");

    SeenSet seen;
    if (!seen_init(&seen, 4096)) { fclose(list); die("out of memory"); }
    if (!seen_load(&seen, o->index_seen_path)) {
        fclose(list);
        seen_free(&seen);
        return 5;
    }
    if (o->index_organizer_path && !load_organizer_exts(o->index_organizer_exts_path)) {
        fclose(list);
        seen_free(&seen);
        return 5;
    }

    FILE *records   = o->index_records_path    ? fopen(o->index_records_path, "ab")    : NULL;
    FILE *apk       = o->index_apk_path        ? fopen(o->index_apk_path, "ab")        : NULL;
    FILE *empty     = o->index_empty_path      ? fopen(o->index_empty_path, "ab")      : NULL;
    FILE *large     = o->index_large_path      ? fopen(o->index_large_path, "ab")      : NULL;
    FILE *organizer = o->index_organizer_path  ? fopen(o->index_organizer_path, "ab")  : NULL;
    FILE *dups      = o->index_duplicates_path ? fopen(o->index_duplicates_path, "ab") : NULL;

    if ((o->index_records_path && !records) || (o->index_apk_path && !apk) ||
        (o->index_empty_path && !empty) || (o->index_large_path && !large) ||
        (o->index_organizer_path && !organizer) || (o->index_duplicates_path && !dups)) {
        if (records) fclose(records); if (apk) fclose(apk); if (empty) fclose(empty);
        if (large) fclose(large); if (organizer) fclose(organizer); if (dups) fclose(dups);
        fclose(list);
        seen_free(&seen);
        vec_free(&g_organizer_exts);
        return 5;
    }

    SeenKey *added = NULL;
    size_t added_n = 0, added_cap = 0;

    uint64_t files = 0, bytes = 0, duplicates = 0, skipped = 0;
    char *line = NULL;
    size_t cap = 0;
    ssize_t len;
    uint64_t counter = 0;
    int rc = stop_requested(o) ? 9 : 0;
    while (rc == 0 && (len = getdelim(&line, &cap, '\0', list)) > 0) {
        if (line[len - 1] == '\0') len--;
        if (len == 0) continue;
        if ((++counter & 0xFFU) == 0U && stop_requested(o)) { rc = 9; break; }

        struct stat st;
        if (lstat(line, &st) != 0 || !S_ISREG(st.st_mode)) { skipped++; continue; }
        const char *base = strrchr(line, '/');
        base = base ? base + 1 : line;
        if (is_partial(base)) { skipped++; continue; }

        SeenResult seen_result = seen_add(&seen, st.st_dev, st.st_ino);
        if (seen_result == SEEN_ERROR) { rc = 12; break; }
        if (seen_result == SEEN_DUP) { duplicates++; continue; }
        if (added_n == added_cap) {
            size_t grown = added_cap ? added_cap * 2U : 1024U;
            SeenKey *tmp = realloc(added, grown * sizeof(*tmp));
            if (!tmp) { rc = 12; break; }
            added = tmp; added_cap = grown;
        }
        added[added_n].dev = st.st_dev;
        added[added_n].ino = st.st_ino;
        added_n++;

        uint64_t size = (uint64_t)st.st_size;
        if (records) { fwrite(line, 1, (size_t)len, records); fputc('\0', records); }
        files++;
        bytes += size;

        if (apk && is_apk(base)) { fwrite(line, 1, (size_t)len, apk); fputc('\0', apk); }
        if (empty && size == 0U) { fwrite(line, 1, (size_t)len, empty); fputc('\0', empty); }
        if (large && o->index_large_bytes > 0U && size >= o->index_large_bytes) {
            fwrite(line, 1, (size_t)len, large); fputc('\0', large);
        }
        if (organizer && is_organizer(base)) { fwrite(line, 1, (size_t)len, organizer); fputc('\0', organizer); }
        if (dups && size > 0U) {
            fprintf(dups, "%" PRIu64 "\t", size);
            if (!b64_encode(line, dups)) { rc = 5; break; }
            fputc('\n', dups);
        }
        if ((records && ferror(records)) || (apk && ferror(apk)) || (empty && ferror(empty)) ||
            (large && ferror(large)) || (organizer && ferror(organizer)) || (dups && ferror(dups))) {
            rc = 5;
            break;
        }
    }
    if (rc == 0 && ferror(list)) rc = 5;
    free(line);
    fclose(list);

    if (records && fclose(records) != 0 && rc == 0) rc = 5;
    if (apk && fclose(apk) != 0 && rc == 0) rc = 5;
    if (empty && fclose(empty) != 0 && rc == 0) rc = 5;
    if (large && fclose(large) != 0 && rc == 0) rc = 5;
    if (organizer && fclose(organizer) != 0 && rc == 0) rc = 5;
    if (dups && fclose(dups) != 0 && rc == 0) rc = 5;

    bool persisted = rc == 0 && seen_append(&seen, o->index_seen_path, added, added_n);
    if (rc == 0 && !persisted) rc = 5;
    free(added);
    seen_free(&seen);
    vec_free(&g_organizer_exts);

    if (rc == 0 && o->summary_path) {
        FILE *sum = fopen(o->summary_path, "w");
        if (!sum) {
            rc = 5;
        } else {
            fprintf(sum, "files=%" PRIu64 "\nbytes=%" PRIu64 "\nduplicates=%" PRIu64
                         "\nskipped=%" PRIu64 "\nseen_persisted=%d\n",
                    files, bytes, duplicates, skipped, persisted ? 1 : 0);
            bool sum_ok = !ferror(sum);
            if (fclose(sum) != 0) sum_ok = false;
            if (!sum_ok) rc = 5;
        }
    }
    return rc;
}

static int scan_deep(const Options *o) {
    require_outputs(o);
    if (!o->rules_path) die("rules required");
    load_lines(o->whitelist_path, &g_whitelist, true);
    FILE *rules = fopen(o->rules_path, "r");
    if (!rules) die("cannot open rules");
    StrVec cand = {0};
    uint64_t parse_started_ms = monotonic_ms();
    char *line = NULL;
    size_t cap = 0;
    uint64_t rn = 0;
    while (getline(&line, &cap, rules) >= 0) {
        if (stop_requested(o)) { free(line); fclose(rules); vec_free(&cand); return 9; }
        rn++;
        if (rn == 1 || rn % 256 == 0) atomic_progress(o, "deep-scan", "C 原生解析深度规则", rn, 0, line);
        /*
         * 规则支持可选的显式风险标注："绝对路径|low|medium|high|critical"。
         * 未标注的规则继续走路径推断，因此旧规则文件完全兼容。
         */
        char *bar = strrchr(line, '|');
        if (bar) {
            char risk[16];
            char *r = bar + 1;
            while (isspace((unsigned char)*r)) r++;
            size_t rl = strlen(r);
            while (rl > 0 && isspace((unsigned char)r[rl - 1])) r[--rl] = '\0';
            if (rl < sizeof(risk) && valid_risk_name(r)) {
                snprintf(risk, sizeof(risk), "%s", r);
                *bar = '\0';
                char *norm = normalize_rule(line);
                if (norm) { risk_rules_add(norm, risk, false); free(norm); }
            }
        }
        expand_rule(line, &cand);
    }
    free(line);
    fclose(rules);
    /* 用户覆盖最后加载，优先级高于规则文件内的标注。 */
    load_risk_overrides(o->risk_overrides_path);
    vec_sort_unique(&cand);
    uint64_t parse_finished_ms = monotonic_ms();
    g_deep_parse_ms = parse_finished_ms >= parse_started_ms ? parse_finished_ms - parse_started_ms : 0U;
    FILE *rep = open_report(o->report_path);
    FILE *targets = fopen(o->targets_path, "w");
    Totals t = {0};
    char covered_all[PATH_MAX] = "", covered_protected[PATH_MAX] = "";
    uint64_t stage_started_ms = monotonic_ms();
    for (size_t i = 0; i < cand.n; i++) {
        const char *p = cand.v[i];
        if (stop_requested(o)) { if (rep) fclose(rep); if (targets) fclose(targets); write_summary(o, &t); vec_free(&cand); return 9; }
        uint64_t stage_now_ms = monotonic_ms();
        if (o->global_budget_ms > 0U && stage_now_ms >= stage_started_ms && stage_now_ms - stage_started_ms >= o->global_budget_ms) { t.truncated = 1; break; }
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
        /*
         * 允许自动执行的最高风险等级。显式传入 --max-auto-risk 时以它为准；
         * 未传入时回退到旧的 --allow-high-risk 语义，保证老调用方行为不变。
         */
        int ceiling = o->max_auto_risk >= 0
                          ? o->max_auto_risk
                          : (o->allow_high_risk ? RISK_CRITICAL : RISK_MEDIUM);
        bool eligible = risk_rank(r) <= ceiling;
        if (!eligible) {
            t.protected_items++;
            report_row(rep, "protected", r, "深度规则", 1, 0, p);
            if (is_dir_nofollow(p)) snprintf(covered_protected, sizeof(covered_protected), "%s", p);
            continue;
        }
        Stats s;
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
    uint64_t stage_finished_ms = monotonic_ms();
    g_deep_stage_ms = stage_finished_ms >= stage_started_ms ? stage_finished_ms - stage_started_ms : 0U;
    if (rep) fclose(rep);
    if (targets) fclose(targets);
    write_summary(o, &t);
    vec_free(&cand);
    risk_rules_free();
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) die("usage: baize_engine <scan-corpses|scan-cache|scan-external-one-pass|clean-cache-snapshot|scan-deep|index-files> [options]");
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
    else if (strcmp(argv[1], "index-files") == 0) rc = index_files(&o);
    else die("unsupported command");
    vec_free(&g_whitelist);
    vec_free(&g_package_whitelist);
    return rc;
}
