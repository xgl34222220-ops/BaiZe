#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
command -v "$JAVAC" >/dev/null || { echo 'SKIP operation lease test: JDK required'; exit 0; }
mkdir -p "$T/module/config" "$T/state"
cp "$ROOT/config/operation-lock.sh" "$T/module/config/"
cp "$ROOT/v2/module/scripts/task-worker.sh" "$ROOT/v2/module/scripts/worker-runner.sh" "$T/module/"
cat >"$T/module/cleaner.sh" <<'SH'
#!/bin/sh
echo entered >"$BAIZE_STATE_DIR/synthetic-entered"
while [ ! -f "$BAIZE_STATE_DIR/synthetic-release" ]; do sleep 0.05; done
SH
chmod +x "$T/module/cleaner.sh"
cat >"$T/OperationLeaseTest.java" <<'JAVA'
package io.github.xgl34222220.baize.root;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
public class OperationLeaseTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void await(File marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(20);
        check(marker.exists(), "worker did not enter synthetic fixture");
    }
    static Process worker(File root, File state, String id, String mode) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", new File(root, "module/task-worker.sh").getPath(), "rules-clean", "scheduler:interval", id, mode);
        builder.environment().put("BAIZE_STATE_DIR", state.getPath());
        builder.environment().put("BAIZE_SHELL_BIN", "/bin/sh");
        return builder.redirectErrorStream(true).redirectOutput(new File(root, id + ".log")).start();
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]), state = new File(root, "state");
        String script = new String(Files.readAllBytes(new File(root, "module/config/operation-lock.sh").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        OperationLease exclusive = OperationLease.acquire("/bin/sh", script, state, false);
        check(exclusive != null, "manual mutation should own the gate");
        check(OperationLease.acquire("/bin/sh", script, state, false) == null, "second service mutation must wait");
        check(OperationLease.acquire("/bin/sh", script, state, true) == null, "read must not enter foreground mutation");
        Process blocked = worker(root, state, "blocked", "wait");
        check(blocked.waitFor(5, TimeUnit.SECONDS) && blocked.exitValue() == 3, "scheduled worker must return busy");
        check(!new File(state, "synthetic-entered").exists(), "blocked worker must not enter destructive body");
        exclusive.close();
        try (OperationLease readerOne = OperationLease.acquire("/bin/sh", script, state, true);
             OperationLease readerTwo = OperationLease.acquire("/bin/sh", script, state, true)) {
            check(readerOne != null && readerTwo != null, "independent cache and rule scans may overlap");
            check(OperationLease.acquire("/bin/sh", script, state, false) == null, "mutation must wait for both scans");
        }
        Process detached = worker(root, state, "detached", "detach");
        check(detached.waitFor(5, TimeUnit.SECONDS) && detached.exitValue() == 0, "detached launcher should finish");
        await(new File(state, "synthetic-entered"));
        check(OperationLease.acquire("/bin/sh", script, state, false) == null, "runner must retain lock after launcher exit");
        Files.write(new File(state, "synthetic-release").toPath(), new byte[0]);
        OperationLease after = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (after == null && System.nanoTime() < deadline) {
            after = OperationLease.acquire("/bin/sh", script, state, false);
            if (after == null) Thread.sleep(20);
        }
        check(after != null, "completed runner must release gate"); after.close();
        check(new File(state, "operations.lock").isFile(), "persistent inode must never be unlinked");
        boolean failedClosed = false;
        try { OperationLease.acquire("/bin/sh", "exit 4", state, false); } catch (IOException expected) { failedClosed = true; }
        check(failedClosed, "missing or broken provider must fail closed");
        System.out.println("operation lease: 12 App/service/scheduler/detached-runner exclusion checks passed");
    }
}
JAVA
"$JAVAC" -d "$T/classes" "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/OperationLease.java" "$T/OperationLeaseTest.java"
"$JAVA" -cp "$T/classes" io.github.xgl34222220.baize.root.OperationLeaseTest "$T"
