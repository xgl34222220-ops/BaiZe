#include <stdbool.h>
/* A stable inode fcntl write lock is shared with CleanupMediaWorker's NIO lock.
 * Hold it before the first unlink until all raw-byte outcomes have been written.
 * Closing the descriptor releases the lock even if the writer dies. */
static FILE *open_media_output(const char *path) {
    if (!path) return NULL;
    int fd = open(path, O_WRONLY | O_APPEND | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return NULL;
    struct flock lock = {0}; lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET;
    if (fcntl(fd, F_SETLK, &lock) != 0) { close(fd); return NULL; }
    /* The consumer can win before this late writer locks. It renames the
     * original directory under this same lock: never write to an orphan inode. */
    struct stat descriptor, original;
    if (fstat(fd, &descriptor) || lstat(path, &original) || !S_ISREG(original.st_mode) ||
        descriptor.st_dev != original.st_dev || descriptor.st_ino != original.st_ino) {
        close(fd); return NULL;
    }
    size_t size = strlen(path) + sizeof(".writer-ready");
    char *ready = malloc(size);
    if (!ready) { close(fd); return NULL; }
    snprintf(ready, size, "%s.writer-ready", path);
    int marker = open(ready, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    free(ready);
    if (marker < 0) { close(fd); return NULL; }
    const char version[] = "cleanup-media-writer-v1\n";
    bool ready_ok = write(marker, version, sizeof(version) - 1U) == (ssize_t)(sizeof(version) - 1U);
    if (close(marker)) ready_ok = false;
    if (!ready_ok) { close(fd); return NULL; }
    FILE *file = fdopen(fd, "ab");
    if (!file) { close(fd); return NULL; }
    setvbuf(file, NULL, _IONBF, 0);
    return file;
}
