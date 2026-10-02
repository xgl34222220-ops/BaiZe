#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <inttypes.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "cleanup_media_output.h"
#include "baize_trash_guard.h"

/* Legacy invocation only de-duplicates manifests. The explicit --begin,
 * --snapshot and --delete modes implement a bounded, no-follow deletion
 * transaction for already policy-selected paths. They never discover targets. */
#define MAX_KEYS 1000000U
#define MAX_KEY_BYTES (64U * 1024U * 1024U)
#define MAX_RECORD 65536U
static char **keys;
static size_t capacity, used, key_bytes;
static const char *stop_path;

static void fail(void) { exit(5); }
static void checkpoint(void) {
    struct stat st;
    if (!stat(stop_path, &st) && S_ISREG(st.st_mode)) exit(9);
}
static uint64_t hash_key(const char *s) {
    uint64_t h = UINT64_C(14695981039346656037);
    while (*s) { h ^= (unsigned char)*s++; h *= UINT64_C(1099511628211); }
    return h;
}
static void grow(void) {
    size_t next = capacity ? capacity * 2 : 1024;
    char **table = calloc(next, sizeof(*table));
    if (!table) fail();
    for (size_t i = 0; i < capacity; i++) if (keys[i]) {
        size_t slot = hash_key(keys[i]) & (next - 1);
        while (table[slot]) slot = (slot + 1) & (next - 1);
        table[slot] = keys[i];
    }
    free(keys); keys = table; capacity = next;
}
static int insert(const char *s) {
    if (!capacity || (used + 1) * 4 >= capacity * 3) grow();
    size_t slot = hash_key(s) & (capacity - 1);
    while (keys[slot]) {
        if (!strcmp(keys[slot], s)) return 0;
        slot = (slot + 1) & (capacity - 1);
    }
    size_t n = strlen(s) + 1;
    if (used >= MAX_KEYS || n > MAX_KEY_BYTES - key_bytes) fail();
    keys[slot] = strdup(s);
    if (!keys[slot]) fail();
    used++; key_bytes += n;
    return 1;
}
static FILE *open_file(const char *path, int output) {
    int flags = output ? O_WRONLY | O_CREAT | O_EXCL : O_RDONLY;
    int fd = open(path, flags | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) fail();
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); fail(); }
    FILE *f = fdopen(fd, output ? "wb" : "rb");
    if (!f) { close(fd); fail(); }
    return f;
}
static void write_record(FILE *f, const char *s, size_t n) {
    if (fwrite(s, 1, n, f) != n) fail();
}
static ssize_t read_record(FILE *f, char *record, int delimiter) {
    size_t n = 0;
    int c;
    while ((c = fgetc(f)) != EOF) {
        if (n == MAX_RECORD) fail();
        record[n++] = (char)c;
        if (c == delimiter) { record[n] = 0; return (ssize_t)n; }
    }
    if (ferror(f) || n) fail();
    return -1;
}
static int filter_main(int argc, char **argv) {
    if (argc != 6) return 2;
    stop_path = argv[5];
    checkpoint();
    FILE *input = open_file(argv[1], 0), *seen = open_file(argv[2], 0);
    FILE *output = open_file(argv[3], 1), *next_seen = open_file(argv[4], 1);
    char *record = malloc(MAX_RECORD + 1);
    if (!record) fail();
    size_t records = 0;
    ssize_t n;
    while ((n = read_record(seen, record, '\n')) >= 0) {
        if ((++records & 127) == 0) checkpoint();
        if ((size_t)n > MAX_RECORD || record[n - 1] != '\n' || memchr(record, 0, (size_t)n)) fail();
        write_record(next_seen, record, (size_t)n);
        record[n - 1] = 0;
        insert(record);
    }
    if (ferror(seen)) fail();
    while ((n = read_record(input, record, 0)) >= 0) {
        if ((++records & 127) == 0) checkpoint();
        if ((size_t)n > MAX_RECORD || record[n - 1] != 0) fail();
        if (n == 1) continue;
        /* readlink -f differs from realpath for vanished targets. Let the
         * compatibility filter handle that case instead of guessing a key. */
        char *key = realpath(record, NULL);
        if (!key) fail();
        /* Match shell command substitution and the legacy CR/LF key encoding. */
        size_t len = strlen(key);
        while (len && key[len - 1] == '\n') key[--len] = 0;
        for (size_t i = 0; i < len; i++) if (key[i] == '\r' || key[i] == '\n') key[i] = ' ';
        if (insert(key)) {
            write_record(output, record, (size_t)n);
            write_record(next_seen, key, len);
            if (fputc('\n', next_seen) == EOF) fail();
        }
        free(key);
    }
    if (ferror(input)) fail();
    checkpoint();
    if (fclose(input) || fclose(seen) || fclose(output) || fclose(next_seen)) fail();
    for (size_t i = 0; i < capacity; i++) free(keys[i]);
    free(keys); free(record);
    return 0;
}

