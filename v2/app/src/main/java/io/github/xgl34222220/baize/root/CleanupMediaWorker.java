package io.github.xgl34222220.baize.root;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Module-only deletion refresh queue. Never deletes a MediaStore row or scans a directory. */
public final class CleanupMediaWorker {
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final int MAX_ITEMS = 32, MAX_PATH_BYTES = 65536;
    private static final long ROUND_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final Path queue;
    private final Backend backend;
    private long deadline;
    private int attempted;
    public interface Backend {
        Presence presence(Target target) throws IOException;
        String refresh(Target target, long remainingMillis) throws Exception;
    }
    public enum Presence { ABSENT, PRESENT, SYMLINK, DENIED, UNKNOWN }
    public static final class Target {
        public final String path, root, user;
        Target(String path, String root, String user) { this.path = path; this.root = root; this.user = user; }
    }
    public CleanupMediaWorker(Path state, Backend backend) {
        this.queue = state.resolve("cleanup-media"); this.backend = backend;
    }
    /** Whole-string DOTALL matching preserves spaces, quotes and newlines. */
    public static Target target(String path, String taskUser) {
        if (!path.startsWith("/") || path.indexOf('\0') >= 0) throw new IllegalArgumentException("INVALID_PATH");
        for (String part : path.split("/", -1)) if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("INVALID_PATH");
        String[][] patterns = {
            {"/data/media/([0-9]+)(/.+)", "emulated"},
            {"/storage/emulated/([0-9]+)(/.+)", "emulated"},
            {"/mnt/runtime/(?:default|read|write|full)/emulated/([0-9]+)(/.+)", "emulated"},
            {"/mnt/(?:installer|androidwritable|pass_through)/([0-9]+)/emulated/\\1(/.+)", "emulated"},
            {"/mnt/user/([0-9]+)/primary(/.+)", "emulated"},
            {"/mnt/media_rw/([A-Za-z0-9-]+)(/.+)", "removable"},
            {"/storage/([A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(/.+)", "removable"}
        };
        for (String[] pattern : patterns) {
            Matcher m = Pattern.compile(pattern[0], Pattern.DOTALL).matcher(path);
            if (!m.matches()) continue;
            String user = pattern[1].equals("emulated") ? checkedUser(m.group(1)) : checkedUser(taskUser);
            String root = pattern[1].equals("emulated") ? "/storage/emulated/" + user : "/storage/" + m.group(1);
            return new Target(root + m.group(2), root, user);
        }
        Matcher alias = Pattern.compile("/(?:sdcard|storage/self/primary)(/.+)", Pattern.DOTALL).matcher(path);
        if (alias.matches()) {
            String user = checkedUser(taskUser), root = "/storage/emulated/" + user;
            return new Target(root + alias.group(1), root, user);
        }
        if (path.startsWith("/data/user/") || path.startsWith("/data/user_de/") ||
            path.startsWith("/data/data/") || path.startsWith("/data/local/tmp/")) return null;
        throw new IllegalArgumentException("UNSUPPORTED_PATH");
    }
    private static String checkedUser(String user) {
        if (user == null || !user.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("USER_UNCONFIRMED");
        return user;
    }
    public static List<String> scanArguments(Target t) {
        return Arrays.asList("/system/bin/content", "call", "--user", t.user,
            "--uri", "content://media", "--method", "scan_file", "--arg", t.path);
    }
    public static List<String> queryArguments(Target t) {
        return Arrays.asList("/system/bin/content", "query", "--user", t.user,
            "--uri", "content://media/external/file", "--projection", "_id", "--where", "_data='" + t.path.replace("'", "''") + "'");
    }
    public static boolean scanReply(int code, String text) {
        return code == 0 && !text.contains("Error") && !text.contains("Exception") &&
            text.startsWith("Result: Bundle[") && text.contains("android.intent.extra.STREAM=");
    }
    public static boolean noRows(int code, String text) { return code == 0 && text.trim().equals("No result found."); }

    public static Presence inspect(Target target) throws IOException {
        Path root = Paths.get(target.root);
        try {
            Map<Path, Object> ancestors = new LinkedHashMap<>();
            Path current = root.getRoot();
            for (Path part : root) {
                current = current.resolve(part);
                BasicFileAttributes a = Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW);
                if (a.isSymbolicLink()) return Presence.SYMLINK;
                if (!a.isDirectory() || a.fileKey() == null) return Presence.UNKNOWN;
                ancestors.put(current, a.fileKey());
            }
            // An unreadable/unmounted storage root must not mean all descendants are absent.
            try (DirectoryStream<Path> ignored = Files.newDirectoryStream(root)) { }
            current = root;
            for (Path part : root.relativize(Paths.get(target.path))) {
                current = current.resolve(part);
                try {
                    BasicFileAttributes a = Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW);
                    if (a.isSymbolicLink()) return Presence.SYMLINK;
                    if (!current.toString().equals(target.path)) {
                        if (!a.isDirectory() || a.fileKey() == null) return Presence.UNKNOWN;
                        ancestors.put(current, a.fileKey());
                    }
                } catch (NoSuchFileException missing) {
                    for (Map.Entry<Path, Object> entry : ancestors.entrySet()) {
                        BasicFileAttributes a = Files.readAttributes(entry.getKey(), BasicFileAttributes.class, NOFOLLOW);
                        if (a.isSymbolicLink()) return Presence.SYMLINK;
                        if (!a.isDirectory() || !entry.getValue().equals(a.fileKey())) return Presence.UNKNOWN;
                    }
                    try (DirectoryStream<Path> ignored = Files.newDirectoryStream(root)) { }
                    try { Files.readAttributes(current, BasicFileAttributes.class, NOFOLLOW); return Presence.UNKNOWN; }
                    catch (NoSuchFileException confirmed) { return Presence.ABSENT; }
                }
            }
            return Presence.PRESENT;
        } catch (AccessDeniedException denied) { return Presence.DENIED;
        } catch (NoSuchFileException missingRoot) { return Presence.UNKNOWN;
        } catch (FileSystemException unknown) { return Presence.UNKNOWN; }
    }
    public int runRound() throws IOException {
        if (!Files.isDirectory(queue, NOFOLLOW)) return 0;
        attempted = 0; deadline = System.nanoTime() + ROUND_NANOS;
        // Never unlink this inode. Hold it through claim, scan, exact query and ack.
        try (FileChannel channel = FileChannel.open(queue.resolve("consumer.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock = tryLock(channel);
            if (lock == null) return 0;
            try {
                List<Path> batches = new ArrayList<>();
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(queue)) {
                    for (Path entry : entries) {
                        if (System.nanoTime() >= deadline) break;
                        if (!Files.isDirectory(entry, NOFOLLOW)) continue;
                        String name = entry.getFileName().toString();
                        if (name.startsWith("pending-") || name.startsWith("inflight-") ||
                            (name.startsWith(".building-") && producerGone(entry))) batches.add(entry);
                    }
                }
                batches.sort(Comparator.comparingLong(CleanupMediaWorker::mtime));
                for (Path batch : batches) {
                    if (attempted >= MAX_ITEMS || System.nanoTime() >= deadline) break;
                    if (Files.exists(batch.resolve("importing"), NOFOLLOW) ||
                        !Files.isRegularFile(batch.resolve("paths.nul"), NOFOLLOW)) continue;
                    // Native/C and Java producers hold this same inode's fcntl write lock.
                    // A dead shell is not sufficient: its orphan writer may still be alive.
                    try (FileChannel stream = FileChannel.open(batch.resolve("paths.nul"), StandardOpenOption.READ,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        FileLock writer = tryLock(stream);
                        if (writer == null) continue;
                        try {
                            String name = batch.getFileName().toString();
                            if (!name.startsWith("inflight-")) {
                                String id = name.substring(name.startsWith("pending-") ? 8 : 10);
                                Path claimed = queue.resolve("inflight-" + id);
                                Files.move(batch, claimed, StandardCopyOption.ATOMIC_MOVE); batch = claimed;
                            }
                            consume(batch, stream);
                        } finally { writer.release(); }
                    } catch (IOException batchFailure) {
                        // Corrupt/inaccessible metadata must not starve other batches.
                        // Keep its original NUL and retry later; never acknowledge unknown work.
                        try {
                            writeAtomic(batch.resolve("blocked"), "BATCH_IO_UNCONFIRMED\n");
                            Files.setLastModifiedTime(batch, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                        } catch (IOException ignored) { }
                    }
                }
                return attempted;
            } finally { lock.release(); }
        }
    }
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); } catch (OverlappingFileLockException busy) { return null; }
    }
    private static long mtime(Path path) {
        try { return Files.getLastModifiedTime(path, NOFOLLOW).toMillis(); } catch (IOException e) { return Long.MAX_VALUE; }
    }
    private static boolean producerGone(Path batch) {
        try {
            List<String> owner = Files.readAllLines(batch.resolve("owner"), StandardCharsets.US_ASCII);
            if (owner.size() != 2 || !owner.get(0).matches("[0-9]+") || !owner.get(1).matches("[0-9]+")) return false;
            String stat;
            try { stat = new String(Files.readAllBytes(Paths.get("/proc", owner.get(0), "stat")), StandardCharsets.US_ASCII); }
            catch (NoSuchFileException gone) { return true; }
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return fields.length > 19 && !fields[19].equals(owner.get(1));
        } catch (Exception unknown) { return false; }
    }
    private void consume(Path batch, FileChannel channel) throws IOException {
        String user = new String(Files.readAllBytes(batch.resolve("user")), StandardCharsets.US_ASCII).trim();
        boolean complete = true;
        // Read the locked fd itself. Closing another fd for this inode could release
        // all process-owned POSIX locks, even while FileLock still says valid.
        channel.position(0);
        InputStream input = new BufferedInputStream(Channels.newInputStream(channel));
        long offset = 0;
        while (true) {
            if (attempted >= MAX_ITEMS || System.nanoTime() >= deadline) { complete = false; break; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int value; boolean overflow = false;
            while ((value = input.read()) > 0) {
                if (bytes.size() < MAX_PATH_BYTES) bytes.write(value); else overflow = true;
                if (System.nanoTime() >= deadline) break;
            }
            if (System.nanoTime() >= deadline) { complete = false; break; }
            if (value < 0 && bytes.size() == 0 && !overflow) break;
            long start = offset; offset += bytes.size() + 1L;
            if (overflow || value < 0 || bytes.size() == 0) {
                writeAtomic(batch.resolve("blocked"), "MALFORMED_NUL_STREAM\n"); complete = false; break;
            }
            Path ack = batch.resolve("ack-" + start), retry = batch.resolve("retry-" + start);
            if (Files.isRegularFile(ack, NOFOLLOW)) continue;
            long[] retryState = readRetry(retry);
            if (retryState[0] > System.currentTimeMillis()) { complete = false; continue; }
            attempted++;
            String outcome;
            try {
                String path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                Target t = target(path, user);
                if (t == null) outcome = "NOT_MEDIA";
                else {
                    Presence before = backend.presence(t);
                    outcome = before == Presence.ABSENT ? backend.refresh(t, Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))) : before.name();
                    if (outcome.equals("VERIFIED_ABSENT") && backend.presence(t) != Presence.ABSENT)
                        outcome = "CHANGED_DURING_REFRESH";
                }
            } catch (Exception error) {
                outcome = error instanceof IllegalArgumentException ? error.getMessage() : "IO_UNCONFIRMED";
            }
            if (outcome.equals("VERIFIED_ABSENT") || outcome.equals("NOT_MEDIA")) {
                writeAtomic(ack, outcome + "\n"); Files.deleteIfExists(retry);
            } else {
                complete = false;
                long attempts = Math.min(20, retryState[1] + 1);
                long delay = Math.min(3600000, 5000L << Math.min(10, attempts));
                writeAtomic(retry, (System.currentTimeMillis() + delay) + "\n" + attempts + "\n" +
                    outcome.replaceAll("[^A-Z_]", "_") + "\n");
            }
        }
        Files.setLastModifiedTime(batch, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        if (complete) {
            // Keep per-item evidence without rescanning historical batches each round.
            Path completed = queue.resolve("completed");
            try { Files.createDirectory(completed); }
            catch (FileAlreadyExistsException exists) {
                if (!Files.isDirectory(completed, NOFOLLOW)) throw exists;
            }
            Files.move(batch, completed.resolve("done-" + batch.getFileName().toString().substring(9)), StandardCopyOption.ATOMIC_MOVE);
        }
    }
    private static long[] readRetry(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
            return new long[] {Long.parseLong(lines.get(0)), Long.parseLong(lines.get(1))};
        } catch (Exception e) { return new long[] {0, 0}; }
    }
    private static void writeAtomic(Path file, String text) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.US_ASCII.encode(text);
            while (bytes.hasRemaining()) out.write(bytes);
            out.force(true);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // Atomic on process death; this is not a power-loss directory-fsync promise.
    }
    private static final class ContentBackend implements Backend {
        private final Path queue;
        ContentBackend(Path queue) { this.queue = queue; }
        public Presence presence(Target t) throws IOException { return inspect(t); }
        public String refresh(Target t, long remaining) throws Exception {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining);
            Result scan = command(scanArguments(t), Math.min(5000, remaining));
            if (!scanReply(scan.code, scan.text)) return "SCAN_UNCONFIRMED";
            if (inspect(t) != Presence.ABSENT) return "CHANGED_DURING_REFRESH";
            long budget = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
            if (budget <= 0) return "TIMEOUT";
            Result query = command(queryArguments(t), Math.min(5000, budget));
            return noRows(query.code, query.text) ? "VERIFIED_ABSENT" : "INDEX_UNCONFIRMED";
        }
        private Result command(List<String> args, long timeoutMillis) throws Exception {
            Path output = Files.createTempFile(queue, "command-", ".log");
            Process process = null;
            try {
                process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!process.waitFor(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS)) return new Result(-1, "TIMEOUT");
                if (Files.size(output) > 65536) return new Result(-1, "OUTPUT_LIMIT");
                return new Result(process.exitValue(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8).trim());
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
                Files.deleteIfExists(output);
            }
        }
    }
    private static final class Result {
        final int code; final String text;
        Result(int code, String text) { this.code = code; this.text = text; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected root state directory");
        Path state = Paths.get(args[0]);
        // Also bound a stalled FUSE/provider I/O call. Abrupt exit releases both
        // kernel locks; unfinished items have no ack and are retried next round.
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(30000); Runtime.getRuntime().halt(124); }
            catch (InterruptedException finished) { Thread.currentThread().interrupt(); }
        }, "cleanup-media-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        try { new CleanupMediaWorker(state, new ContentBackend(state.resolve("cleanup-media"))).runRound(); }
        finally { watchdog.interrupt(); }
    }
}
