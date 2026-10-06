package io.github.xgl34222220.baize.root;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Publish a complete immutable rules generation, retaining the last usable one on failure. */
final class RuleBundleStore {
    interface Source { byte[] read(String name) throws IOException; }
    interface Publisher { boolean rename(File source, File target); }

    static File install(File root, List<String> names, Source source) throws IOException {
        return install(root, names, source, File::renameTo);
    }

    static synchronized File install(File root, List<String> names, Source source, Publisher publisher) throws IOException {
        File previous = current(root, names);
        File staging = null;
        File pointer = null;
        try {
            Map<String, byte[]> bundle = new LinkedHashMap<>();
            MessageDigest digest = sha256();
            // Finish all reads before changing any on-disk rules or metadata.
            for (String name : names) {
                if (!name.matches("[a-zA-Z0-9._-]+")) throw new IOException("Invalid rules filename");
                byte[] bytes = source.read(name);
                if (bytes == null) throw new IOException("Missing rules asset: " + name);
                bundle.put(name, bytes);
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
            }
            StringBuilder fingerprint = new StringBuilder();
            for (byte value : digest.digest()) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            String generationName = "bundle-" + fingerprint;
            File generation = new File(root, generationName);
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create rules store");
            if (!matches(generation, bundle)) {
                // Never overwrite a generation already held by a scanner.
                if (generation.exists()) throw new IOException("Rules generation is incomplete");
                staging = new File(root, ".staging-" + UUID.randomUUID());
                if (!staging.mkdir()) throw new IOException("Cannot stage rules");
                for (Map.Entry<String, byte[]> entry : bundle.entrySet()) write(new File(staging, entry.getKey()), entry.getValue());
                if (!publisher.rename(staging, generation)) throw new IOException("Cannot publish rules generation");
                staging = null;
            }
            pointer = new File(root, ".current-" + UUID.randomUUID());
            write(pointer, generationName.getBytes(StandardCharsets.UTF_8));
            if (!publisher.rename(pointer, new File(root, "current"))) throw new IOException("Cannot activate rules generation");
            pointer = null;
            return generation;
        } catch (IOException | RuntimeException error) {
            if (previous != null) return previous;
            throw new IOException("No complete rules bundle is available", error);
        } finally {
            if (staging != null) {
                File[] files = staging.listFiles();
                if (files != null) for (File file : files) file.delete();
                staging.delete();
            }
            if (pointer != null) pointer.delete();
        }
    }

    private static File current(File root, List<String> names) {
        try {
            String name = new String(Files.readAllBytes(new File(root, "current").toPath()), StandardCharsets.UTF_8);
            if (name.matches("bundle-[0-9a-f]{64}")) {
                File directory = new File(root, name);
                if (complete(directory, names)) return directory;
            }
        } catch (IOException ignored) { }
        // Upgrade from the former flat store without rewriting or deleting it.
        return complete(root, names) ? root : null;
    }

    private static boolean complete(File directory, List<String> names) {
        if (!directory.isDirectory()) return false;
        for (String name : names) if (!new File(directory, name).isFile()) return false;
        return true;
    }

    private static boolean matches(File directory, Map<String, byte[]> bundle) throws IOException {
        if (!directory.isDirectory()) return false;
        for (Map.Entry<String, byte[]> entry : bundle.entrySet()) {
            File file = new File(directory, entry.getKey());
            if (!file.isFile() || !java.util.Arrays.equals(Files.readAllBytes(file.toPath()), entry.getValue())) return false;
        }
        return true;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
