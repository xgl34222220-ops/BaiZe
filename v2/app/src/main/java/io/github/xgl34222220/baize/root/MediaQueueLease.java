package io.github.xgl34222220.baize.root;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
/** Persistent inode, kernel-owned consumer lease. Never reclaimed by age or unlinked. */
final class MediaQueueLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private MediaQueueLease(FileChannel channel, FileLock lock) { this.channel=channel; this.lock=lock; }
    static MediaQueueLease acquire(File directory) throws IOException {
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Media queue state unavailable");
        FileChannel channel=FileChannel.open(new File(directory,"organizer-media-consumer.lease").toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        boolean held=false;
        try {
            FileLock lock;
            try { lock=channel.tryLock(); } catch(OverlappingFileLockException busy) { return null; }
            if(lock==null) return null;
            held=true;return new MediaQueueLease(channel,lock);
        } finally { if(!held) channel.close(); }
    }
    @Override public void close() {
        try { lock.release(); } catch(IOException ignored) {}
        try { channel.close(); } catch(IOException ignored) {}
    }
}
