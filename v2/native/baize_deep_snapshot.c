#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
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

#define MANIFEST_FIELDS 11U
#define ENGINE_VERSION "deep-manifest-v1"

typedef struct {
    const char *targets;
    const char *manifest;
    const char *cursor;
    const char *report;
    const char *summary;
    const char *whitelist;
    const char *progress;
    const char *stop;
    uint64_t max_file_bytes;
    bool safe_only;
} Options;

typedef struct {
    char **items;
    size_t count;
    size_t capacity;
} StringList;

typedef struct {
    char *field[MANIFEST_FIELDS];
    size_t capacity[MANIFEST_FIELDS];
} Record;

typedef struct {
    uint64_t records;
    uint64_t files;
    uint64_t dirs;
    uint64_t bytes;
    uint64_t targets;
    uint64_t processed;
    uint64_t skipped;
    uint64_t errors;
} Summary;

static Options g_options;
static StringList g_whitelist;
static uint64_t g_started_epoch;

static void die(const char *message) {
    fprintf(stderr, "%s\n", message);
    exit(2);
}

static void *checked_realloc(void *pointer, size_t size) {
    void *result = realloc(pointer, size ? size : 1U);
    if (!result) die("out of memory");
    return result;
}

static char *checked_strdup(const char *value) {
    char *result = strdup(value ? value : "");
    if (!result) die("out of memory");
    return result;
}

static void list_add(StringList *list, const char *value) {
    if (list->count == list->capacity) {
        list->capacity = list->capacity ? list->capacity * 2U : 64U;
        list->items = checked_realloc(list->items, list->capacity * sizeof(*list->items));
    }
    list->items[list->count++] = checked_strdup(value);
}

static void list_free(StringList *list) {
    for (size_t i = 0; i < list->count; ++i) free(list->items[i]);
    free(list->items);
    memset(list, 0, sizeof(*list));
}

static void normalize_path(char *path) {
    size_t length = path ? strlen(path) : 0U;
    while (length > 1U && path[length - 1U] == '/') path[--length] = '\0';
}

static bool path_relation(const char *parent, const char *child) {
    if (!parent || !child) return false;
    size_t length = strlen(parent);
    return strcmp(parent, child) == 0 ||
           (strncmp(parent, child, length) == 0 && child[length] == '/');
}

static bool deep_allowed(const char *path) {
    if (!path || path[0] != '/' || strcmp(path, "/") == 0) return false;
    const char *deny[] = {
        "/data/adb", "/data/app", "/data/system", "/data/misc", "/data/dalvik-cache",
        "/system", "/vendor", "/product", "/apex"
    };
    for (size_t i = 0; i < sizeof(deny) / sizeof(deny[0]); ++i) {
        if (path_relation(deny[i], path)) return false;
    }
    return strncmp(path, "/data/data/", 11U) == 0 ||
           strncmp(path, "/data/user/", 11U) == 0 ||
           strncmp(path, "/data/user_de/", 14U) == 0 ||
           strncmp(path, "/data/cache/", 12U) == 0 ||
           strncmp(path, "/data/media/", 12U) == 0 ||
           strncmp(path, "/data_mirror/data_ce/", 21U) == 0;
}

static bool valid_risk(const char *risk) {
    return risk && (strcmp(risk, "low") == 0 || strcmp(risk, "medium") == 0 ||
                    strcmp(risk, "high") == 0 || strcmp(risk, "critical") == 0);
}

static bool safe_risk(const char *risk) {
    return risk && (strcmp(risk, "low") == 0 || strcmp(risk, "medium") == 0);
}

static void load_whitelist(void) {
    if (!g_options.whitelist) return;
    FILE *file = fopen(g_options.whitelist, "r");
    if (!file) return;
    char *line = NULL;
    size_t capacity = 0;
    while (getline(&line, &capacity, file) >= 0) {
        char *start = line;
        while (isspace((unsigned char)*start)) ++start;
        char *end = start + strlen(start);
        while (end > start && isspace((unsigned char)end[-1])) *--end = '\0';
        if (*start != '/') continue;
        normalize_path(start);
        list_add(&g_whitelist, start);
    }
    free(line);
    fclose(file);
}

static bool whitelist_conflict(const char *path) {
    for (size_t i = 0; i < g_whitelist.count; ++i) {
        if (path_relation(g_whitelist.items[i], path) || path_relation(path, g_whitelist.items[i])) return true;
    }
    return false;
}

