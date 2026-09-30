package io.github.xgl34222220.baize.root;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cache-only traversal anchored to open directories. Never follows any path component symlink.
 * Unsupported providers fail closed: no path-based recursive-delete fallback is permitted.
 * Only the contents of a previously measured root are removable; the root itself is retained.
 */
public final class SecureCacheTree {
    private SecureCacheTree() {}
    public static final class Result {
        public long bytes, files, directories, visited;
        public boolean complete = true;
        public String identity = "";
        public String reason = "";
        private void incomplete(String why) { complete = false; if (reason.isEmpty()) reason = why; }
    }
    public interface Progress { void update(long files, long bytes); }
    private static final class Frame {
        final SecureDirectoryStream<Path> directory;
        final Iterator<Path> iterator;
        final Path name;
        Frame(SecureDirectoryStream<Path> directory, Path name) {
            this.directory = directory; this.iterator = directory.iterator(); this.name = name;
        }
    }
    public static Result measure(Path root, AtomicBoolean cancelled, long budgetMs, long maxEntries, Progress progress) {
        return walk(root, "", false, cancelled, budgetMs, maxEntries, progress);
    }
    public static Result clear(Path root, String identity, AtomicBoolean cancelled, Progress progress) {
        if (identity == null || identity.isEmpty()) {
            Result result = new Result(); result.incomplete("snapshot_identity_missing"); return result;
        }
        return walk(root, identity, true, cancelled, 60_000L, 1_000_000L, progress);
    }
    private static Result walk(Path root, String expected, boolean clean, AtomicBoolean cancelled,
                               long budgetMs, long maxEntries, Progress progress) {
        Result result = new Result();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        long start = System.nanoTime(), lastProgress = start;
        try {
            SecureDirectoryStream<Path> directory = openRoot(root);
            BasicFileAttributes attributes;
            try { attributes = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes(); }
            catch (Exception error) { directory.close(); throw error; }
            Object key = attributes.fileKey();
            result.identity = key == null ? "" : key.toString();
            if (result.identity.isEmpty() || (clean && !expected.equals(result.identity))) {
                directory.close(); result.incomplete("root_changed"); return result;
            }
            stack.push(new Frame(directory, null));
            while (!stack.isEmpty()) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) { result.incomplete("cancelled"); break; }
                long now = System.nanoTime();
                if ((now - start) / 1_000_000L >= budgetMs || result.visited >= maxEntries) {
                    result.incomplete("budget_reached"); break;
                }
                if (now - lastProgress >= 200_000_000L || (result.visited > 0 && result.visited % 128 == 0)) {
                    if (progress != null) progress.update(result.files, result.bytes);
                    lastProgress = now;
                    if (cancelled.get()) { result.incomplete("cancelled"); break; }
                }
                Frame frame = stack.peek();
                if (!frame.iterator.hasNext()) {
                    stack.pop(); frame.directory.close();
                    if (clean && frame.name != null && !stack.isEmpty()) {
                        try { stack.peek().directory.deleteDirectory(frame.name); result.directories++; }
                        catch (IOException error) { result.incomplete("directory_not_empty_or_changed"); }
                    }
                    continue;
                }
                Path name = frame.iterator.next().getFileName();
                result.visited++;
                try {
                    BasicFileAttributes stat = frame.directory.getFileAttributeView(name,
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                    if (stat.isSymbolicLink() || (!stat.isRegularFile() && !stat.isDirectory())) {
                        result.incomplete("special_file_protected"); continue;
                    }
                    if (stat.isDirectory()) {
                        if (stack.size() >= 128) { result.incomplete("depth_limit"); continue; }
                        SecureDirectoryStream<Path> child = frame.directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                        stack.push(new Frame(child, name));
                        if (!clean) result.directories++;
                    } else {
                        if (clean) frame.directory.deleteFile(name);
                        result.files++; result.bytes += Math.max(0L, stat.size());
                    }
                } catch (IOException | SecurityException error) { result.incomplete("unreadable_or_changed"); }
            }
        } catch (Exception error) { result.incomplete("unreadable_or_unsupported"); }
        finally {
            while (!stack.isEmpty()) { try { stack.pop().directory.close(); } catch (IOException ignored) {} }
        }
        if (progress != null) progress.update(result.files, result.bytes);
        return result;
    }
    /** Anchor at filesystem root and open every component with NOFOLLOW_LINKS. */
    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> openRoot(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (absolute.getNameCount() == 0) throw new IOException("Filesystem root cannot be a cache target");
        DirectoryStream<Path> base = Files.newDirectoryStream(absolute.getRoot());
        if (!(base instanceof SecureDirectoryStream)) { base.close(); throw new IOException("Secure traversal unavailable"); }
        SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) base;
        try {
            for (Path component : absolute) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                current.close(); current = next;
            }
            return current;
        } catch (Exception error) { current.close(); throw error; }
    }
}
