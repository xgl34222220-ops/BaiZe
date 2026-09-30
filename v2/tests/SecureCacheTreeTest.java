import io.github.xgl34222220.baize.root.SecureCacheTree;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SecureCacheTreeTest {
    private static int checks;
    static void check(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
    static SecureCacheTree.Result measure(Path root) { return SecureCacheTree.measure(root, new AtomicBoolean(), 15000, 100000, null); }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("baize-secure-test-");
        try {
            Path root = Files.createDirectory(temp.resolve("cache"));
            Files.write(root.resolve("one"), new byte[12]);
            Files.createDirectories(root.resolve("nested"));
            Files.write(root.resolve("nested/two"), new byte[8]);
            SecureCacheTree.Result scan = measure(root);
            check(scan.complete && scan.bytes == 20 && scan.files == 2, "accurate measured cache");
            SecureCacheTree.Result stopped = SecureCacheTree.clear(root, scan.identity, new AtomicBoolean(true), null);
            check(!stopped.complete && stopped.files == 0 && Files.exists(root.resolve("one")), "cancel before mutation");
            SecureCacheTree.Result missing = SecureCacheTree.clear(root, "", new AtomicBoolean(), null);
            check(!missing.complete && Files.exists(root.resolve("one")), "missing identity fails closed");
            SecureCacheTree.Result clear = SecureCacheTree.clear(root, scan.identity, new AtomicBoolean(), null);
            check(clear.complete && clear.bytes == 20 && clear.files == 2 && clear.directories == 1 && Files.isDirectory(root), "contents only");
            Files.move(root, temp.resolve("old-cache")); Files.createDirectory(root);
            Files.write(root.resolve("new"), new byte[3]);
            check(!SecureCacheTree.clear(root, scan.identity, new AtomicBoolean(), null).complete && Files.exists(root.resolve("new")), "replaced root rejected");
            Path personal = Files.createDirectory(temp.resolve("personal"));
            Files.write(personal.resolve("keep"), new byte[99]);
            Files.createSymbolicLink(root.resolve("escape"), personal);
            scan = measure(root);
            check(!scan.complete && scan.bytes == 3, "symlink not traversed during scan");
            clear = SecureCacheTree.clear(root, scan.identity, new AtomicBoolean(), null);
            check(!clear.complete && Files.exists(personal.resolve("keep")), "symlink target preserved");
            Path alias = Files.createSymbolicLink(temp.resolve("alias"), personal);
            check(!measure(alias).complete, "root symlink rejected");
            Files.createDirectory(personal.resolve("nested"));
            check(!measure(alias.resolve("nested")).complete, "ancestor symlink rejected");
            Path many = Files.createDirectory(temp.resolve("many"));
            for (int i=0;i<10000;i++) Files.write(many.resolve("f"+i), new byte[1]);
            long start = System.nanoTime(); scan = measure(many);
            check(scan.complete && scan.files == 10000 && scan.bytes == 10000, "10k exact aggregate");
            System.out.println("10k scan: " + (System.nanoTime()-start)/1000000 + " ms");
            SecureCacheTree.Result limited = SecureCacheTree.measure(many, new AtomicBoolean(), 15000, 50, null);
            check(!limited.complete && limited.files <= 50 && limited.reason.equals("budget_reached"), "bounded traversal explicit partial");
            AtomicBoolean cancel = new AtomicBoolean();
            scan = SecureCacheTree.measure(many, cancel, 15000, 100000, (files,bytes)->cancel.set(true));
            check(!scan.complete && scan.files <= 128 && scan.reason.equals("cancelled"), "cancellation interrupts a large tree");
            check(Files.exists(personal.resolve("keep")), "tests never delete protected content");
            System.out.println("SecureCacheTree: " + checks + " assertions passed");
        } finally {
            try (java.util.stream.Stream<Path> paths=Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});
            }
        }
    }
}