static bool stop_requested(void) {
    return g_options.stop && access(g_options.stop, F_OK) == 0;
}

static void sanitize_text(char *value) {
    for (; value && *value; ++value) {
        if (*value == '\t' || *value == '\r' || *value == '\n') *value = ' ';
    }
}

static void write_progress(const char *mode, const char *phase, uint64_t current,
                           uint64_t total, const char *path) {
    if (!g_options.progress) return;
    char temporary[PATH_MAX];
    if (snprintf(temporary, sizeof(temporary), "%s.tmp.%ld", g_options.progress, (long)getpid()) < 0) return;
    FILE *file = fopen(temporary, "w");
    if (!file) return;
    char clean_path[PATH_MAX];
    snprintf(clean_path, sizeof(clean_path), "%s", path ? path : "");
    sanitize_text(clean_path);
    fprintf(file,
            "mode=%s\nphase=%s\nstarted=%" PRIu64 "\nprogress_current=%" PRIu64
            "\nprogress_total=%" PRIu64 "\ncurrent_path=%s\nengine=%s\n",
            mode, phase, g_started_epoch, current, total, clean_path, ENGINE_VERSION);
    fclose(file);
    rename(temporary, g_options.progress);
}

static bool write_nul_field(FILE *file, const char *value) {
    size_t length = strlen(value) + 1U;
    return fwrite(value, 1U, length, file) == length;
}

static bool write_nul_u64(FILE *file, uint64_t value) {
    char text[32];
    snprintf(text, sizeof(text), "%" PRIu64, value);
    return write_nul_field(file, text);
}

static bool write_record(FILE *file, const char *kind, const char *risk, const char *target,
                         const struct stat *status, uint64_t size, const char *path) {
    return write_nul_field(file, kind) && write_nul_field(file, risk) &&
           write_nul_field(file, target) &&
           write_nul_u64(file, (uint64_t)status->st_dev) &&
           write_nul_u64(file, (uint64_t)status->st_ino) &&
           write_nul_u64(file, size) &&
           write_nul_u64(file, (uint64_t)status->st_mtim.tv_sec) &&
           write_nul_u64(file, (uint64_t)status->st_mtim.tv_nsec) &&
           write_nul_u64(file, (uint64_t)status->st_ctim.tv_sec) &&
           write_nul_u64(file, (uint64_t)status->st_ctim.tv_nsec) &&
           write_nul_field(file, path);
}

static int copy_stream(FILE *source, FILE *destination) {
    rewind(source);
    char buffer[16384];
    size_t count;
    while ((count = fread(buffer, 1U, sizeof(buffer), source)) > 0U) {
        if (fwrite(buffer, 1U, count, destination) != count) return -1;
    }
    return ferror(source) ? -1 : 0;
}

static int snapshot_path(const char *path, const char *target, const char *risk,
                         dev_t root_device, FILE *manifest, Summary *summary, unsigned depth) {
    if (depth > 512U) return -1;
    if (stop_requested()) return 9;
    struct stat status;
    if (lstat(path, &status) != 0) return -1;
    if (S_ISLNK(status.st_mode)) return 0;
    if (S_ISREG(status.st_mode)) {
        uint64_t size = status.st_size > 0 ? (uint64_t)status.st_size : 0U;
        if (size > g_options.max_file_bytes) return 8;
        if (!write_record(manifest, "file", risk, target, &status, size, path)) return -1;
        summary->records++;
        summary->files++;
        summary->bytes += size;
        return 0;
    }
    if (!S_ISDIR(status.st_mode)) return 0;
    if (depth > 0U && status.st_dev != root_device) return 8;
    DIR *directory = opendir(path);
    if (!directory) return -1;
    struct dirent *entry;
    int result = 0;
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char child[PATH_MAX];
        int written = snprintf(child, sizeof(child), "%s/%s", path, entry->d_name);
        if (written < 0 || (size_t)written >= sizeof(child)) { result = -1; break; }
        result = snapshot_path(child, target, risk, root_device, manifest, summary, depth + 1U);
        if (result != 0) break;
    }
    closedir(directory);
    if (result != 0) return result;
    if (lstat(path, &status) != 0 || !S_ISDIR(status.st_mode) || S_ISLNK(status.st_mode)) return -1;
    if (!write_record(manifest, "dir", risk, target, &status, 0U, path)) return -1;
    summary->records++;
    summary->dirs++;
    return 0;
}

