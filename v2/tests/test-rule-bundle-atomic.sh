#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
command -v "$JAVAC" >/dev/null || { echo 'SKIP rules bundle test: JDK required'; exit 0; }
cat >"$T/RuleBundleStoreTest.java" <<'JAVA'
package io.github.xgl34222220.baize.root;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class RuleBundleStoreTest {
    static final List<String> NAMES = Arrays.asList("app.rules", "hidden.rules", "deep.rules", "rules.meta.env");
    static byte[] bytes(String version, String name) { return (version + ":" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void intact(File directory, String version) throws Exception {
        for (String name : NAMES) check(Arrays.equals(Files.readAllBytes(new File(directory, name).toPath()), bytes(version, name)), name);
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "rules");
        File old = RuleBundleStore.install(root, NAMES, name -> bytes("old", name));
        intact(old, "old");
        File readFailure = RuleBundleStore.install(root, NAMES, name -> {
            if (name.equals("hidden.rules")) throw new IOException("synthetic read failure");
            return bytes("new", name);
        });
        check(old.equals(readFailure), "failed read must retain previous complete generation");
        intact(old, "old");
        File renameFailure = RuleBundleStore.install(root, NAMES, name -> bytes("new", name), (a, b) -> false);
        check(old.equals(renameFailure), "failed stage publication must retain old rules");
        File pointerFailure = RuleBundleStore.install(root, NAMES, name -> bytes("new", name), (a, b) -> !b.getName().equals("current") && a.renameTo(b));
        check(old.equals(pointerFailure), "failed activation must retain old rules");
        File updated = RuleBundleStore.install(root, NAMES, name -> bytes("new", name));
        check(!old.equals(updated), "upgrade must publish a new immutable generation");
        intact(updated, "new");
        intact(old, "old");
        File restarted = RuleBundleStore.install(root, NAMES, name -> { throw new IOException("unavailable after restart"); });
        check(updated.equals(restarted), "restart must recover last complete generation");
        File legacy = new File(args[0], "legacy"); legacy.mkdirs();
        for (String name : NAMES) Files.write(new File(legacy, name).toPath(), bytes("legacy", name));
        check(legacy.equals(RuleBundleStore.install(legacy, NAMES, name -> { throw new IOException("upgrade read failure"); })), "legacy fallback");
        File migrated = RuleBundleStore.install(legacy, NAMES, name -> bytes("new", name));
        intact(migrated, "new"); intact(legacy, "legacy");
        boolean failed = false;
        try { RuleBundleStore.install(new File(args[0], "empty"), NAMES, name -> { throw new IOException("missing"); }); }
        catch (IOException expected) { failed = true; }
        check(failed, "fresh installation must fail closed when no complete bundle exists");
        check(Arrays.stream(root.list()).noneMatch(name -> name.startsWith(".staging-") || name.startsWith(".current-")), "temporary bundle cleanup");
        System.out.println("rules bundle: 9 atomic publication, rollback, restart and upgrade checks passed");
    }
}
JAVA
"$JAVAC" -d "$T/classes" "$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/RuleBundleStore.java" "$T/RuleBundleStoreTest.java"
"$JAVA" -cp "$T/classes" io.github.xgl34222220.baize.root.RuleBundleStoreTest "$T/state"
