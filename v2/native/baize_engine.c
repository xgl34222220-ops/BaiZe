#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define ENGINE_VERSION "42.3-preview1"

typedef struct {
    char **items;
    size_t count;
    size_t capacity;
} StringList;

typedef struct {
    char *path;
} Target;

typedef struct {
    Target *items;
    size_t count;
    size_t capacity;
} TargetList;

typedef struct {
    uint64_t files;
    uint64_t bytes;
    uint64_t errors;
    bool oversized;
} DirStats;

typedef struct {
    const char *media_root;
    const char *installed_root;
    const char *whitelist_path;
    const char *report_path;
    const char *targets_path;
    const char *summary_path;
    const char *progress_path;
    const char *stop_path;
    uint64_t max_file_bytes;
} Options;

typedef struct {
    uint64_t candidates;
    uint64_t files;
    uint64_t empty_dirs;
    uint64_t bytes;
    uint64_t protected_items;
    uint64_t protected_bytes;
    uint64_t skipped;
    uint64_t errors;
    uint64_t users;
    uint64_t targets;
    bool cancelled;
} Summary;

static void die_usage(void) {
    fprintf(stderr,
            "用法: baize_engine scan-corpses --media-root PATH --installed-root PATH "
            "--whitelist FILE --max-file-bytes N --report FILE --targets FILE "
            "--summary FILE --progress FILE --stop FILE\n");
    exit(2);
}

static void *xrealloc(void *ptr, size_t size) {
    void *next = realloc(ptr, size);
    if (!next) {
        fprintf(stderr, "内存不足\n");
        exit(70);
    }
    return next;
}

static char *xstrdup(const char *value) {
    char *copy = strdup(value ? value : "");
    if (!copy) {
        fprintf(stderr, "内存不足\n");
        exit(70);
    }
    return copy;
}

static bool is_numeric_name(const char *name) {
    if (!name || !*name) return false;
    for (const unsigned char *p = (const unsigned char *) name; *p; ++p) {
        if (!isdigit(*p)) return false;
    }
    return true;
}

static bool valid_package_name(const char *name) {
    if (!name || !*name || !strchr(name, '.')) return false;
    for (const unsigned char *p = (const unsigned char *) name; *p; ++p) {
        if (!(isalnum(*p) || *p == '.' || *p == '_' || *p == '-')) return false;
    }
    return true;
}

static void string_list_add(StringList *list, const char *value) {
    if (list->count == list->capacity) {
        list->capacity = list->capacity ? list->capacity * 2 : 64;
        list->items = xrealloc(list->items, list->capacity * sizeof(*list->items));
    }
    list->items[list->count++] = xstrdup(value);
}

static int compare_strings(const void *a, const void *b) {
    const char *const *left = a;
    const char *const *right = b;
    return strcmp(*left, *right);
}

static void string_list_sort_unique(StringList *list) {
    if (list->count < 2) return;
    qsort(list->items, list->count, sizeof(*list->items), compare_strings);
    size_t out = 1;
    for (size_t i = 1; i < list->count; ++i) {
        if (strcmp(list->items[i], list->items[out - 1]) == 0) {
            free(list->items[i]);
        } else {
            list->items[out++] = list->items[i];
        }
    }
    list->count = out;
}

static bool string_list_contains(const StringList *list, const char *value) {
    if (!list->count) return false;
    char *key = (char *) value;
    return bsearch(&key, list->items, list->count, sizeof(*list->items), compare_strings) != NULL;
}

static void string_list_free(StringList *list) {
    for (size_t i = 0; i < list->count; ++i) free(list->items[i]);
    free(list->items);
    memset(list, 0, sizeof(*list));
}

static void target_list_add(TargetList *list, const char *path) {
    if (list->count == list->capacity) {
        list->capacity = list->capacity ? list->capacity * 2 : 64;
        list->items = xrealloc(list->items, list->capacity * sizeof(*list->items));
    }
    list->items[list->count++].path = xstrdup(path);
}

static int compare_targets(const void *a, const void *b) {
    const Target *left = a;
    const Target *right = b;
    return strcmp(left->path, right->path);
}