static int build_manifest(void) {
    if (!g_options.targets || !g_options.manifest || !g_options.summary) die("missing build paths");
    FILE *targets = fopen(g_options.targets, "r");
    FILE *manifest = fopen(g_options.manifest, "wb");
    if (!targets || !manifest) {
        if (targets) fclose(targets);
        if (manifest) fclose(manifest);
        return 71;
    }
    Summary total = {0};
    char *line = NULL;
    size_t capacity = 0;
    uint64_t current = 0;
    int result = 0;
    while (getline(&line, &capacity, targets) >= 0) {
        char *end = line + strlen(line);
        while (end > line && (end[-1] == '\n' || end[-1] == '\r')) *--end = '\0';
        if (!*line) continue;
        char *tab = strrchr(line, '\t');
        if (!tab) { result = 7; break; }
        *tab = '\0';
        const char *target = line;
        const char *risk = tab + 1;
        normalize_path(line);
        if (!deep_allowed(target) || !valid_risk(risk)) { result = 7; break; }
        struct stat root;
        if (lstat(target, &root) != 0 || S_ISLNK(root.st_mode)) { result = 7; break; }
        current++;
        write_progress("deep-scan", "正在固化逐文件深度快照", current, 0U, target);
        FILE *temporary = tmpfile();
        if (!temporary) { result = 71; break; }
        Summary target_summary = {0};
        int code = snapshot_path(target, target, risk, root.st_dev, temporary, &target_summary, 0U);
        if (code == 0) code = copy_stream(temporary, manifest);
        fclose(temporary);
        if (code != 0) { result = code; break; }
        total.records += target_summary.records;
        total.files += target_summary.files;
        total.dirs += target_summary.dirs;
        total.bytes += target_summary.bytes;
        total.targets++;
    }
    free(line);
    fclose(targets);
    fflush(manifest);
    fsync(fileno(manifest));
    fclose(manifest);
    if (result != 0) {
        unlink(g_options.manifest);
        return result;
    }
    FILE *summary = fopen(g_options.summary, "w");
    if (!summary) return 71;
    fprintf(summary,
            "records=%" PRIu64 "\nfiles=%" PRIu64 "\ndirs=%" PRIu64
            "\nbytes=%" PRIu64 "\ntargets=%" PRIu64 "\nengine=%s\n",
            total.records, total.files, total.dirs, total.bytes, total.targets, ENGINE_VERSION);
    fclose(summary);
    return 0;
}

static int read_nul_field(FILE *file, char **value, size_t *capacity) {
    ssize_t length = getdelim(value, capacity, '\0', file);
    if (length < 0) return feof(file) ? 0 : -1;
    if (length == 0 || (*value)[length - 1] != '\0') return -1;
    (*value)[length - 1] = '\0';
    return 1;
}

static int read_record(FILE *file, Record *record) {
    int first = read_nul_field(file, &record->field[0], &record->capacity[0]);
    if (first <= 0) return first;
    for (size_t i = 1; i < MANIFEST_FIELDS; ++i) {
        if (read_nul_field(file, &record->field[i], &record->capacity[i]) != 1) return -1;
    }
    return 1;
}

static void free_record(Record *record) {
    for (size_t i = 0; i < MANIFEST_FIELDS; ++i) free(record->field[i]);
    memset(record, 0, sizeof(*record));
}

static bool parse_u64(const char *text, uint64_t *value) {
    if (!text || !*text) return false;
    char *end = NULL;
    errno = 0;
    unsigned long long parsed = strtoull(text, &end, 10);
    if (errno != 0 || !end || *end != '\0') return false;
    *value = (uint64_t)parsed;
    return true;
}

static bool file_metadata_matches(const struct stat *status, uint64_t device, uint64_t inode,
                                  uint64_t size, uint64_t mtime_sec, uint64_t mtime_nsec,
                                  uint64_t ctime_sec, uint64_t ctime_nsec) {
    uint64_t actual_size = status->st_size > 0 ? (uint64_t)status->st_size : 0U;
    return S_ISREG(status->st_mode) && !S_ISLNK(status->st_mode) &&
           (uint64_t)status->st_dev == device && (uint64_t)status->st_ino == inode &&
           actual_size == size &&
           (uint64_t)status->st_mtim.tv_sec == mtime_sec &&
           (uint64_t)status->st_mtim.tv_nsec == mtime_nsec &&
           (uint64_t)status->st_ctim.tv_sec == ctime_sec &&
           (uint64_t)status->st_ctim.tv_nsec == ctime_nsec;
}

