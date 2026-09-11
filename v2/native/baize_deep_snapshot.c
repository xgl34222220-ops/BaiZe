#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <signal.h>
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
    const char *roots;
    uint64_t checkpoint_records;
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
    uint64_t uncertain;
    uint64_t uncertain_bytes;
} Summary;

static Options g_options;
static StringList g_whitelist;
static uint64_t g_started_epoch;
static uint64_t g_global_deadline, g_target_deadline;
static volatile sig_atomic_t g_interrupted;
static bool g_recovery_requires_audit;

static void request_stop(int signal_number) {
    (void)signal_number;
    g_interrupted = 1;
}

static void die(const char *message);
static int read_record(FILE *file, Record *record);
static int read_nul_field(FILE *file, char **value, size_t *capacity);
static void free_record(Record *record);
static bool parse_u64(const char *text, uint64_t *value);
static int open_parent_nofollow(const char *path, char *buffer, const char **name);
static uint64_t monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) die("clock failed");
    return (uint64_t)now.tv_sec * 1000U + (uint64_t)now.tv_nsec / 1000000U;
}

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
    return g_interrupted || (g_options.stop && access(g_options.stop, F_OK) == 0);
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

static int snapshot_abort(void) {
    if (stop_requested()) return 9;
    uint64_t now = monotonic_ms();
    if ((g_global_deadline && now >= g_global_deadline) ||
        (g_target_deadline && now >= g_target_deadline)) return 124;
    return 0;
}

