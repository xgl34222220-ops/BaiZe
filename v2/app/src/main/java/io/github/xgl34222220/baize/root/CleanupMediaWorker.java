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
    /**
     * Every file under /data/adb is relabeled by init's restorecon on each boot, so
     * file COUNT is a boot-time cost. Per-item progress therefore lives in one
     * append-only log per batch instead of one ack-/retry- file per deleted path,
     * and only the newest completed batches are kept as receipts.
     */
    static final String PROGRESS = "progress.log";
    static final int KEEP_COMPLETED = 32;
    /** Written by the one-time state migration once legacy history is compacted/archived. */
    static final String COMPACTED_MARKER = ".compacted-v1";
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
        Progress progress = Progress.load(batch);
        // Read the locked fd itself. Closing another fd for this inode could release
        // all process-owned POSIX locks, even while FileLock still says valid.
        channel.position(0);
        InputStream input = new BufferedInputStream(Channels.newInputStream(channel));
        long offset = 0;
        try (FileChannel log = FileChannel.open(batch.resolve(PROGRESS), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
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
                if (progress.acked.containsKey(start)) continue;
                long[] retryState = progress.retryState(start);
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
                    progress.ack(log, start, outcome);
                } else {
                    complete = false;
                    long attempts = Math.min(20, retryState[1] + 1);
                    long delay = Math.min(3600000, 5000L << Math.min(10, attempts));
                    progress.retry(log, start, System.currentTimeMillis() + delay, attempts, outcome);
                }
            }
        }
        // Fold the append log and any legacy per-item files into one compact log.
        // The compacted log is durable before a single legacy file is unlinked.
        progress.compact(batch);
        Files.setLastModifiedTime(batch, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        if (complete) {
            // Keep a bounded set of receipts without rescanning historical batches each round.
            Path completed = queue.resolve("completed");
            try {
                Files.createDirectory(completed);
                // A fresh history has no legacy receipts to migrate.
                writeAtomic(completed.resolve(COMPACTED_MARKER), "archived=0\n");
            }
            catch (FileAlreadyExistsException exists) {
                if (!Files.isDirectory(completed, NOFOLLOW)) throw exists;
            }
            Files.move(batch, completed.resolve("done-" + batch.getFileName().toString().substring(9)), StandardCopyOption.ATOMIC_MOVE);
            pruneCompleted(completed);
        }
    }
    /**
     * Keeps only the newest {@link #KEEP_COMPLETED} receipts. A receipt describes a
     * deletion whose MediaStore row is already verified absent; it holds no media.
     * Legacy history is left alone until the migration marker says it was archived.
     */
    static void pruneCompleted(Path completed) {
        if (!Files.isRegularFile(completed.resolve(COMPACTED_MARKER), NOFOLLOW)) return;
        List<Path> done = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(completed, "done-*")) {
            for (Path entry : entries) if (Files.isDirectory(entry, NOFOLLOW)) done.add(entry);
        } catch (IOException unreadable) { return; }
        if (done.size() <= KEEP_COMPLETED) return;
        done.sort(Comparator.comparingLong(CleanupMediaWorker::mtime).reversed());
        for (Path old : done.subList(KEEP_COMPLETED, done.size())) {
            try { deleteTree(old); } catch (IOException retryLater) { }
        }
    }
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
    /**
     * One-time migration for state written before {@link #PROGRESS}: folds each legacy
     * receipt's per-item files into its progress log, then bundles every receipt beyond
     * the newest {@link #KEEP_COMPLETED} into one ustar archive. Sources are removed only
     * after the archive is fsynced, renamed into place and re-read byte-for-byte.
     * Returns the number of receipts archived, or -1 when nothing was removed because
     * verification failed (the next boot retries).
     */
    public static int migrate(Path state) throws IOException {
        Path queue = state.resolve("cleanup-media"), completed = queue.resolve("completed");
        if (!Files.isDirectory(completed, NOFOLLOW)) return 0;
        if (Files.isRegularFile(completed.resolve(COMPACTED_MARKER), NOFOLLOW)) return 0;
        try (FileChannel channel = FileChannel.open(queue.resolve("consumer.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            List<Path> done = new ArrayList<>();
            Map<Path, Long> times = new HashMap<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(completed, "done-*")) {
                for (Path entry : entries) if (Files.isDirectory(entry, NOFOLLOW)) { done.add(entry); times.put(entry, mtime(entry)); }
            }
            for (Path batch : done) {
                Progress progress = Progress.load(batch);
                if (!progress.legacy.isEmpty()) progress.compact(batch);
                Files.setLastModifiedTime(batch, java.nio.file.attribute.FileTime.fromMillis(times.get(batch)));
            }
            done.sort(Comparator.comparingLong((Path p) -> times.get(p)).reversed());
            int archived = 0;
            if (done.size() > KEEP_COMPLETED) {
                List<Path> overflow = new ArrayList<>(done.subList(KEEP_COMPLETED, done.size()));
                Path archiveDir = queue.resolve("archive");
                Files.createDirectories(archiveDir);
                Path archive = archiveDir.resolve("completed-" + System.currentTimeMillis() + ".tar");
                Path temp = archiveDir.resolve(archive.getFileName() + ".tmp");
                try {
                    Tar.write(temp, overflow);
                    Files.move(temp, archive, StandardCopyOption.ATOMIC_MOVE);
                    if (!Tar.verify(archive, overflow)) { Files.deleteIfExists(archive); return -1; }
                } catch (IOException failed) {
                    Files.deleteIfExists(temp);
                    throw failed;
                }
                for (Path old : overflow) { deleteTree(old); archived++; }
            }
            writeAtomic(completed.resolve(COMPACTED_MARKER), "archived=" + archived + "\n");
            return archived;
        }
    }
    /** Minimal ustar writer/verifier; restorable with any tar (tar -xf FILE). */
    static final class Tar {
        static void write(Path archive, List<Path> dirs) throws IOException {
            try (FileChannel out = FileChannel.open(archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                for (Path dir : dirs) {
                    String base = dir.getFileName().toString();
                    out.write(ByteBuffer.wrap(header(base + "/", 0, mtime(dir), '5')));
                    for (Path file : files(dir)) {
                        long size = Files.size(file);
                        out.write(ByteBuffer.wrap(header(base + "/" + file.getFileName(), size, mtime(file), '0')));
                        long copied = 0;
                        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                            while (copied < size) {
                                long n = in.transferTo(copied, size - copied, out);
                                if (n <= 0) throw new IOException("SHORT_READ " + file);
                                copied += n;
                            }
                        }
                        int pad = (int) ((512 - size % 512) % 512);
                        if (pad > 0) out.write(ByteBuffer.wrap(new byte[pad]));
                    }
                }
                out.write(ByteBuffer.wrap(new byte[1024]));
                out.force(true);
            }
        }
        static boolean verify(Path archive, List<Path> dirs) throws IOException {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(archive))) {
                byte[] block = new byte[512];
                for (Path dir : dirs) {
                    String base = dir.getFileName().toString();
                    if (!readFully(in, block) || !name(block).equals(base + "/") || block[156] != '5') return false;
                    for (Path file : files(dir)) {
                        if (!readFully(in, block) || !name(block).equals(base + "/" + file.getFileName()) || block[156] != '0') return false;
                        long size = Long.parseLong(new String(block, 124, 11, StandardCharsets.US_ASCII).trim(), 8);
                        if (size != Files.size(file)) return false;
                        try (InputStream source = new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
                            for (long i = 0; i < size; i++) if (in.read() != source.read()) return false;
                            if (source.read() != -1) return false;
                        }
                        long pad = (512 - size % 512) % 512;
                        for (long i = 0; i < pad; i++) if (in.read() != 0) return false;
                    }
                }
                return readFully(in, block);
            }
        }
        private static List<Path> files(Path dir) throws IOException {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    if (Files.isDirectory(entry, NOFOLLOW)) throw new IOException("UNEXPECTED_DIRECTORY " + entry);
                    if (Files.isRegularFile(entry, NOFOLLOW)) files.add(entry);
                    else throw new IOException("UNEXPECTED_ENTRY " + entry);
                }
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return files;
        }
        private static boolean readFully(InputStream in, byte[] block) throws IOException {
            int off = 0;
            while (off < block.length) { int n = in.read(block, off, block.length - off); if (n < 0) return false; off += n; }
            return true;
        }
        private static String name(byte[] block) {
            int end = 0; while (end < 100 && block[end] != 0) end++;
            return new String(block, 0, end, StandardCharsets.UTF_8);
        }
        private static byte[] header(String name, long size, long mtimeMillis, char type) throws IOException {
            byte[] h = new byte[512];
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            if (n.length > 99) throw new IOException("NAME_TOO_LONG " + name);
            System.arraycopy(n, 0, h, 0, n.length);
            octal(h, 100, 8, type == '5' ? 0700 : 0600);
            octal(h, 108, 8, 0); octal(h, 116, 8, 0);
            octal(h, 124, 12, size);
            octal(h, 136, 12, Math.max(0, mtimeMillis / 1000));
            h[156] = (byte) type;
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
            h[263] = '0'; h[264] = '0';
            Arrays.fill(h, 148, 156, (byte) ' ');
            long sum = 0; for (byte b : h) sum += b & 0xff;
            String chk = String.format(Locale.ROOT, "%06o", sum);
            System.arraycopy(chk.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 6);
            h[154] = 0; h[155] = ' ';
            return h;
        }
        private static void octal(byte[] h, int off, int len, long value) {
            String text = String.format(Locale.ROOT, "%0" + (len - 1) + "o", value);
            System.arraycopy(text.getBytes(StandardCharsets.US_ASCII), 0, h, off, len - 1);
            h[off + len - 1] = 0;
        }
    }
    /** Per-batch item state: one append-only log, legacy ack-N / retry-N files still honoured. */
    static final class Progress {
        final Map<Long, String> acked = new TreeMap<>();
        final Map<Long, String[]> retries = new TreeMap<>();
        final List<Path> legacy = new ArrayList<>();
        static Progress load(Path batch) throws IOException {
            Progress p = new Progress();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(batch)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    boolean ack = name.startsWith("ack-"), retry = name.startsWith("retry-");
                    if (!ack && !retry) continue;
                    String number = name.substring(ack ? 4 : 6);
                    if (number.endsWith(".tmp")) { p.legacy.add(entry); continue; }
                    if (!number.matches("[0-9]{1,18}") || !Files.isRegularFile(entry, NOFOLLOW)) continue;
                    long offset = Long.parseLong(number);
                    List<String> lines;
                    try { lines = Files.readAllLines(entry, StandardCharsets.US_ASCII); }
                    catch (IOException unreadable) { continue; }
                    if (ack) p.acked.put(offset, lines.isEmpty() ? "VERIFIED_ABSENT" : clean(lines.get(0)));
                    else if (lines.size() >= 2 && lines.get(0).matches("[0-9]{1,19}") && lines.get(1).matches("[0-9]{1,9}"))
                        p.retries.put(offset, new String[] {lines.get(0), lines.get(1), lines.size() > 2 ? clean(lines.get(2)) : "UNKNOWN"});
                    p.legacy.add(entry);
                }
            }
            Path log = batch.resolve(PROGRESS);
            if (Files.isRegularFile(log, NOFOLLOW)) {
                // A torn final line from process death is ignored; that item is simply retried.
                for (String line : new String(Files.readAllBytes(log), StandardCharsets.US_ASCII).split("\n")) {
                    String[] f = line.split(" ");
                    if (f.length == 3 && f[0].equals("A") && f[1].matches("[0-9]{1,18}") && f[2].matches("[A-Z_]+"))
                        p.acked.put(Long.parseLong(f[1]), f[2]);
                    else if (f.length == 5 && f[0].equals("R") && f[1].matches("[0-9]{1,18}") && f[2].matches("[0-9]{1,19}") &&
                        f[3].matches("[0-9]{1,9}") && f[4].matches("[A-Z_]+"))
                        p.retries.put(Long.parseLong(f[1]), new String[] {f[2], f[3], f[4]});
                }
            }
            for (Long done : p.acked.keySet()) p.retries.remove(done);
            return p;
        }
        long[] retryState(long offset) {
            String[] r = retries.get(offset);
            if (r == null) return new long[] {0, 0};
            try { return new long[] {Long.parseLong(r[0]), Long.parseLong(r[1])}; }
            catch (NumberFormatException e) { return new long[] {0, 0}; }
        }
        void ack(FileChannel log, long offset, String outcome) throws IOException {
            outcome = clean(outcome);
            append(log, "A " + offset + " " + outcome + "\n");
            acked.put(offset, outcome); retries.remove(offset);
        }
        void retry(FileChannel log, long offset, long next, long attempts, String outcome) throws IOException {
            String[] r = {Long.toString(next), Long.toString(attempts), clean(outcome)};
            append(log, "R " + offset + " " + r[0] + " " + r[1] + " " + r[2] + "\n");
            retries.put(offset, r);
        }
        void compact(Path batch) throws IOException {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<Long, String> e : acked.entrySet()) text.append("A ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
            for (Map.Entry<Long, String[]> e : retries.entrySet()) {
                String[] r = e.getValue();
                text.append("R ").append(e.getKey()).append(' ').append(r[0]).append(' ').append(r[1]).append(' ').append(r[2]).append('\n');
            }
            writeAtomic(batch.resolve(PROGRESS), text.toString());
            for (Path old : legacy) Files.deleteIfExists(old);
            legacy.clear();
        }
        private static void append(FileChannel log, String line) throws IOException {
            ByteBuffer bytes = StandardCharsets.US_ASCII.encode(line);
            while (bytes.hasRemaining()) log.write(bytes);
            log.force(false);
        }
        private static String clean(String outcome) {
            String value = outcome == null ? "" : outcome.trim().replaceAll("[^A-Z_]", "_");
            return value.isEmpty() ? "UNKNOWN" : value;
        }
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
        if (args.length == 2 && args[0].equals("--migrate")) {
            // Background, late_start only: no 30 second round watchdog for the one-time pass.
            int archived = migrate(Paths.get(args[1]));
            System.out.println("cleanup-media migration archived=" + archived);
            if (archived < 0) System.exit(3);
            return;
        }
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
