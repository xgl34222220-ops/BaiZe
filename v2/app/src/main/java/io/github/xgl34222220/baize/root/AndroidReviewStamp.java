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
        if (Build.VERSION.SDK_INT < 27) throw new IOException("precise_file_identity_unsupported");
        try {
            StructStat s = Os.lstat(path);
            if (!OsConstants.S_ISREG(s.st_mode) && !OsConstants.S_ISDIR(s.st_mode))
                throw new IOException("special_file");
            return "android:" + s.st_dev + ":" + s.st_ino + ":" + s.st_mode + ":" + s.st_uid + ":" +
                s.st_gid + ":" + s.st_nlink + ":" + s.st_size + ":" + s.st_mtim.tv_sec + ":" +
                s.st_mtim.tv_nsec + ":" + s.st_ctim.tv_sec + ":" + s.st_ctim.tv_nsec;
        } catch (ErrnoException error) { throw new IOException("file_identity_unavailable", error); }
    }
}
