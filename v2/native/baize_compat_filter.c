#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Only transforms manifests. Policy, traversal, deletion and accounting stay in
 * cleaner.sh.compat. Inputs are never modified; the caller commits both outputs
 * only after success, or runs the original filter on failure. */
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
int main(int argc, char **argv) {
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
