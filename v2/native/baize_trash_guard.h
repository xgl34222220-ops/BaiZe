#ifndef BAIZE_TRASH_GUARD_H
#define BAIZE_TRASH_GUARD_H
#include <string.h>
#include <strings.h>
/* Reserved shared-mount payload roots are never ordinary cleaning candidates. */
static int baize_is_ordinary_trash(const char *path) {
    if (!path) return 0;
    const char *markers[] = {"/.baize-file-trash", "/Android/data/io.github.xgl34222220.baize/files/recoverable-trash"};
    for (unsigned i = 0; i < sizeof(markers) / sizeof(markers[0]); ++i) {
        size_t length = strlen(markers[i]);
        for (const char *cursor = path; *cursor; ++cursor) {
            if (strncasecmp(cursor, markers[i], length) == 0 &&
                (cursor[length] == '/' || cursor[length] == '\0')) return 1;
        }
    }
    return 0;
}
#endif
