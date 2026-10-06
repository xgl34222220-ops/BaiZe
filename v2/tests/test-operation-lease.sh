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
    static String shell = "/bin/sh";
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void await(File marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(20);
        check(marker.exists(), "worker did not enter synthetic fixture");
    }
    static Process worker(File root, File state, String id, String mode) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(shell, new File(root, "module/task-worker.sh").getPath(), "rules-clean", "scheduler:interval", id, mode);
        builder.environment().put("BAIZE_STATE_DIR", state.getPath());
        builder.environment().put("BAIZE_SHELL_BIN", shell);
        return builder.redirectErrorStream(true).redirectOutput(new File(root, id + ".log")).start();
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) shell = args[1];
        File root = new File(args[0]), state = new File(root, "state");
        String script = new String(Files.readAllBytes(new File(root, "module/config/operation-lock.sh").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        if (args.length > 2 && args[2].equals("hold")) {
            OperationLease held = OperationLease.acquire(shell, script, state, false);
            check(held != null, "owner process could not acquire lease");
            System.out.println("OWNED"); System.out.flush();
            Thread.sleep(60_000);
            held.close();
            return;
        }
        OperationLease exclusive = OperationLease.acquire(shell, script, state, false);
        check(exclusive != null, "manual mutation should own the gate");
        check(OperationLease.acquire(shell, script, state, false) == null, "second service mutation must wait");
        check(OperationLease.acquire(shell, script, state, true) == null, "read must not enter foreground mutation");
        Process blocked = worker(root, state, "blocked", "wait");
        check(blocked.waitFor(5, TimeUnit.SECONDS) && blocked.exitValue() == 3, "scheduled worker must return busy");
        check(!new File(state, "synthetic-entered").exists(), "blocked worker must not enter destructive body");
        exclusive.close();
        try (OperationLease readerOne = OperationLease.acquire(shell, script, state, true);
             OperationLease readerTwo = OperationLease.acquire(shell, script, state, true)) {
            check(readerOne != null && readerTwo != null, "independent cache and rule scans may overlap");
            check(OperationLease.acquire(shell, script, state, false) == null, "mutation must wait for both scans");
        }
        Process detached = worker(root, state, "detached", "detach");
        check(detached.waitFor(5, TimeUnit.SECONDS) && detached.exitValue() == 0, "detached launcher should finish");
        await(new File(state, "synthetic-entered"));
        check(OperationLease.acquire(shell, script, state, false) == null, "runner must retain lock after launcher exit");
        Files.write(new File(state, "synthetic-release").toPath(), new byte[0]);
        OperationLease after = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (after == null && System.nanoTime() < deadline) {
            after = OperationLease.acquire(shell, script, state, false);
            if (after == null) Thread.sleep(20);
        }
        check(after != null, "completed runner must release gate"); after.close();
        check(new File(state, "operations.lock").isFile(), "persistent inode must never be unlinked");
        boolean failedClosed = false;
        try { OperationLease.acquire(shell, "exit 4", state, false); } catch (IOException expected) { failedClosed = true; }
        check(failedClosed, "missing or broken provider must fail closed");
        File broken = new File(root, "broken-providers"); broken.mkdir();
        for (String name : new String[]{"flock", "toybox", "busybox"}) {
            File provider = new File(broken, name);
            Files.writeString(provider.toPath(), "#!/bin/sh\necho 'flock: Bad file descriptor' >&2\nexit 1\n"); provider.setExecutable(true);
        }
        String brokenScript = "PATH='" + broken.getAbsolutePath().replace("'", "'\\''") + ":/usr/bin:/bin'\n" + script;
        boolean toolFailure = false;
        try { OperationLease.acquire(shell, brokenScript, state, false); }
        catch (IOException expected) { toolFailure = expected.getMessage().contains("Bad file descriptor"); }
        check(toolFailure, "bad descriptor must be a tool error, not false busy");
        Process owner = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
            "-cp", System.getProperty("java.class.path"), OperationLeaseTest.class.getName(), root.getPath(), shell, "hold").start();
        try {
            check("OWNED".equals(new BufferedReader(new InputStreamReader(owner.getInputStream())).readLine()), "child owner handshake");
            check(OperationLease.acquire(shell, script, state, false) == null, "live owner must keep lease");
            owner.destroyForcibly(); check(owner.waitFor(5, TimeUnit.SECONDS), "owner kill");
            OperationLease recovered = null;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (recovered == null && System.nanoTime() < deadline) {
                recovered = OperationLease.acquire(shell, script, state, false);
                if (recovered == null) Thread.sleep(20);
            }
            check(recovered != null, "owner SIGKILL must close pipe and release kernel lease"); recovered.close();
        } finally { if (owner.isAlive()) owner.destroyForcibly(); }
        System.out.println("operation lease (" + shell + "): exclusion, inherited FD, provider error and owner SIGKILL checks passed");
    }
}
JAVA
"$JAVAC" -d "$T/classes" "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/OperationLease.java" "$T/OperationLeaseTest.java"
"$JAVA" -cp "$T/classes" io.github.xgl34222220.baize.root.OperationLeaseTest "$T"
MKSH=${BAIZE_TEST_MKSH:-$(command -v mksh || true)}
if [ -n "$MKSH" ]; then
  mkdir -p "$T/mksh-case/state"
  cp -a "$T/module" "$T/mksh-case/module"
  "$JAVA" -cp "$T/classes" io.github.xgl34222220.baize.root.OperationLeaseTest "$T/mksh-case" "$MKSH"
  python3 - "$ROOT" "$T" "$MKSH" <<'PY'
from pathlib import Path
import shlex, subprocess, sys
root, temporary, shell = sys.argv[1:]
for name in ('task-worker.sh', 'supervisor.sh'):
    source = (Path(root) / 'v2/module/scripts' / name).read_text()
    function = source[source.index('recovery_lock() {'):source.index('release_recovery_lock()')]
    lock = shlex.quote(str(Path(temporary) / ('recovery-' + name)))
    code = f'LAUNCH_LOCK={lock}\nSUPERVISOR_LOCK={lock}\n' + function + '\nrecovery_lock\n'
    result = subprocess.run([shell, '-c', code], capture_output=True, text=True)
    assert result.returncode == 0, (name, result.stderr)
print('mksh: both stale-lock recovery guards inherit FD 9 correctly')
PY
fi