static uint64_t read_cursor(void) {
    if (!g_options.cursor) return 0U;
    FILE *file = fopen(g_options.cursor, "r");
    if (!file) return 0U;
    unsigned long long value = 0U;
    int matched = fscanf(file, "%llu", &value);
    fclose(file);
    return matched == 1 ? (uint64_t)value : 0U;
}

static int write_cursor(uint64_t value) {
    if (!g_options.cursor) return 0;
    char temporary[PATH_MAX];
    if (snprintf(temporary, sizeof(temporary), "%s.tmp.%ld", g_options.cursor, (long)getpid()) < 0) return -1;
    FILE *file = fopen(temporary, "w");
    if (!file) return -1;
    fprintf(file, "%" PRIu64 "\n", value);
    fflush(file);
    fsync(fileno(file));
    fclose(file);
    return rename(temporary, g_options.cursor);
}

static FILE *open_report(void) {
    FILE *file = fopen(g_options.report, "w");
    if (file) fprintf(file, "action\trisk\tcategory\titems\tbytes\tpath\n");
    return file;
}

static void report_row(FILE *file, const char *action, const char *risk, uint64_t bytes, const char *path) {
    if (!file) return;
    char clean[PATH_MAX];
    snprintf(clean, sizeof(clean), "%s", path ? path : "");
    sanitize_text(clean);
    fprintf(file, "%s\t%s\t深度不可变快照\t1\t%" PRIu64 "\t%s\n",
            action, risk, bytes, clean);
}

static int clean_manifest(void) {
    if (!g_options.manifest || !g_options.cursor || !g_options.report || !g_options.summary) die("missing clean paths");
    load_whitelist();
    FILE *manifest = fopen(g_options.manifest, "rb");
    FILE *report = open_report();
    if (!manifest || !report) {
        if (manifest) fclose(manifest);
        if (report) fclose(report);
        return 71;
    }
    Record record = {0};
    uint64_t total = 0U;
    int read_code;
    while ((read_code = read_record(manifest, &record)) == 1) total++;
    if (read_code < 0) { free_record(&record); fclose(manifest); fclose(report); return 7; }
    uint64_t cursor = read_cursor();
    if (cursor > total) { free_record(&record); fclose(manifest); fclose(report); return 7; }
    rewind(manifest);
    for (uint64_t i = 0; i < cursor; ++i) {
        if (read_record(manifest, &record) != 1) { free_record(&record); fclose(manifest); fclose(report); return 7; }
    }
    Summary summary = {0};
    summary.records = total;
    uint64_t current = cursor;
    int result = 0;
    while ((read_code = read_record(manifest, &record)) == 1) {
        if (stop_requested()) { result = 9; break; }
        const char *kind = record.field[0];
        const char *risk = record.field[1];
        const char *target = record.field[2];
        const char *path = record.field[10];
        if (current == cursor || current % 128U == 0U || current + 1U == total) {
            write_progress("deep-clean", "正在消费逐文件深度快照", current, total, path);
        }
        uint64_t device, inode, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec;
        bool metadata_ok = parse_u64(record.field[3], &device) &&
                           parse_u64(record.field[4], &inode) &&
                           parse_u64(record.field[5], &size) &&
                           parse_u64(record.field[6], &mtime_sec) &&
                           parse_u64(record.field[7], &mtime_nsec) &&
                           parse_u64(record.field[8], &ctime_sec) &&
                           parse_u64(record.field[9], &ctime_nsec);
        bool common_ok = metadata_ok && valid_risk(risk) &&
                         (!g_options.safe_only || safe_risk(risk)) && deep_allowed(target) &&
                         deep_allowed(path) && path_relation(target, path) &&
                         !whitelist_conflict(path) && size <= g_options.max_file_bytes;
        if (!common_ok || (strcmp(kind, "file") != 0 && strcmp(kind, "dir") != 0)) {
            summary.skipped++;
            report_row(report, "protected", valid_risk(risk) ? risk : "high", 0U, path);
        } else if (strcmp(kind, "file") == 0) {
            struct stat first;
            struct stat second;
            if (lstat(path, &first) != 0) {
                summary.skipped++;
                report_row(report, "missing", risk, 0U, path);
            } else if (!file_metadata_matches(&first, device, inode, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec) ||
                       lstat(path, &second) != 0 ||
                       !file_metadata_matches(&second, device, inode, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec)) {
                summary.skipped++;
                report_row(report, "changed", risk, size, path);
            } else if (unlink(path) == 0) {
                summary.files++;
                summary.bytes += size;
                report_row(report, "cleaned", risk, size, path);
            } else {
                summary.errors++;
                report_row(report, "failed", risk, size, path);
            }
        } else {
            struct stat status;
            if (lstat(path, &status) != 0) {
                summary.skipped++;
                report_row(report, "missing", risk, 0U, path);
            } else if (!S_ISDIR(status.st_mode) || S_ISLNK(status.st_mode) ||
                       (uint64_t)status.st_dev != device || (uint64_t)status.st_ino != inode) {
                summary.skipped++;
                report_row(report, "changed", risk, 0U, path);
            } else if (rmdir(path) == 0) {
                summary.dirs++;
                report_row(report, "cleaned", risk, 0U, path);
            } else if (errno == ENOTEMPTY || errno == EEXIST) {
                summary.skipped++;
                report_row(report, "protected", risk, 0U, path);
            } else {
                summary.errors++;
                report_row(report, "failed", risk, 0U, path);
            }
        }
        current++;
        summary.processed++;
        if (write_cursor(current) != 0) { result = 71; break; }
    }
    if (read_code < 0) result = 7;
    free_record(&record);
    fclose(manifest);
    fclose(report);
    FILE *summary_file = fopen(g_options.summary, "w");
    if (!summary_file) return 71;
    uint64_t remaining = total > current ? total - current : 0U;
    fprintf(summary_file,
            "records=%" PRIu64 "\nprocessed=%" PRIu64 "\nfiles=%" PRIu64
            "\ndirs=%" PRIu64 "\nbytes=%" PRIu64 "\nskipped=%" PRIu64
            "\nerrors=%" PRIu64 "\nremaining=%" PRIu64 "\ncursor=%" PRIu64
            "\nengine=%s\n",
            total, summary.processed, summary.files, summary.dirs, summary.bytes,
            summary.skipped, summary.errors, remaining, current, ENGINE_VERSION);
    fclose(summary_file);
    return result;
}

