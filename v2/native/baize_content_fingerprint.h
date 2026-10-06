#ifndef BAIZE_CONTENT_FINGERPRINT_H
#define BAIZE_CONTENT_FINGERPRINT_H
#include "baize_sha256.h"
#include <fcntl.h>
#include <sys/stat.h>
static int baize_content_stamp_equal(const struct stat *a,const struct stat *b) {
    return S_ISREG(a->st_mode)&&S_ISREG(b->st_mode)&&a->st_dev==b->st_dev&&a->st_ino==b->st_ino&&
        a->st_size==b->st_size&&a->st_mtim.tv_sec==b->st_mtim.tv_sec&&a->st_mtim.tv_nsec==b->st_mtim.tv_nsec&&
        a->st_ctim.tv_sec==b->st_ctim.tv_sec&&a->st_ctim.tv_nsec==b->st_ctim.tv_nsec;
}
/* Success keeps the exact file fd open; caller closes it only after recording
 * the original snapshot or finishing the final fstatat/unlinkat. */
static int baize_hash_at(int parent,const char *name,const struct stat *expected,char digest[65],
                         int *opened,int (*abort_check)(void *),void *context) {
    *opened=-1;
    int fd=openat(parent,name,O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK);
    if(fd<0)return errno==ENOENT||errno==ELOOP?7:8;
    struct stat before,after,current;
    int code=0;
    if(fstat(fd,&before))code=8;
    else if(!baize_content_stamp_equal(expected,&before)||before.st_size<0)code=7;
    else code=baize_sha256_fd(fd,(uint64_t)before.st_size,digest,abort_check,context);
    if(!code && (fstat(fd,&after)||fstatat(parent,name,&current,AT_SYMLINK_NOFOLLOW)))code=8;
    if(!code && (!baize_content_stamp_equal(&before,&after)||!baize_content_stamp_equal(&before,&current)))code=7;
    if(code){close(fd);return code;}
    *opened=fd;return 0;
}
#endif
