#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
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

#define ENGINE_VERSION "42.4-preview1"
#define MAX_CANDIDATES 200000U

typedef struct { char **v; size_t n, cap; } StrVec;
typedef struct {
    uint64_t files, bytes, dirs, empty_dirs;
    bool oversized, mount_conflict, incomplete;
} Stats;
typedef struct {
    const char *media_root, *data_root, *installed_root, *whitelist_path;
    const char *package_whitelist_path, *rules_path, *report_path, *targets_path;
    const char *items_path, *summary_path, *progress_path, *stop_path;
    uint64_t max_file_bytes;
    int min_age_days;
    bool allow_high_risk;
} Options;
typedef struct {
    uint64_t files, bytes, dirs, empty_dirs, skipped, errors;
    uint64_t protected_items, protected_bytes, candidates, targets;
    uint64_t risk_low, risk_medium, risk_high, risk_critical;
    uint64_t mount_items, truncated, whitelisted;
} Totals;

static StrVec g_whitelist = {0}, g_package_whitelist = {0};
static time_t g_started;

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
    for (size_t i = 0; i < g_package_whitelist.n; i++) if (strcmp(pkg, g_package_whitelist.v[i]) == 0) return true;
    return false;
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
static bool installed_contains(const char *root, const char *user, const char *pkg) {
    char p[PATH_MAX];
    snprintf(p, sizeof(p), "%s/%s.txt", root, user);
    FILE *f = fopen(p, "r");
    if (!f) return false;
    char *line = NULL;
    size_t cap = 0;
    bool found = false;
    while (getline(&line, &cap, f) >= 0) {
        char *e = line + strlen(line);
        while (e > line && isspace((unsigned char)e[-1])) *--e = '\0';
        if (strcmp(line, pkg) == 0) { found = true; break; }
    }
    free(line);
    fclose(f);
    return found;
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
        if (eligible_mtime(&st, days)) {
            s->files++;
            s->bytes += (uint64_t)st.st_size;
            if ((uint64_t)st.st_size > max_bytes) s->oversized = true;
        }
        return 0;
    }
    if (!S_ISDIR(st.st_mode)) return 0;
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
static void write_summary(const Options *o, const Totals *t) {
    FILE *f = fopen(o->summary_path, "w");
    if (!f) return;
    fprintf(f,
        "files=%" PRIu64 "\nbytes=%" PRIu64 "\ndirs=%" PRIu64 "\nempty_dirs=%" PRIu64 "\nskipped=%" PRIu64 "\nerrors=%" PRIu64 "\nprotected_items=%" PRIu64 "\nprotected_bytes=%" PRIu64 "\ncandidates=%" PRIu64 "\ntargets=%" PRIu64 "\nrisk_low=%" PRIu64 "\nrisk_medium=%" PRIu64 "\nrisk_high=%" PRIu64 "\nrisk_critical=%" PRIu64 "\nmount_items=%" PRIu64 "\ntruncated=%" PRIu64 "\nwhitelisted=%" PRIu64 "\nengine=native-c-arm64\nversion=%s\n",
        t->files, t->bytes, t->dirs, t->empty_dirs, t->skipped, t->errors,
        t->protected_items, t->protected_bytes, t->candidates, t->targets,
        t->risk_low, t->risk_medium, t->risk_high, t->risk_critical,
        t->mount_items, t->truncated, t->whitelisted, ENGINE_VERSION);
    fclose(f);
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
        else if (strcmp(a, "--summary") == 0) o->summary_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--progress") == 0) o->progress_path = arg_value(argc, argv, &i);
        else if (strcmp(a, "--stop") == 0) o->stop_path = arg_value(argc, argv, &i);
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
                if (installed_contains(o->installed_root, ue->d_name, de->d_name)) continue;
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

static void cache_candidate(const Options *o, FILE *rep, FILE *targets, FILE *items, Totals *t, const char *pkg, const char *category, const char *path, uint64_t cur) {
    if (!is_dir_nofollow(path)) return;
    atomic_progress(o, "cache-scan", "C 原生应用缓存扫描", cur, 0, path);
    if (package_whitelisted(pkg) || whitelist_conflict(path)) {
        t->skipped++;
        t->whitelisted++;
        report_row(rep, "skipped", "protected", category, 0, 0, path);
        return;
    }
    Stats s;
    int rc = stat_tree(path, o, o->min_age_days, &s);
    if (rc == 9) return;
    if (rc < 0 || s.incomplete) t->errors++;
    if (s.files == 0) return;
    uint64_t count = s.files;
    if (s.oversized || s.mount_conflict) {
        t->protected_items += count;
        t->protected_bytes += s.bytes;
        if (s.mount_conflict) t->mount_items++;
        report_row(rep, "protected", "low", category, count, s.bytes, path);
        return;
    }
    t->files += s.files;
    t->bytes += s.bytes;
    t->dirs += s.dirs;
    t->candidates++;
    t->targets++;
    report_row(rep, "candidate", "low", category, count, s.bytes, path);
    if (targets) fprintf(targets, "%s\n", path);
    if (items) fprintf(items, "%s\t%s\t%" PRIu64 "\t%" PRIu64 "\t%" PRIu64 "\t%s\n", pkg, category, s.files, s.bytes, s.dirs, path);
}
static void scan_cache_user_root(const Options *o, const char *root, const char *prefix, FILE *rep, FILE *targets, FILE *items, Totals *t, uint64_t *cur) {
    DIR *users = opendir(root);
    if (!users) return;
    struct dirent *ue;
    while ((ue = readdir(users)) != NULL) {
        if (!isdigit((unsigned char)ue->d_name[0])) continue;
        char user[PATH_MAX];
        snprintf(user, sizeof(user), "%s/%s", root, ue->d_name);
        DIR *apps = opendir(user);
        if (!apps) continue;
        struct dirent *ae;
        while ((ae = readdir(apps)) != NULL) {
            if (ae->d_name[0] == '.' || !safe_package(ae->d_name)) continue;
            char app[PATH_MAX];
            snprintf(app, sizeof(app), "%s/%s", user, ae->d_name);
            const char *leaves[] = {"cache", "code_cache"};
            for (size_t i = 0; i < 2; i++) {
                if (stop_requested(o)) { closedir(apps); closedir(users); return; }
                char p[PATH_MAX];
                snprintf(p, sizeof(p), "%s/%s", app, leaves[i]);
                (*cur)++;
                char cat[320];
                snprintf(cat, sizeof(cat), "%s:%s", prefix, ae->d_name);
                cache_candidate(o, rep, targets, items, t, ae->d_name, cat, p, *cur);
            }
        }
        closedir(apps);
    }
    closedir(users);
}
static int scan_cache(const Options *o) {
    require_outputs(o);
    load_lines(o->whitelist_path, &g_whitelist, true);
    load_lines(o->package_whitelist_path, &g_package_whitelist, false);
    FILE *rep = open_report(o->report_path);
    FILE *targets = fopen(o->targets_path, "w");
    FILE *items = o->items_path ? fopen(o->items_path, "w") : NULL;
    if (items) fprintf(items, "package\tcategory\tfiles\tbytes\tdirectories\tpath\n");
    Totals t = {0};
    uint64_t cur = 0;
    char root[PATH_MAX];
    snprintf(root, sizeof(root), "%s/user", o->data_root);
    scan_cache_user_root(o, root, "内部应用缓存", rep, targets, items, &t, &cur);
    if (stop_requested(o)) goto stopped;
    snprintf(root, sizeof(root), "%s/user_de", o->data_root);
    scan_cache_user_root(o, root, "设备保护缓存", rep, targets, items, &t, &cur);
    if (stop_requested(o)) goto stopped;
    DIR *users = opendir(o->media_root);
    if (users) {
        struct dirent *ue;
        while ((ue = readdir(users)) != NULL) {
            if (!isdigit((unsigned char)ue->d_name[0])) continue;
            char appsroot[PATH_MAX];
            snprintf(appsroot, sizeof(appsroot), "%s/%s/Android/data", o->media_root, ue->d_name);
            DIR *apps = opendir(appsroot);
            if (!apps) continue;
            struct dirent *ae;
            while ((ae = readdir(apps)) != NULL) {
                if (ae->d_name[0] == '.' || !safe_package(ae->d_name)) continue;
                char app[PATH_MAX];
                snprintf(app, sizeof(app), "%s/%s", appsroot, ae->d_name);
                const char *leaves[] = {"cache", "code_cache"};
                for (size_t i = 0; i < 2; i++) {
                    if (stop_requested(o)) { closedir(apps); closedir(users); goto stopped; }
                    char p[PATH_MAX];
                    snprintf(p, sizeof(p), "%s/%s", app, leaves[i]);
                    cur++;
                    char cat[320];
                    snprintf(cat, sizeof(cat), "外部应用缓存:%s", ae->d_name);
                    cache_candidate(o, rep, targets, items, &t, ae->d_name, cat, p, cur);
                }
            }
            closedir(apps);
        }
        closedir(users);
    }
    if (rep) fclose(rep);
    if (targets) fclose(targets);
    if (items) fclose(items);
    write_summary(o, &t);
    return 0;
stopped:
    if (rep) fclose(rep);
    if (targets) fclose(targets);
    if (items) fclose(items);
    write_summary(o, &t);
    return 9;
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
    if (argc < 2) die("usage: baize_engine <scan-corpses|scan-cache|scan-deep> [options]");
    g_started = time(NULL);
    Options o;
    parse_options(argc, argv, &o);
    int rc;
    if (strcmp(argv[1], "scan-corpses") == 0) rc = scan_corpses(&o);
    else if (strcmp(argv[1], "scan-cache") == 0) rc = scan_cache(&o);
    else if (strcmp(argv[1], "scan-deep") == 0) rc = scan_deep(&o);
    else die("unsupported command");
    vec_free(&g_whitelist);
    vec_free(&g_package_whitelist);
    return rc;
}
