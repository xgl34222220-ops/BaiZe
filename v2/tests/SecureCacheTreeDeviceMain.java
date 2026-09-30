import java.nio.file.*;
import java.nio.file.attribute.*;
import io.github.xgl34222220.baize.root.SecureCacheTree;
import java.util.concurrent.atomic.AtomicBoolean;
public class SecureCacheTreeDeviceMain {
    public static void main(String[] args) {
        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get("/"))) {
                System.out.println("root provider=" + stream.getClass() + ", secure=" + (stream instanceof SecureDirectoryStream));
            }
            Path root=Files.createTempDirectory("baize-diag-");
            Files.write(root.resolve("one"), new byte[12]);
            SecureCacheTree.Result r=SecureCacheTree.measure(root,new AtomicBoolean(),15000,100000,null);
            System.out.println("measured bytes="+r.bytes+" files="+r.files+" complete="+r.complete+" identity="+r.identity+" reason="+r.reason);
            Files.delete(root.resolve("one")); Files.delete(root);
            SecureCacheTreeTest.main(args);
        }
        catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
}