/* Ephemeral, same-ABI snapshots; never accepted as a persistent/public format. */
#define SNAP_MAGIC UINT64_C(0x42435a534e415031)
#define MAX_PARENTS 256U
#define SNAP_BYTES (64U * 1024U * 1024U)
typedef struct { struct timespec real, mono; } Boundary;
typedef struct { uint64_t dev, ino; } Parent;
typedef struct {
    uint32_t length, parents, disposition, kind;
    struct stat identity;
} Record;
typedef struct { Record r; char *path; Parent *parents; } Item;

static int stopped(void) {
    struct stat st;
    return !stat(stop_path, &st) && S_ISREG(st.st_mode);
}
static int clock_pair(Boundary *b) {
    return clock_gettime(CLOCK_REALTIME, &b->real) || clock_gettime(CLOCK_MONOTONIC, &b->mono);
}
static long double elapsed(struct timespec a, struct timespec b) {
    return (long double)a.tv_sec - b.tv_sec + ((long double)a.tv_nsec - b.tv_nsec) / 1000000000.L;
}
static int boundary_valid(const Boundary *b) {
    Boundary now;
    if (b->real.tv_sec <= 0 || b->mono.tv_sec < 0 || b->real.tv_nsec <= 0 || b->real.tv_nsec >= 1000000000 ||
        b->mono.tv_nsec < 0 || b->mono.tv_nsec >= 1000000000 || clock_pair(&now)) return 0;
    long double r = elapsed(now.real, b->real), m = elapsed(now.mono, b->mono);
    /* Reject backwards/stepped clocks and stale transactions. The one second
     * tolerance covers normal clock slewing; the selection fence rounds down. */
    return r >= 0 && m >= 0 && m <= 86400 && r - m > -1 && r - m < 1;
}
static void put(FILE *f, const void *p, size_t n) {
    if (fwrite(p, 1, n, f) != n) fail();
}
static void get(FILE *f, void *p, size_t n) {
    if (fread(p, 1, n, f) != n) fail();
}
static int same_id(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_mode == b->st_mode &&
        a->st_uid == b->st_uid && a->st_gid == b->st_gid && a->st_nlink == b->st_nlink &&
        a->st_size == b->st_size && a->st_mtim.tv_sec == b->st_mtim.tv_sec &&
        a->st_mtim.tv_nsec == b->st_mtim.tv_nsec && a->st_ctim.tv_sec == b->st_ctim.tv_sec &&
        a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}
