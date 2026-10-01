package io.github.xgl34222220.baize.root;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.IOException;

/** Android's direct stat timestamps retain precision that a NIO provider may omit. */
final class AndroidReviewStamp {
    private AndroidReviewStamp() {}
    static String read(String path) throws IOException {
        try {
            StructStat s = Os.lstat(path);
            if (!OsConstants.S_ISREG(s.st_mode) && !OsConstants.S_ISDIR(s.st_mode))
                throw new IOException("special_file");
            String times = Build.VERSION.SDK_INT >= 27
                ? s.st_mtim.tv_sec + ":" + s.st_mtim.tv_nsec + ":" + s.st_ctim.tv_sec + ":" + s.st_ctim.tv_nsec
                : s.st_mtime + ":coarse:" + s.st_ctime + ":coarse";
            // The reviewed content digest independently covers coarse filesystem timestamps.
            return "android:" + s.st_dev + ":" + s.st_ino + ":" + s.st_mode + ":" + s.st_uid + ":" +
                s.st_gid + ":" + s.st_nlink + ":" + s.st_size + ":" + times;
        } catch (ErrnoException error) { throw new IOException("file_identity_unavailable", error); }
    }
}