static void target_list_sort_unique(TargetList *list) {
    if (list->count < 2) return;
    qsort(list->items, list->count, sizeof(*list->items), compare_targets);
    size_t out = 1;
    for (size_t i = 1; i < list->count; ++i) {
        if (strcmp(list->items[i].path, list->items[out - 1].path) == 0) {
            free(list->items[i].path);
        } else {
            if (out != i) list->items[out] = list->items[i];
            out++;
        }
    }
    list->count = out;
}

static void target_list_free(TargetList *list) {
    for (size_t i = 0; i < list->count; ++i) free(list->items[i].path);
    free(list->items);
    memset(list, 0, sizeof(*list));
}

static char *trim_line(char *line) {
    while (*line && isspace((unsigned char) *line)) line++;
    size_t len = strlen(line);
    while (len > 0 && isspace((unsigned char) line[len - 1])) line[--len] = '\0';
    return line;
}

static bool load_lines(const char *path, StringList *list, bool packages_only) {
    FILE *file = fopen(path, "re");
    if (!file) return false;
    char *line = NULL;
    size_t capacity = 0;
    while (getline(&line, &capacity, file) >= 0) {
        char *value = trim_line(line);
        if (!*value || *value == '#') continue;
        if (packages_only && !valid_package_name(value)) continue;
        string_list_add(list, value);
    }
    free(line);
    fclose(file);
    string_list_sort_unique(list);
    return true;
}

static bool path_has_prefix(const char *path, const char *prefix) {
    size_t length = strlen(prefix);
    while (length > 1 && prefix[length - 1] == '/') length--;
    return strncmp(path, prefix, length) == 0 && (path[length] == '\0' || path[length] == '/');
}

static bool is_whitelisted(const StringList *whitelist, const char *path) {
    for (size_t i = 0; i < whitelist->count; ++i) {
        if (path_has_prefix(path, whitelist->items[i])) return true;
    }
    return false;
}

static bool stop_requested(const char *stop_path) {
    return stop_path && access(stop_path, F_OK) == 0;
}

static int scan_dir_fd(int dir_fd, const Options *options, DirStats *stats) {
    int duplicate = dup(dir_fd);
    if (duplicate < 0) {
        stats->errors++;
        return -1;
    }
    DIR *dir = fdopendir(duplicate);
    if (!dir) {
        close(duplicate);
        stats->errors++;
        return -1;
    }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        if (stop_requested(options->stop_path)) {
            closedir(dir);
            return 9;
        }
        struct stat st;
        if (fstatat(dir_fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) {
            stats->errors++;
            continue;
        }
        if (S_ISLNK(st.st_mode)) continue;
        if (S_ISREG(st.st_mode)) {
            stats->files++;
            if (st.st_size > 0) stats->bytes += (uint64_t) st.st_size;
            if ((uint64_t) st.st_size > options->max_file_bytes) stats->oversized = true;
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            int child = openat(dir_fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
            if (child < 0) {
                stats->errors++;
                continue;
            }
            int code = scan_dir_fd(child, options, stats);
            close(child);
            if (code == 9) {
                closedir(dir);
                return 9;
            }
        }
    }
    closedir(dir);
    return 0;
}

static int scan_target(const char *path, const Options *options, DirStats *stats) {
    struct stat st;
    if (lstat(path, &st) != 0) return -1;
    if (!S_ISDIR(st.st_mode) || S_ISLNK(st.st_mode)) return -1;
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    int code = scan_dir_fd(fd, options, stats);
    close(fd);
    return code;
}

static void sanitize_tsv(const char *input, char *output, size_t size) {
    if (!size) return;
    size_t out = 0;
    for (const unsigned char *p = (const unsigned char *) input; *p && out + 1 < size; ++p) {
        unsigned char c = *p;
        output[out++] = (c == '\t' || c == '\r' || c == '\n') ? ' ' : (char) c;
    }
    output[out] = '\0';
}

static bool write_atomic_text(const char *path, const char *text) {
    char temporary[PATH_MAX];
    if (snprintf(temporary, sizeof(temporary), "%s.tmp.%ld", path, (long) getpid()) >= (int) sizeof(temporary)) return false;
    FILE *file = fopen(temporary, "we");
    if (!file) return false;
    bool ok = fputs(text, file) >= 0 && fflush(file) == 0 && fsync(fileno(file)) == 0;
    if (fclose(file) != 0) ok = false;
    if (ok && rename(temporary, path) == 0) return true;
    unlink(temporary);
    return false;
}

static void write_progress(const Options *options, uint64_t current, uint64_t total, const char *path) {
    char safe_path[PATH_MAX];
    sanitize_tsv(path ? path : "", safe_path, sizeof(safe_path));
    char text[PATH_MAX + 256];
    snprintf(text, sizeof(text),
             "mode=corpse-scan\nphase=原生引擎扫描卸载残留（%" PRIu64 "/%" PRIu64 "）\n"
             "progress_current=%" PRIu64 "\nprogress_total=%" PRIu64 "\ncurrent_path=%s\nengine=native-c-arm64\n",
             current, total, current, total, safe_path);
    write_atomic_text(options->progress_path, text);
}

static bool join_path(char *output, size_t size, const char *a, const char *b) {
    int written = snprintf(output, size, "%s/%s", a, b);
    return written > 0 && written < (int) size;
}

static void collect_targets_for_root(const char *root, const StringList *installed, TargetList *targets) {
    DIR *dir = opendir(root);
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        if (!valid_package_name(entry->d_name)) continue;
        if (string_list_contains(installed, entry->d_name)) continue;
        char path[PATH_MAX];
        if (!join_path(path, sizeof(path), root, entry->d_name)) continue;
        struct stat st;
        if (lstat(path, &st) != 0 || !S_ISDIR(st.st_mode) || S_ISLNK(st.st_mode)) continue;
        target_list_add(targets, path);
    }
    closedir(dir);
}