static int record_matches(const Record *r, const struct stat *st) {
    if (r->kind != 2) return same_id(&r->identity, st) && (r->kind != 1 || st->st_size == 0);
    /* Removing selected children changes parent timestamps/nlink. rmdir is
     * atomic and requires an empty directory, so directory records pin the
     * object identity while allowing those effects of our own earlier work. */
    const struct stat *old = &r->identity;
    return S_ISDIR(st->st_mode) && old->st_dev == st->st_dev && old->st_ino == st->st_ino &&
        old->st_mode == st->st_mode && old->st_uid == st->st_uid && old->st_gid == st->st_gid;
}
static int clean_path(const char *p) {
    if (baize_is_ordinary_trash(p)) return 0;
    if (p[0] != '/' || !p[1] || p[strlen(p) - 1] == '/') return 0;
    for (const char *q = p + 1; *q;) {
        const char *end = strchr(q, '/');
        size_t n = end ? (size_t)(end - q) : strlen(q);
        if (!n || (n == 1 && q[0] == '.') || (n == 2 && q[0] == '.' && q[1] == '.')) return 0;
        q += n; if (*q) q++;
    }
    return 1;
}
typedef struct { char *path; Parent id; } KnownParent;
static KnownParent *known;
static size_t known_capacity, known_count, known_bytes;
static FILE *parent_log;
static const Boundary *parent_boundary;
static void grow_parents(void) {
    size_t capacity = known_capacity ? known_capacity * 2 : 128;
    KnownParent *next = calloc(capacity, sizeof(*next));
    if (!next) fail();
    for (size_t i = 0; i < known_capacity; i++) if (known[i].path) {
        size_t slot = hash_key(known[i].path) & (capacity - 1);
        while (next[slot].path) slot = (slot + 1) & (capacity - 1);
        next[slot] = known[i];
    }
    free(known); known = next; known_capacity = capacity;
}
/* Return 1 for the same path/object, -1 for an already known replacement. */
static int remember_parent(const char *path, Parent id, int add) {
    if (!known_capacity || (known_count + 1) * 4 >= known_capacity * 3) grow_parents();
    size_t slot = hash_key(path) & (known_capacity - 1);
    while (known[slot].path) {
        if (!strcmp(known[slot].path, path))
            return known[slot].id.dev == id.dev && known[slot].id.ino == id.ino ? 1 : -1;
        slot = (slot + 1) & (known_capacity - 1);
    }
    if (!add) return 0;
    size_t length = strlen(path) + 1;
    if (++known_count > MAX_KEYS || length + sizeof(KnownParent) > SNAP_BYTES - known_bytes) fail();
    known_bytes += length + sizeof(KnownParent);
    known[slot].path = strdup(path); known[slot].id = id;
    if (!known[slot].path) fail();
    return 0;
}
static int check_parent_time(const char *path, size_t length, const struct stat *st) {
    char *prefix = strndup(path, length ? length : 1);
    if (!prefix) fail();
    Parent id = { (uint64_t)st->st_dev, (uint64_t)st->st_ino };
    int seen = remember_parent(prefix, id, 0);
    if (seen < 0 || (!seen && parent_boundary && st->st_ctim.tv_sec >= parent_boundary->real.tv_sec - 1)) {
        free(prefix); errno = ESTALE; return -1;
    }
    if (!seen) {
        remember_parent(prefix, id, 1);
        uint32_t n = (uint32_t)strlen(prefix) + 1;
        put(parent_log, &n, sizeof(n)); put(parent_log, &id, sizeof(id)); put(parent_log, prefix, n);
    }
    free(prefix); return 0;
}
static FILE *load_parent_log(const char *path, Boundary *b) {
    int fd = open(path, O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size > SNAP_BYTES) fail();
    FILE *f = fdopen(fd, "r+b");
    if (!f) fail();
    get(f, b, sizeof(*b));
    for (;;) {
        uint32_t n; Parent id;
        size_t read = fread(&n, 1, sizeof(n), f);
        if (!read && !ferror(f)) break;
        if (read != sizeof(n) || n < 2 || n > MAX_RECORD) fail();
        char *name = malloc(n);
        if (!name) fail();
        get(f, &id, sizeof(id)); get(f, name, n);
        if (name[n - 1] || strlen(name) + 1 != n || (strcmp(name, "/") && !clean_path(name))) fail();
        if (remember_parent(name, id, 1) < 0) fail();
        free(name);
    }
    if (fseek(f, 0, SEEK_END)) fail();
    return f;
}
/* Open every ancestor without following links; record/compare each inode, not
 * just the final directory. A replaced intermediate directory is a change. */