static const char *option_value(int argc, char **argv, int *index) {
    if (*index + 1 >= argc) die("missing option value");
    return argv[++*index];
}

static void parse_options(int argc, char **argv) {
    memset(&g_options, 0, sizeof(g_options));
    g_options.max_file_bytes = 256ULL * 1024ULL * 1024ULL;
    for (int i = 2; i < argc; ++i) {
        const char *argument = argv[i];
        if (strcmp(argument, "--targets") == 0) g_options.targets = option_value(argc, argv, &i);
        else if (strcmp(argument, "--manifest") == 0) g_options.manifest = option_value(argc, argv, &i);
        else if (strcmp(argument, "--cursor") == 0) g_options.cursor = option_value(argc, argv, &i);
        else if (strcmp(argument, "--report") == 0) g_options.report = option_value(argc, argv, &i);
        else if (strcmp(argument, "--summary") == 0) g_options.summary = option_value(argc, argv, &i);
        else if (strcmp(argument, "--whitelist") == 0) g_options.whitelist = option_value(argc, argv, &i);
        else if (strcmp(argument, "--progress") == 0) g_options.progress = option_value(argc, argv, &i);
        else if (strcmp(argument, "--stop") == 0) g_options.stop = option_value(argc, argv, &i);
        else if (strcmp(argument, "--max-file-bytes") == 0) g_options.max_file_bytes = strtoull(option_value(argc, argv, &i), NULL, 10);
        else if (strcmp(argument, "--safe-only") == 0) g_options.safe_only = strcmp(option_value(argc, argv, &i), "1") == 0;
        else die("unknown option");
    }
}

int main(int argc, char **argv) {
    if (argc < 2) die("usage: baize_deep_snapshot <build|clean> [options]");
    g_started_epoch = (uint64_t)time(NULL);
    parse_options(argc, argv);
    int result = 0;
    if (strcmp(argv[1], "build") == 0) result = build_manifest();
    else if (strcmp(argv[1], "clean") == 0) result = clean_manifest();
    else die("unsupported command");
    list_free(&g_whitelist);
    return result;
}