static int collect_targets(const Options *options, TargetList *targets, Summary *summary) {
    DIR *media = opendir(options->media_root);
    if (!media) return 7;
    struct dirent *user_entry;
    while ((user_entry = readdir(media)) != NULL) {
        if (!is_numeric_name(user_entry->d_name)) continue;
        char user_root[PATH_MAX];
        if (!join_path(user_root, sizeof(user_root), options->media_root, user_entry->d_name)) continue;
        struct stat user_stat;
        if (lstat(user_root, &user_stat) != 0 || !S_ISDIR(user_stat.st_mode) || S_ISLNK(user_stat.st_mode)) continue;

        char installed_path[PATH_MAX];
        char installed_name[64];
        if (snprintf(installed_name, sizeof(installed_name), "%s.txt", user_entry->d_name) >= (int) sizeof(installed_name)) continue;
        if (!join_path(installed_path, sizeof(installed_path), options->installed_root, installed_name)) continue;
        StringList installed = {0};
        if (!load_lines(installed_path, &installed, true)) {
            string_list_free(&installed);
            continue;
        }
        summary->users++;

        const char *subroots[] = {"Android/data", "Android/obb", "Android/media"};
        for (size_t i = 0; i < sizeof(subroots) / sizeof(subroots[0]); ++i) {
            char root[PATH_MAX];
            if (!join_path(root, sizeof(root), user_root, subroots[i])) continue;
            collect_targets_for_root(root, &installed, targets);
        }
        string_list_free(&installed);
    }
    closedir(media);
    target_list_sort_unique(targets);
    summary->targets = targets->count;
    return summary->users ? 0 : 7;
}

