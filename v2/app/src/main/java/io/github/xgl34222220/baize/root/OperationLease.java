package io.github.xgl34222220.baize.root;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns an actual flock lease; it is released by pipe EOF on RootService process death. */
final class OperationLease implements AutoCloseable {
    private final Process process;
    private OperationLease(Process process) { this.process = process; }

    static OperationLease acquire(String shell, String script, File stateDir, boolean shared) throws IOException {
        Process process = new ProcessBuilder(shell, "-c", script, "baize-operation-lease", "--hold",
                stateDir.getAbsolutePath(), shared ? "shared" : "exclusive").redirectErrorStream(true).start();
        AtomicReference<String> response = new AtomicReference<>();
        CountDownLatch read = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try { response.set(new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine()); }
            catch (IOException ignored) { }
            finally { read.countDown(); }
        }, "baize-operation-lease");
        reader.setDaemon(true);
        reader.start();
        boolean acquired = false;
        try {
            if (!read.await(5, TimeUnit.SECONDS)) throw new IOException("Task ownership handshake timed out");
            if ("READY".equals(response.get()) && process.isAlive()) {
                acquired = true;
                return new OperationLease(process);
            }
            if ("BUSY".equals(response.get())) return null;
            String detail = response.get();
            throw new IOException("无法建立任务互斥：" + (detail == null ? "互斥工具未返回结果" : detail));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Task ownership interrupted", error);
        } finally {
            if (!acquired) release(process);
        }
    }

    @Override public void close() { release(process); }

    private static void release(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
    }
}