static int snapshot_path(int parent_fd, const char *name, const char *path,
                         const char *target, const char *risk, dev_t root_device, ino_t root_inode,
                         FILE *manifest, Summary *summary, unsigned depth) {
    if (depth > 512U) return -1;
    int abort_code = snapshot_abort();
    if (abort_code != 0) return abort_code;
    struct stat status;
    if (fstatat(parent_fd, name, &status, AT_SYMLINK_NOFOLLOW) != 0) return -1;
    if (depth == 0U && (status.st_dev != root_device || status.st_ino != root_inode)) return 7;
    if (S_ISLNK(status.st_mode)) return 0;
    if (status.st_dev != root_device) return 8;
    if (whitelist_conflict(path)) return 8;
    if (S_ISREG(status.st_mode)) {
        uint64_t size = status.st_size > 0 ? (uint64_t)status.st_size : 0U;
        if (size > g_options.max_file_bytes) return 8;
        abort_code = snapshot_abort();
        if (abort_code != 0) return abort_code;
        if (!write_record(manifest, "file", risk, target, &status, size, path)) return -1;
        summary->records++;
        summary->files++;
        summary->bytes += size;
        return 0;
    }
    if (!S_ISDIR(status.st_mode)) return 0;
    int fd = openat(parent_fd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat opened;
    if (fstat(fd, &opened) != 0 || opened.st_dev != status.st_dev || opened.st_ino != status.st_ino) {
        close(fd);
        return -1;
    }
    DIR *directory = fdopendir(fd);
    if (!directory) { close(fd); return -1; }
    int result = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (!entry) { if (errno) result = -1; break; }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char child[PATH_MAX];
        int written = snprintf(child, sizeof(child), "%s/%s", path, entry->d_name);
        if (written < 0 || (size_t)written >= sizeof(child)) { result = -1; break; }
        result = snapshot_path(fd, entry->d_name, child, target, risk, root_device, root_inode,
                               manifest, summary, depth + 1U);
        if (result != 0) break;
    }
    struct stat after;
    if (result == 0 && (fstat(fd, &after) != 0 ||
        fstatat(parent_fd, name, &status, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISDIR(status.st_mode) || status.st_dev != opened.st_dev || status.st_ino != opened.st_ino ||
        after.st_mtim.tv_sec != opened.st_mtim.tv_sec || after.st_mtim.tv_nsec != opened.st_mtim.tv_nsec ||
        after.st_ctim.tv_sec != opened.st_ctim.tv_sec || after.st_ctim.tv_nsec != opened.st_ctim.tv_nsec)) result = -1;
    closedir(directory);
    if (result != 0) return result;
    abort_code = snapshot_abort();
    if (abort_code != 0) return abort_code;
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
    FILE *roots = g_options.roots ? fopen(g_options.roots, "rb") : NULL;
    Record root_record = {0};
    uint64_t dir_budget = 0U;
    if (g_options.roots && (!roots ||
        read_nul_field(roots, &root_record.field[0], &root_record.capacity[0]) != 1 ||
        !parse_u64(root_record.field[0], &dir_budget) ||
        read_nul_field(roots, &root_record.field[0], &root_record.capacity[0]) != 1 ||
        !parse_u64(root_record.field[0], &g_global_deadline))) {
        if (roots) fclose(roots);
        free_record(&root_record);
        fclose(targets);
        fclose(manifest);
        unlink(g_options.manifest);
        return 7;
    }
    load_whitelist();
    FILE *report = g_options.report ? fopen(g_options.report, "w") : NULL;
    if (g_options.report && !report) {
        if (roots) fclose(roots);
        free_record(&root_record);
        fclose(targets);
        fclose(manifest);
        unlink(g_options.manifest);
        return 71;
    }
    if (report) fprintf(report, "action\trisk\tcategory\titems\tbytes\tpath\n");
    char *line = NULL;
    size_t capacity = 0;
    uint64_t current = 0, protected_targets = 0, timed_out_targets = 0;
    char covered[PATH_MAX] = "", covered_protected[PATH_MAX] = "";
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
        if (roots) {
            uint64_t dev, ino, mt, mn, ct, cn;
            if (read_record(roots, &root_record) != 1 ||
                strcmp(root_record.field[2], target) || strcmp(root_record.field[1], risk) ||
                !parse_u64(root_record.field[3], &dev) || !parse_u64(root_record.field[4], &ino) ||
                !parse_u64(root_record.field[6], &mt) || !parse_u64(root_record.field[7], &mn) ||
                !parse_u64(root_record.field[8], &ct) || !parse_u64(root_record.field[9], &cn) ||
                dev != (uint64_t)root.st_dev || ino != (uint64_t)root.st_ino ||
                mt != (uint64_t)root.st_mtim.tv_sec || mn != (uint64_t)root.st_mtim.tv_nsec ||
                ct != (uint64_t)root.st_ctim.tv_sec || cn != (uint64_t)root.st_ctim.tv_nsec) {
                result = 7;
                break;
            }
        }
        if (*covered && path_relation(covered, target)) continue;
        if (*covered_protected && path_relation(covered_protected, target) &&
            (strcmp(risk, "high") == 0 || strcmp(risk, "critical") == 0)) continue;
        g_target_deadline = dir_budget ? monotonic_ms() + dir_budget : 0U;
        current++;
        write_progress("deep-scan", "正在固化逐文件深度快照", current, 0U, target);
        FILE *temporary = tmpfile();
        if (!temporary) { result = 71; break; }
        Summary target_summary = {0};
        char parent_buffer[PATH_MAX];
        const char *name = NULL;
        int parent_fd = open_parent_nofollow(target, parent_buffer, &name);
        int code = parent_fd < 0 ? 7 : snapshot_path(parent_fd, name, target, target, risk,
                                 root.st_dev, root.st_ino, temporary, &target_summary, 0U);
        if (parent_fd >= 0) close(parent_fd);
        if (code == 0) code = copy_stream(temporary, manifest);
        fclose(temporary);
        if (roots && code == 8) {
            protected_targets++;
            if (S_ISDIR(root.st_mode)) snprintf(covered_protected, sizeof(covered_protected), "%s", target);
            if (report) fprintf(report, "protected\t%s\t深度规则\t1\t0\t%s\n", risk, target);
            continue;
        }
        if (roots && code == 124 && (!g_global_deadline || monotonic_ms() < g_global_deadline)) {
            timed_out_targets++;
            if (report) fprintf(report, "protected\tslow\t深度规则\t1\t0\t%s\n", target);
            continue;
        }
        if (code != 0) { result = code; break; }
        if (report) fprintf(report, "candidate\t%s\t深度规则\t%" PRIu64 "\t%" PRIu64 "\t%s\n",
                            risk, target_summary.files ? target_summary.files : 1U, target_summary.bytes, target);
        snprintf(covered, sizeof(covered), "%s", target);
        total.records += target_summary.records;
        total.files += target_summary.files;
        total.dirs += target_summary.dirs;
        total.bytes += target_summary.bytes;
        total.targets++;
    }
    if (ferror(targets)) result = 71;
    if (report) {
        if (ferror(report)) result = 71;
        if (fclose(report) != 0) result = 71;
    }
    if (roots && read_record(roots, &root_record) != 0 && result == 0) result = 7;
    if (roots) fclose(roots);
    free_record(&root_record);
    free(line);
    fclose(targets);
    if (fflush(manifest) != 0 || fsync(fileno(manifest)) != 0) result = 71;
    if (fclose(manifest) != 0) result = 71;
    if (result != 0) {
        unlink(g_options.manifest);
        return result < 0 ? 71 : result;
    }
    FILE *summary = fopen(g_options.summary, "w");
    if (!summary) return 71;
    fprintf(summary,
            "records=%" PRIu64 "\nfiles=%" PRIu64 "\ndirs=%" PRIu64
            "\nbytes=%" PRIu64 "\ntargets=%" PRIu64 "\nengine=%s\n",
            total.records, total.files, total.dirs, total.bytes, total.targets, ENGINE_VERSION);
    fprintf(summary, "protected_targets=%" PRIu64 "\ntimed_out_dirs=%" PRIu64
            "\nscan_complete=%d\ntruncated=0\n", protected_targets, timed_out_targets,
            timed_out_targets == 0U);
    bool summary_ok = !ferror(summary);
    if (fclose(summary) != 0) summary_ok = false;
    return summary_ok ? 0 : 71;
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
    for (const char *p = text; *p; ++p) if (!isdigit((unsigned char)*p)) return false;
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

static int open_parent_nofollow(const char *path, char *buffer, const char **name) {
    if (!path || path[0] != '/' || strlen(path) >= PATH_MAX) { errno = EINVAL; return -1; }
    strcpy(buffer, path);
    char *slash = strrchr(buffer, '/');
    *name = slash + 1;
    if (!**name || strcmp(*name, ".") == 0 || strcmp(*name, "..") == 0) { errno = EINVAL; return -1; }
    *slash = '\0';
    int fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    char *save = NULL;
    for (char *part = strtok_r(buffer, "/", &save); fd >= 0 && part; part = strtok_r(NULL, "/", &save)) {
        if (strcmp(part, ".") == 0 || strcmp(part, "..") == 0) { close(fd); errno = EINVAL; return -1; }
        int next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        int error = errno;
        close(fd);
        fd = next;
        errno = error;
    }
    return fd;
}

static int sync_mutations(int *fds, size_t *count) {
    int result = 0;
    for (size_t i = 0; i < *count; ++i) {
        if (fsync(fds[i]) != 0) result = -1;
        if (close(fds[i]) != 0) result = -1;
    }
    *count = 0;
    return result;
}

static int sync_parent(const char *path) {
    char parent[PATH_MAX];
    int length = snprintf(parent, sizeof(parent), "%s", path);
    if (length < 0 || (size_t)length >= sizeof(parent)) return -1;
    char *slash = strrchr(parent, '/');
    if (!slash) snprintf(parent, sizeof(parent), ".");
    else if (slash == parent) slash[1] = '\0';
    else *slash = '\0';
    int fd = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -1;
    int result = fsync(fd);
    if (close(fd) != 0) result = -1;
    return result;
}

static bool read_boot_id(char *id) {
    FILE *file = fopen("/proc/sys/kernel/random/boot_id", "r");
    if (!file) return false;
    char buffer[64];
    bool ok = fgets(buffer, sizeof(buffer), file) != NULL;
    if (fclose(file) != 0) ok = false;
    if (!ok) return false;
    buffer[strcspn(buffer, "\r\n")] = '\0';
    if (strlen(buffer) != 36U) return false;
    for (size_t i = 0; i < 36U; ++i) {
        bool separator = i == 8U || i == 13U || i == 18U || i == 23U;
        if (separator ? buffer[i] != '-' : !isxdigit((unsigned char)buffer[i])) return false;
    }
    strcpy(id, buffer);
    return true;
}

static uint64_t journal_hash(const char *text) {
    uint64_t hash = UINT64_C(14695981039346656037);
    for (; *text; ++text) { hash ^= (unsigned char)*text; hash *= UINT64_C(1099511628211); }
    return hash;
}

static int journal_line(FILE *file, const char *text) {
    if (fprintf(file, "%s\t%" PRIu64 "\n", text, journal_hash(text)) < 0) return -1;
    return fflush(file);
}

static int checkpoint(FILE *journal) {
    if (fflush(journal) != 0 || fsync(fileno(journal)) != 0) return -1;
    return 0;
}

static int seal_checkpoint(FILE *journal, uint64_t current) {
    char line[64];
    snprintf(line, sizeof(line), "C %" PRIu64, current);
    if (journal_line(journal, line) != 0) return -1;
    return checkpoint(journal);
}

static int journal_outcome(FILE *journal, const Summary *s) {
    char line[512];
    snprintf(line, sizeof(line), "R %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64
             " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64,
             s->processed, s->files, s->dirs, s->bytes, s->skipped, s->errors,
             s->uncertain, s->uncertain_bytes);
    return journal_line(journal, line);
}

/* The cursor is an append-only outcome journal. A durable batch intent precedes
 * mutation; outcomes are flushed before starting the next record, and fsynced
 * in batches. A missing record without an outcome is uncertain, never credited
 * as a successful deletion. unlink and accounting cannot be one transaction. */
static FILE *open_cursor(FILE *manifest, Summary *s, uint64_t *replay_end, uint64_t *unsealed) {
    struct stat st;
    if (fstat(fileno(manifest), &st) != 0) return NULL;
    char boot_id[37];
    if (!read_boot_id(boot_id)) {
        fprintf(stderr, "Cannot verify kernel boot identity; refusing to consume snapshot\n");
        return NULL;
    }
    /* Outcomes without a sealed batch only prove syscalls in this kernel boot.
     * Never promote them after power loss, when directory entries may reappear. */
    char header[512];
    snprintf(header, sizeof(header), "H %ju %ju %ju %jd %ld %jd %ld %s",
             (uintmax_t)st.st_dev, (uintmax_t)st.st_ino, (uintmax_t)st.st_size,
             (intmax_t)st.st_mtim.tv_sec, st.st_mtim.tv_nsec,
             (intmax_t)st.st_ctim.tv_sec, st.st_ctim.tv_nsec, boot_id);
    FILE *file = fopen(g_options.cursor, "r+");
    if (!file) return NULL;
    char *line = NULL;
    size_t capacity = 0;
    ssize_t length = getline(&line, &capacity, file);
    bool ok = length == 2 && strcmp(line, "0\n") == 0;
    bool have_header = false;
    uint64_t sealed = 0U;
    off_t valid_end = ftello(file);
    while (ok && (length = getline(&line, &capacity, file)) >= 0) {
        if (line[length - 1] != '\n') break;
        line[--length] = '\0';
        char *tab = strrchr(line, '\t');
        uint64_t hash;
        if (!tab || !parse_u64(tab + 1, &hash)) { ok = false; break; }
        *tab = '\0';
        if (hash != journal_hash(line)) { ok = false; break; }
        if (!have_header) {
            ok = strcmp(line, header) == 0;
            have_header = ok;
        } else if (strcmp(line, "U 1") == 0) {
            g_recovery_requires_audit = true;
        } else if (line[0] == 'C') {
            uint64_t end;
            ok = line[1] == ' ' && parse_u64(line + 2, &end) && end == s->processed;
            if (ok) sealed = end;
        } else if (line[0] == 'B') {
            uint64_t end;
            ok = line[1] == ' ' && parse_u64(line + 2, &end) && end >= s->processed && end <= s->records;
            if (ok) *replay_end = end;
        } else {
            Summary next = {0};
            int used = 0;
            int count = sscanf(line, "R %" SCNu64 " %" SCNu64 " %" SCNu64 " %" SCNu64
                               " %" SCNu64 " %" SCNu64 " %" SCNu64 " %" SCNu64 "%n",
                               &next.processed, &next.files, &next.dirs, &next.bytes,
                               &next.skipped, &next.errors, &next.uncertain, &next.uncertain_bytes, &used);
            ok = count == 8 && line[used] == '\0' && next.processed == s->processed + 1U &&
                 next.processed <= *replay_end && next.files >= s->files && next.dirs >= s->dirs &&
                 next.bytes >= s->bytes && next.skipped >= s->skipped && next.errors >= s->errors &&
                 next.uncertain >= s->uncertain && next.uncertain_bytes >= s->uncertain_bytes &&
                 next.files + next.dirs + next.skipped + next.errors == next.processed;
            if (ok) { next.records = s->records; *s = next; }
        }
        if (ok) valid_end = ftello(file);
    }
    if (ferror(file)) ok = false;
    free(line);
    if (!ok || ftruncate(fileno(file), valid_end) != 0 || fseeko(file, valid_end, SEEK_SET) != 0) {
        fclose(file);
        return NULL;
    }
    if ((!have_header && journal_line(file, header) != 0) || checkpoint(file) != 0 ||
        sync_parent(g_options.cursor) != 0) { fclose(file); return NULL; }
    *unsealed = s->processed - sealed;
    if (*unsealed && !g_recovery_requires_audit) {
        if (journal_line(file, "U 1") != 0 || checkpoint(file) != 0) { fclose(file); return NULL; }
        g_recovery_requires_audit = true;
    }
    return file;
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
    Summary summary = {0};
    summary.records = total;
    uint64_t replay_end = 0U, recovered_unsealed = 0U;
    FILE *journal = open_cursor(manifest, &summary, &replay_end, &recovered_unsealed);
    if (!journal) { free_record(&record); fclose(manifest); fclose(report); return 71; }
    uint64_t cursor = summary.processed;
    Summary initial = summary;
    rewind(manifest);
    for (uint64_t i = 0; i < cursor; ++i) {
        if (read_record(manifest, &record) != 1) { free_record(&record); fclose(manifest); fclose(report); fclose(journal); return 7; }
    }
    uint64_t current = cursor, batch_end = current;
    int mutation_fds[128];
    dev_t mutation_devs[128];
    ino_t mutation_inos[128];
    size_t mutation_count = 0U;
    int result = 0;
    while ((read_code = read_record(manifest, &record)) == 1) {
        if (stop_requested()) { result = 9; break; }
        if (current == batch_end) {
            batch_end = total - current > g_options.checkpoint_records ?
                current + g_options.checkpoint_records : total;
            char intent[64];
            snprintf(intent, sizeof(intent), "B %" PRIu64, batch_end > replay_end ? batch_end : replay_end);
            if (journal_line(journal, intent) != 0 || checkpoint(journal) != 0) { result = 71; break; }
        }
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
        bool common_ok = metadata_ok && valid_risk(risk) && deep_allowed(target) &&
                         deep_allowed(path) && path_relation(target, path) &&
                         !whitelist_conflict(path) && size <= g_options.max_file_bytes;
        char parent_buffer[PATH_MAX];
        const char *name = NULL;
        int parent_fd = common_ok ? open_parent_nofollow(path, parent_buffer, &name) : -1;
        int parent_error = errno;
        bool mutated = false;
        if (!common_ok || (strcmp(kind, "file") != 0 && strcmp(kind, "dir") != 0)) {
            summary.skipped++;
            report_row(report, "protected", valid_risk(risk) ? risk : "high", 0U, path);
        } else if (parent_fd < 0) {
            summary.skipped++;
            if (parent_error == ENOENT && current < replay_end) {
                summary.uncertain++;
                summary.uncertain_bytes += size;
            }
            report_row(report, parent_error == ENOENT ? "missing" : "protected", risk, 0U, path);
        } else if (strcmp(kind, "file") == 0) {
            struct stat first;
            struct stat second;
            if (fstatat(parent_fd, name, &first, AT_SYMLINK_NOFOLLOW) != 0) {
                summary.skipped++;
                if (errno == ENOENT && current < replay_end) { summary.uncertain++; summary.uncertain_bytes += size; }
                report_row(report, "missing", risk, 0U, path);
            } else if (!file_metadata_matches(&first, device, inode, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec) ||
                       fstatat(parent_fd, name, &second, AT_SYMLINK_NOFOLLOW) != 0 ||
                       !file_metadata_matches(&second, device, inode, size, mtime_sec, mtime_nsec, ctime_sec, ctime_nsec)) {
                summary.skipped++;
                if (current < replay_end) { summary.uncertain++; summary.uncertain_bytes += size; }
                report_row(report, "changed", risk, size, path);
            } else if (unlinkat(parent_fd, name, 0) == 0) {
                mutated = true;
                summary.files++;
                summary.bytes += size;
                report_row(report, "cleaned", risk, size, path);
            } else {
                summary.errors++;
                report_row(report, "failed", risk, size, path);
            }
        } else {
            struct stat status;
            if (fstatat(parent_fd, name, &status, AT_SYMLINK_NOFOLLOW) != 0) {
                summary.skipped++;
                if (errno == ENOENT && current < replay_end) summary.uncertain++;
                report_row(report, "missing", risk, 0U, path);
            } else if (!S_ISDIR(status.st_mode) || S_ISLNK(status.st_mode) ||
                       (uint64_t)status.st_dev != device || (uint64_t)status.st_ino != inode) {
                summary.skipped++;
                if (current < replay_end) summary.uncertain++;
                report_row(report, "changed", risk, 0U, path);
            } else if (unlinkat(parent_fd, name, AT_REMOVEDIR) == 0) {
                mutated = true;
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
        if (mutated) {
            struct stat parent;
            if (fstat(parent_fd, &parent) != 0) result = 71;
            else {
                size_t i;
                for (i = 0; i < mutation_count; ++i) {
                    if (mutation_devs[i] == parent.st_dev && mutation_inos[i] == parent.st_ino) break;
                }
                if (i == mutation_count) {
                    mutation_fds[i] = parent_fd;
                    mutation_devs[i] = parent.st_dev;
                    mutation_inos[i] = parent.st_ino;
                    mutation_count++;
                    parent_fd = -1;
                }
            }
        }
        if (parent_fd >= 0) close(parent_fd);
        current++;
        summary.processed++;
        if (journal_outcome(journal, &summary) != 0 || fflush(report) != 0) result = 71;
        if (current == batch_end) {
            if (sync_mutations(mutation_fds, &mutation_count) != 0) result = 71;
            if (result == 0 && seal_checkpoint(journal, current) != 0) result = 71;
        }
        if (result != 0) break;
    }
    if (read_code < 0) result = 7;
    if (sync_mutations(mutation_fds, &mutation_count) != 0) result = 71;
    if ((result == 0 || result == 9 || result == 7) && seal_checkpoint(journal, current) != 0) result = 71;
    if (checkpoint(journal) != 0) result = 71;
    if (fclose(journal) != 0) result = 71;
    free_record(&record);
    fclose(manifest);
    if (fclose(report) != 0) result = 71;
    FILE *summary_file = fopen(g_options.summary, "w");
    if (!summary_file) return 71;
    uint64_t remaining = total > current ? total - current : 0U;
    fprintf(summary_file,
            "records=%" PRIu64 "\nprocessed=%" PRIu64 "\nfiles=%" PRIu64
            "\ndirs=%" PRIu64 "\nbytes=%" PRIu64 "\nskipped=%" PRIu64
            "\nerrors=%" PRIu64 "\nremaining=%" PRIu64 "\ncursor=%" PRIu64
            "\nengine=%s\n",
            total, summary.processed - initial.processed, summary.files, summary.dirs, summary.bytes,
            summary.skipped, summary.errors, remaining, current, ENGINE_VERSION);
    fprintf(summary_file, "recovered_unsealed_records=%" PRIu64 "\nrecovery_requires_audit=%d\n",
            recovered_unsealed, g_recovery_requires_audit);
    fprintf(summary_file, "accounting=cumulative-journal-v1\nuncertain_records=%" PRIu64
            "\nuncertain_bytes=%" PRIu64 "\nrun_files=%" PRIu64 "\nrun_dirs=%" PRIu64
            "\nrun_bytes=%" PRIu64 "\nrun_errors=%" PRIu64 "\ncheckpoint_records=%" PRIu64 "\n",
            summary.uncertain, summary.uncertain_bytes, summary.files - initial.files,
            summary.dirs - initial.dirs, summary.bytes - initial.bytes, summary.errors - initial.errors,
            g_options.checkpoint_records);
    if (ferror(summary_file)) result = 71;
    if (fclose(summary_file) != 0) result = 71;
    return result;
}

static const char *option_value(int argc, char **argv, int *index) {
    if (*index + 1 >= argc) die("missing option value");
    return argv[++*index];
}

static void parse_options(int argc, char **argv) {
    memset(&g_options, 0, sizeof(g_options));
    g_options.max_file_bytes = 256ULL * 1024ULL * 1024ULL;
    g_options.checkpoint_records = 128U;
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
        else if (strcmp(argument, "--roots") == 0) g_options.roots = option_value(argc, argv, &i);
        else if (strcmp(argument, "--checkpoint-records") == 0) {
            if (!parse_u64(option_value(argc, argv, &i), &g_options.checkpoint_records) ||
                g_options.checkpoint_records < 1U || g_options.checkpoint_records > 128U) die("checkpoint records must be 1..128");
        }
        else if (strcmp(argument, "--max-file-bytes") == 0) g_options.max_file_bytes = strtoull(option_value(argc, argv, &i), NULL, 10);
        else die("unknown option");
    }
}

int main(int argc, char **argv) {
    if (argc < 2) die("usage: baize_deep_snapshot <build|clean> [options]");
    g_started_epoch = (uint64_t)time(NULL);
    struct sigaction action = {0};
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, NULL) != 0 || sigaction(SIGTERM, &action, NULL) != 0) return 71;
    parse_options(argc, argv);
    int result;
    if (strcmp(argv[1], "build") == 0) result = build_manifest();
    else if (strcmp(argv[1], "clean") == 0) result = clean_manifest();
    else die("unsupported command");
    list_free(&g_whitelist);
    return result;
}