static int scan_corpses(const Options *options) {
    StringList whitelist = {0};
    load_lines(options->whitelist_path, &whitelist, false);

    TargetList targets = {0};
    Summary summary = {0};
    int collect_code = collect_targets(options, &targets, &summary);
    if (collect_code != 0) {
        string_list_free(&whitelist);
        target_list_free(&targets);
        return collect_code;
    }

    FILE *report = fopen(options->report_path, "we");
    FILE *target_file = fopen(options->targets_path, "we");
    if (!report || !target_file) {
        if (report) fclose(report);
        if (target_file) fclose(target_file);
        string_list_free(&whitelist);
        target_list_free(&targets);
        return 71;
    }
    fprintf(report, "action\trisk\tcategory\titems\tbytes\tpath\n");

    write_progress(options, 0, targets.count, "");
    for (size_t i = 0; i < targets.count; ++i) {
        const Target *target = &targets.items[i];
        if (stop_requested(options->stop_path)) {
            summary.cancelled = true;
            break;
        }
        if (is_whitelisted(&whitelist, target->path)) {
            summary.skipped++;
            char safe[PATH_MAX];
            sanitize_tsv(target->path, safe, sizeof(safe));
            fprintf(report, "skipped\tprotected\t卸载残留\t0\t0\t%s\n", safe);
        } else {
            DirStats stats = {0};
            int code = scan_target(target->path, options, &stats);
            if (code == 9) {
                summary.cancelled = true;
                break;
            }
            char safe[PATH_MAX];
            sanitize_tsv(target->path, safe, sizeof(safe));
            if (code != 0) {
                summary.errors++;
                fprintf(report, "failed\thigh\t卸载残留\t1\t0\t%s（无法统计）\n", safe);
            } else if (stats.oversized) {
                uint64_t report_count = stats.files ? stats.files : 1;
                summary.protected_items += report_count;
                summary.protected_bytes += stats.bytes;
                summary.errors += stats.errors;
                fprintf(report, "protected\thigh\t卸载残留\t%" PRIu64 "\t%" PRIu64 "\t%s（含超过单文件上限的文件）\n",
                        report_count, stats.bytes, safe);
            } else {
                uint64_t report_count = stats.files ? stats.files : 1;
                summary.candidates++;
                summary.files += stats.files;
                if (stats.files == 0) summary.empty_dirs++;
                summary.bytes += stats.bytes;
                summary.errors += stats.errors;
                fprintf(report, "candidate\thigh\t卸载残留\t%" PRIu64 "\t%" PRIu64 "\t%s\n",
                        report_count, stats.bytes, safe);
                fprintf(target_file, "%s\n", target->path);
            }
        }
        if (i == 0 || (i + 1) % 4 == 0 || i + 1 == targets.count) {
            write_progress(options, i + 1, targets.count, target->path);
        }
    }

    fflush(report);
    fsync(fileno(report));
    fflush(target_file);
    fsync(fileno(target_file));
    fclose(report);
    fclose(target_file);

    char summary_text[1024];
    snprintf(summary_text, sizeof(summary_text),
             "engine=native-c-arm64\nengine_version=%s\nusers=%" PRIu64 "\ntargets=%" PRIu64 "\n"
             "candidates=%" PRIu64 "\nfiles=%" PRIu64 "\nempty_dirs=%" PRIu64 "\nbytes=%" PRIu64 "\n"
             "protected_items=%" PRIu64 "\nprotected_bytes=%" PRIu64 "\nskipped=%" PRIu64 "\nerrors=%" PRIu64 "\n"
             "cancelled=%d\n",
             ENGINE_VERSION, summary.users, summary.targets, summary.candidates, summary.files,
             summary.empty_dirs, summary.bytes, summary.protected_items, summary.protected_bytes,
             summary.skipped, summary.errors, summary.cancelled ? 1 : 0);
    write_atomic_text(options->summary_path, summary_text);

    string_list_free(&whitelist);
    target_list_free(&targets);
    return summary.cancelled ? 9 : 0;
}

static uint64_t parse_u64(const char *value) {
    if (!value || !*value) die_usage();
    errno = 0;
    char *end = NULL;
    unsigned long long parsed = strtoull(value, &end, 10);
    if (errno || !end || *end) die_usage();
    return (uint64_t) parsed;
}

int main(int argc, char **argv) {
    if (argc < 2 || strcmp(argv[1], "scan-corpses") != 0) die_usage();
    Options options = {0};
    for (int i = 2; i < argc; ++i) {
        if (i + 1 >= argc) die_usage();
        const char *key = argv[i++];
        const char *value = argv[i];
        if (strcmp(key, "--media-root") == 0) options.media_root = value;
        else if (strcmp(key, "--installed-root") == 0) options.installed_root = value;
        else if (strcmp(key, "--whitelist") == 0) options.whitelist_path = value;
        else if (strcmp(key, "--max-file-bytes") == 0) options.max_file_bytes = parse_u64(value);
        else if (strcmp(key, "--report") == 0) options.report_path = value;
        else if (strcmp(key, "--targets") == 0) options.targets_path = value;
        else if (strcmp(key, "--summary") == 0) options.summary_path = value;
        else if (strcmp(key, "--progress") == 0) options.progress_path = value;
        else if (strcmp(key, "--stop") == 0) options.stop_path = value;
        else die_usage();
    }
    if (!options.media_root || !options.installed_root || !options.whitelist_path ||
        !options.report_path || !options.targets_path || !options.summary_path ||
        !options.progress_path || !options.stop_path || options.max_file_bytes == 0) {
        die_usage();
    }
    return scan_corpses(&options);
}