static int parent_fd(const char *path, Parent *parents, uint32_t *count, int compare) {
    char *copy = strdup(path), *part, *slash;
    struct stat st;
    int fd = -1, next, saved;
    uint32_t n = 0, wanted = *count;
    if (!copy) { errno = ENOMEM; return -1; }
    fd = open("/", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) goto bad;
    part = copy + 1;
    for (;;) {
        if (fstat(fd, &st)) goto bad;
        if (n == MAX_PARENTS) { errno = EOVERFLOW; goto bad; }
        if (compare) {
            if (n >= wanted || parents[n].dev != (uint64_t)st.st_dev || parents[n].ino != (uint64_t)st.st_ino) {
                errno = ESTALE; goto bad;
            }
        } else {
            if (parent_log && check_parent_time(path, (size_t)(part - copy - 1), &st)) goto bad;
            parents[n].dev = st.st_dev; parents[n].ino = st.st_ino;
        }
        n++;
        slash = strchr(part, '/');
        if (!slash) break;
        *slash = 0;
        next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) {
            saved = errno;
            if ((saved == ELOOP || saved == ENOTDIR) && !fstatat(fd, part, &st, AT_SYMLINK_NOFOLLOW) &&
                !S_ISDIR(st.st_mode)) saved = ESTALE;
            errno = saved; goto bad;
        }
        close(fd); fd = next; part = slash + 1;
    }
    if (compare && n != wanted) { errno = ESTALE; goto bad; }
    *count = n; free(copy); return fd;
bad:
    saved = errno; if (fd >= 0) close(fd); free(copy); errno = saved; return -1;
}
static int disposition(int error) {
    if (error == ENOENT) return 2;
    if (error == ESTALE || error == ELOOP) return 1;
    return -1;
}
static int recent(const struct stat *st, const Boundary *b) {
    /* Same-second timestamps are ambiguous on coarse filesystems. Protect
     * them, even when nanoseconds appear to predate the collection start.
     * One extra second covers the accepted clock-slew tolerance; zero ctime
     * nanoseconds have unknown precision and cannot establish a safe fence. */
    return st->st_ctim.tv_nsec == 0 || st->st_ctim.tv_sec >= b->real.tv_sec - 1 ||
        st->st_mtim.tv_sec >= b->real.tv_sec - 1;
}
static int begin_main(int argc, char **argv) {
    if (argc != 3) return 2;
    Boundary b;
    if (clock_pair(&b)) return 5;
    FILE *f = open_file(argv[2], 1);
    put(f, &b, sizeof(b));
    /* Seed the boundary file's ancestors before discovery, including globally
     * mutable / or /data. Later unrelated sibling creation is not a new scope. */
    parent_log = f; parent_boundary = NULL;
    Parent parents[MAX_PARENTS]; uint32_t count = 0;
    int parent = parent_fd(argv[2], parents, &count, 0);
    if (parent < 0) return 5;
    close(parent);
    if (fclose(f)) return 5;
    parent_log = NULL;
    return 0;
}
static int snapshot_main(int argc, char **argv) {
    if (argc != 7 && argc != 8) return 2;
    uint64_t max_bytes = UINT64_MAX;
    if (argc == 8) {
        char *end; errno = 0;
        if (!argv[7][0] || strspn(argv[7], "0123456789") != strlen(argv[7])) return 2;
        max_bytes = strtoull(argv[7], &end, 10);
        if (errno || *end) return 2;
    }
    stop_path = argv[6];
    unsigned kind = !strcmp(argv[5], "file") ? 0 : !strcmp(argv[5], "empty") ? 1 :
                    !strcmp(argv[5], "directory") ? 2 : !strcmp(argv[5], "empty-tree") ? 3 : 4;
    if (kind == 4) return 2;
    Boundary b;
    parent_log = load_parent_log(argv[4], &b);
    parent_boundary = &b;
    if (!boundary_valid(&b)) return 5;
    FILE *input = open_file(argv[2], 0), *out = open_file(argv[3], 1);
    uint64_t magic = SNAP_MAGIC;
    put(out, &magic, sizeof(magic)); put(out, &b, sizeof(b));
    uint64_t sealed_count = UINT64_MAX; put(out, &sealed_count, sizeof(sealed_count));
    char *path = malloc(MAX_RECORD + 1);
    if (!path) fail();
    ssize_t length;
    size_t bytes = 0, total = 0;
    while ((length = read_record(input, path, 0)) >= 0) {
        if (stopped()) return 9;
        if (length <= 1 || !clean_path(path) || ++total > MAX_KEYS) return 5;
        Record r = {0}; Parent parents[MAX_PARENTS];
        r.length = (uint32_t)length; r.kind = kind;
        int fd = parent_fd(path, parents, &r.parents, 0), status = 0;
        if (fd < 0) status = disposition(errno);
        else {
            if (fstatat(fd, strrchr(path, '/') + 1, &r.identity, AT_SYMLINK_NOFOLLOW)) status = disposition(errno);
            else {
                if (kind == 3) r.kind = S_ISDIR(r.identity.st_mode) ? 2 : 1;
                Parent self = { (uint64_t)r.identity.st_dev, (uint64_t)r.identity.st_ino };
                int known_dir = r.kind == 2 && remember_parent(path, self, 0) == 1;
                if ((r.kind == 2 ? !S_ISDIR(r.identity.st_mode) : !S_ISREG(r.identity.st_mode)) ||
                    (r.kind == 1 && r.identity.st_size != 0) ||
                    (r.kind != 2 && (r.identity.st_size < 0 || (uint64_t)r.identity.st_size > max_bytes)) ||
                    (recent(&r.identity, &b) && !known_dir)) status = 1;
            }
            close(fd);
        }
        if (status < 0) return 8; /* Discard the whole unsealed snapshot. */
        r.disposition = (unsigned)status;
        if (r.kind == 3) r.kind = 1;
        size_t need = sizeof(r) + (size_t)length + r.parents * sizeof(Parent);
        if (need > SNAP_BYTES - bytes) return 5;
        bytes += need;
        put(out, &r, sizeof(r)); put(out, path, (size_t)length); put(out, parents, r.parents * sizeof(Parent));
    }
    if (!boundary_valid(&b) || stopped()) return stopped() ? 9 : 5;
    sealed_count = total;
    if (fseek(out, (long)(sizeof(magic) + sizeof(b)), SEEK_SET)) return 5;
    put(out, &sealed_count, sizeof(sealed_count));
    if (fclose(input) || fclose(out) || fclose(parent_log)) return 5;
    parent_log = NULL;
    free(path); return 0;
}
static int delete_main(int argc, char **argv) {
    if (argc != 6) return 2;
    stop_path = argv[5];
    FILE *in = open_file(argv[2], 0);
    uint64_t magic; Boundary b;
    get(in, &magic, sizeof(magic)); get(in, &b, sizeof(b));
    uint64_t sealed_count; get(in, &sealed_count, sizeof(sealed_count));
    if (sealed_count > MAX_KEYS) return 5;
    if (magic != SNAP_MAGIC || !boundary_valid(&b)) return 5;
    Item *items = NULL;
    size_t count = 0, cap = 0, bytes = 0;
    /* Validate the complete manifest before the first unlink. */
    for (;;) {
        Record r;
        size_t got = fread(&r, 1, sizeof(r), in);
        if (!got && !ferror(in)) break;
        if (got != sizeof(r) || r.length < 2 || r.length > MAX_RECORD || r.parents > MAX_PARENTS ||
            r.disposition > 2 || r.kind > 2 || (!r.disposition && !r.parents) || count >= MAX_KEYS) return 5;
        size_t need = sizeof(Item) + r.length + r.parents * sizeof(Parent);
        if (need > SNAP_BYTES - bytes) return 5;
        bytes += need;
        if (count == cap) {
            cap = cap ? cap * 2 : 128;
            Item *next = realloc(items, cap * sizeof(*items));
            if (!next) return 5;
            items = next;
        }
        Item *item = &items[count++]; item->r = r;
        item->path = malloc(r.length); item->parents = malloc((r.parents ? r.parents : 1) * sizeof(Parent));
        if (!item->path || !item->parents) return 5;
        get(in, item->path, r.length); get(in, item->parents, r.parents * sizeof(Parent));
        if (item->path[r.length - 1] || strlen(item->path) + 1 != r.length || !clean_path(item->path)) return 5;
    }
    if (count != sealed_count || fclose(in)) return 5;
    FILE *summary = open_file(argv[3], 1), *deleted = open_media_output(argv[4]);
    if (!deleted) { fclose(summary); return 5; }
    uint64_t cleaned = 0, released = 0, changed = 0, missing = 0, errors = 0, processed = 0;
    int result = 0;
    if (fprintf(summary, "schema=compat-delete-v1\nauthorized=%zu\n", count) < 0 || fflush(summary)) return 5;
    for (size_t i = 0; i < count; i++) {
        if (stopped()) { result = 9; break; }
        if (!boundary_valid(&b)) { errors++; result = 8; break; }
        Item *item = &items[i]; Record *r = &item->r;
        int status = (int)r->disposition;
        struct stat st;
        if (!status) {
            uint32_t n = r->parents;
            int fd = parent_fd(item->path, item->parents, &n, 1);
            if (fd < 0) status = disposition(errno);
            else {
                const char *name = strrchr(item->path, '/') + 1;
                if (fstatat(fd, name, &st, AT_SYMLINK_NOFOLLOW)) status = disposition(errno);
                else if (!record_matches(r, &st)) status = 1;
                if (!status) {
                    if (fstatat(fd, name, &st, AT_SYMLINK_NOFOLLOW)) status = disposition(errno);
                    else if (!record_matches(r, &st)) status = 1;
                }
                if (!status && unlinkat(fd, name, r->kind == 2 ? AT_REMOVEDIR : 0)) {
                    int e = errno;
                    status = e == ENOENT ? 2 : (r->kind == 2 && (e == ENOTEMPTY || e == EEXIST)) ? 1 : -1;
                }
                close(fd);
            }
        }
        processed++;
        if (status == 1) changed++;
        else if (status == 2) missing++;
        else if (status < 0) { errors++; result = 8; }
        else {
            cleaned++;
            if (r->kind != 2 && r->identity.st_size > 0) released += (uint64_t)r->identity.st_size;
            /* This NUL stream contains only successful unlinkat operations.
             * Flush each complete record so consumers can recover a prefix. */
            put(deleted, item->path, r->length);
            if (fflush(deleted)) return 5;
        }
        /* Preserve confirmed totals when the watchdog terminates a later
         * operation. The end marker rejects a partially written last record. */
        if (fprintf(summary, "progress=%" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64
            " %" PRIu64 " end\n", cleaned, released, changed, missing, errors, processed) < 0 ||
            fflush(summary)) return 5;
    }
    if (fprintf(summary, "processed=%" PRIu64 "\ncleaned=%" PRIu64
        "\nbytes=%" PRIu64 "\nchanged=%" PRIu64 "\nmissing=%" PRIu64 "\nerrors=%" PRIu64 "\n",
        processed, cleaned, released, changed, missing, errors) < 0) return 5;
    if (fclose(summary) || fclose(deleted)) return 5;
    for (size_t i = 0; i < count; i++) { free(items[i].path); free(items[i].parents); }
    free(items); return result;
}
int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "--begin")) return begin_main(argc, argv);
    if (argc > 1 && !strcmp(argv[1], "--snapshot")) return snapshot_main(argc, argv);
    if (argc > 1 && !strcmp(argv[1], "--delete")) return delete_main(argc, argv);
    return filter_main(argc, argv);
}
