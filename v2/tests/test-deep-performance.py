#!/usr/bin/env python3
"""Host-only deep pipeline/replay checks. Requires gcc, Python 3, and sudo for /data/media fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(tempfile.mkdtemp(prefix="deep-performance-", dir=os.environ.get("TMPDIR", "/tmp")))
DATA = Path("/data/media") / WORK.name

SHIM = r'''
#define _GNU_SOURCE
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static unsigned mutations, syncs, stats;
int fsync(int fd) {
    int (*real)(int) = dlsym(RTLD_NEXT, "fsync");
    ++syncs;
    const char *n = getenv("FAIL_SYNC");
    if (n && syncs == strtoul(n, NULL, 10)) { errno = EIO; return -1; }
    return real(fd);
}
int fstatat(int fd, const char *path, struct stat *st, int flags) {
    int (*real)(int, const char *, struct stat *, int) = dlsym(RTLD_NEXT, "fstatat");
    ++stats;
    const char *delay = getenv("STAT_DELAY_US");
    if (delay) usleep(strtoul(delay, NULL, 10));
    int result = real(fd, path, st, flags);
    if (result == 0 && getenv("FAKE_MOUNT") && !strcmp(path, "mounted")) ++st->st_dev;
    return result;
}
struct dirent *readdir(DIR *dir) {
    struct dirent *(*real)(DIR *) = dlsym(RTLD_NEXT, "readdir");
    if (getenv("FAIL_READDIR")) { errno = EIO; return NULL; }
    return real(dir);
}
int unlinkat(int fd, const char *path, int flags) {
    int (*real)(int, const char *, int) = dlsym(RTLD_NEXT, "unlinkat");
    const char *before = getenv("KILL_BEFORE");
    if (before && mutations + 1 == strtoul(before, NULL, 10)) kill(getpid(), SIGKILL);
    int result = real(fd, path, flags);
    if (result == 0) {
        ++mutations;
        const char *after = getenv("KILL_AFTER");
        if (after && mutations == strtoul(after, NULL, 10)) {
            const char *victim = getenv("VICTIM");
            if (victim) { FILE *f = fopen(victim, "w"); if (f) { fprintf(f, "%s", path); fclose(f); } }
            kill(getpid(), SIGKILL);
        }
        const char *term = getenv("TERM_AFTER");
        if (term && mutations == strtoul(term, NULL, 10)) raise(SIGTERM);
        const char *stop = getenv("STOP_AFTER");
        if (stop && mutations == strtoul(stop, NULL, 10)) {
            int s = open(getenv("STOP_PATH"), O_CREAT | O_WRONLY, 0600);
            if (s >= 0) close(s);
        }
    }
    return result;
}
__attribute__((destructor)) static void finish(void) {
    const char *path = getenv("COUNTS");
    if (path) { FILE *f = fopen(path, "w"); if (f) {
        fprintf(f, "fsync=%u\nfstatat=%u\nmutations=%u\n", syncs, stats, mutations); fclose(f);
    } }
}
'''


def run(args, code=0, **env):
    result = subprocess.run([str(a) for a in args], env={**os.environ, **env}, capture_output=True, text=True)
    assert result.returncode == code, (args, result.returncode, result.stdout, result.stderr)
    return result


def values(path):
    return dict(line.split("=", 1) for line in Path(path).read_text().splitlines() if "=" in line)


def fixture(name, files=40):
    state = WORK / name
    state.mkdir()
    target = DATA / name / "cache"
    target.mkdir(parents=True)
    for i in range(files):
        (target / f"file-{i:05d}").write_bytes(b"payload")
    (state / "targets").write_text(f"{target}\tlow\n")
    (state / "white").write_text("")
    run([BIN, "build", "--targets", state / "targets", "--manifest", state / "manifest", "--summary", state / "build"])
    (state / "cursor").write_text("0\n")
    return state, target


def clean(state, code=0, batch=128, **env):
    return run([BIN, "clean", "--manifest", state / "manifest", "--cursor", state / "cursor",
                "--report", state / "report", "--summary", state / "summary", "--whitelist", state / "white",
                "--stop", state / "stop", "--checkpoint-records", batch], code=code, **env)


try:
    run(["sudo", "mkdir", "-p", DATA])
    run(["sudo", "chown", f"{os.getuid()}:{os.getgid()}", DATA])
    BIN = WORK / "deep"
    ENGINE = WORK / "engine"
    SHARED = WORK / "faults.so"
    (WORK / "faults.c").write_text(SHIM)
    run(["gcc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", ROOT / "native/baize_deep_snapshot.c", "-o", BIN])
    run(["gcc", "-std=c11", "-O2", ROOT / "native/baize_engine_42_4.c", "-o", ENGINE])
    run(["gcc", "-shared", "-fPIC", "-O2", WORK / "faults.c", "-ldl", "-o", SHARED])
    preload = {"LD_PRELOAD": str(SHARED)}

    # Kill between outcomes: replay recovers all recorded deletions, without crediting them twice.
    s, target = fixture("kill-between")
    clean(s, code=-9, KILL_BEFORE="10", **preload)
    recorded_path = Path(os.fsdecode((s / "manifest").read_bytes().split(b"\0")[10]))
    recorded_path.write_bytes(b"recreated-after-recorded-success")
    clean(s)
    assert recorded_path.read_bytes() == b"recreated-after-recorded-success"
    v = values(s / "summary")
    assert (v["files"], v["bytes"], v["uncertain_records"]) == ("40", "280", "0"), v
    clean(s)
    v = values(s / "summary")
    assert v["files"] == "40" and v["run_files"] == "0" and v["processed"] == "0", v
    assert v["recovery_requires_audit"] == "1", v

    # Kill in the unlink/outcome gap: missing old data is explicitly uncertain, not silently lost or credited.
    s, target = fixture("kill-gap")
    clean(s, code=-9, KILL_AFTER="10", **preload)
    clean(s)
    v = values(s / "summary")
    assert (v["files"], v["bytes"], v["uncertain_records"], v["uncertain_bytes"]) == ("39", "273", "1", "7"), v
    clean(s)
    assert values(s / "summary")["uncertain_records"] == "1"

    # An object recreated in the gap is not authorized by either intent or the old manifest metadata.
    s, target = fixture("kill-recreate")
    clean(s, code=-9, KILL_AFTER="10", VICTIM=str(s / "victim"), **preload)
    victim = target / (s / "victim").read_text()
    victim.write_bytes(b"new-recreated-payload")
    clean(s)
    assert victim.read_bytes() == b"new-recreated-payload"
    v = values(s / "summary")
    assert v["files"] == "39" and v["uncertain_records"] == "1", v

    # Force a final checkpoint on a mid-batch stop; resume cannot add previous totals again.
    s, target = fixture("stop")
    clean(s, code=9, STOP_AFTER="7", STOP_PATH=str(s / "stop"), **preload)
    assert values(s / "summary")["files"] == "7"
    assert (s / "cursor").read_text().splitlines()[-1].startswith("C 7\t")
    (s / "stop").unlink()
    clean(s)
    v = values(s / "summary")
    assert v["files"] == "40" and v["run_files"] == "33", v

    s, target = fixture("signal-stop")
    clean(s, code=9, TERM_AFTER="7", **preload)
    assert (s / "cursor").read_text().splitlines()[-1].startswith("C 7\t")
    clean(s)
    assert values(s / "summary")["files"] == "40"

    # A torn final append is discarded, but complete corrupt records and foreign/legacy cursors fail closed.
    s, target = fixture("torn")
    clean(s, code=-9, KILL_BEFORE="10", **preload)
    with (s / "cursor").open("ab") as f:
        f.write(b"R 10 10 0")
    clean(s)
    assert values(s / "summary")["files"] == "40"
    s, target = fixture("corrupt")
    with (s / "cursor").open("ab") as f:
        f.write(b"H bogus\t1\n")
    clean(s, code=71)
    assert len(list(target.iterdir())) == 40
    (s / "cursor").write_text("9\n")
    clean(s, code=71)
    assert len(list(target.iterdir())) == 40

    # Durability errors before intent and after deletion are surfaced, with recoverable outcomes.
    s, target = fixture("sync-before")
    clean(s, code=71, FAIL_SYNC="3", **preload)
    assert len(list(target.iterdir())) == 40
    s, target = fixture("sync-after")
    clean(s, code=71, FAIL_SYNC="4", **preload)
    assert values(s / "summary")["files"] == "40"
    clean(s)
    assert values(s / "summary")["files"] == "40"

    # Following a replaced ancestor symlink must not expose even unchanged hard-linked data.
    s, target = fixture("ancestor")
    moved = target.with_name("moved")
    target.rename(moved)
    target.symlink_to(moved, target_is_directory=True)
    clean(s)
    assert len(list(moved.iterdir())) == 40
    assert values(s / "summary")["files"] == "0"

    # Cross-device subtrees and readdir errors never turn into partial authorized manifests.
    s, target = fixture("mount", 2)
    (target / "mounted").mkdir()
    run([BIN, "build", "--targets", s / "targets", "--manifest", s / "mount.manifest",
         "--summary", s / "mount.build"], code=8, FAKE_MOUNT="1", **preload)
    assert not (s / "mount.manifest").exists()
    run([BIN, "build", "--targets", s / "targets", "--manifest", s / "read-error.manifest",
         "--summary", s / "read-error.build"], code=71, FAIL_READDIR="1", **preload)
    assert not (s / "read-error.manifest").exists()

    # Equivalent rules/manifest, with exactly one recursive stat pass instead of two.
    s, target = fixture("scan", 2000)
    (s / "rules").write_text(f"{target}|low\n{target}/file-00000|low\n")
    def scan(prefix, expand=False, **extra):
        env = dict(preload, COUNTS=str(s / f"{prefix}.counts"), **extra)
        if expand:
            env["BAIZE_DEEP_MANIFEST_ROOTS"] = str(s / f"{prefix}.roots")
        run([ENGINE, "scan-deep", "--rules", s / "rules", "--targets", s / f"{prefix}.targets",
             "--summary", s / f"{prefix}.summary", "--report", s / f"{prefix}.report",
             "--dir-budget-ms", "0", "--global-budget-ms", "0"], **env)
    scan("full")
    scan("expand", True)
    run([BIN, "build", "--targets", s / "full.targets", "--manifest", s / "full.manifest", "--summary", s / "full.build"])
    run([BIN, "build", "--targets", s / "expand.targets", "--roots", s / "expand.roots",
         "--manifest", s / "expand.manifest", "--summary", s / "expand.build"])
    assert (s / "full.manifest").read_bytes() == (s / "expand.manifest").read_bytes()
    assert values(s / "full.counts")["fstatat"] == "2000"
    assert values(s / "expand.counts")["fstatat"] == "0"
    print("scan: first-pass recursive fstatat 2000 -> 0; byte-identical 2001-record final manifests")

    # Expanded root replacement aborts publication; the old root's authorization cannot transfer.
    target.rename(target.with_name("old-cache"))
    target.mkdir()
    (target / "replacement").write_text("keep")
    run([BIN, "build", "--targets", s / "expand.targets", "--roots", s / "expand.roots",
         "--manifest", s / "replaced.manifest", "--summary", s / "replaced.build"], code=7)
    assert not (s / "replaced.manifest").exists()

    # Oversized parent protection still permits an explicitly selected safe descendant.
    s, target = fixture("oversized", 2)
    (target / "huge").write_bytes(b"x" * 100)
    (s / "rules").write_text(f"{target}|low\n{target}/file-00000|low\n")
    scan("safety", True)
    run([BIN, "build", "--targets", s / "safety.targets", "--roots", s / "safety.roots",
         "--manifest", s / "safe.manifest", "--summary", s / "safe.build", "--max-file-bytes", "10"])
    v = values(s / "safe.build")
    assert v["files"] == "1" and v["protected_targets"] == "1", v

    # The recursive pass, not just expansion, still enforces per-directory and global deadlines.
    roots = s / "safety.roots"
    fields = roots.read_bytes().split(b"\0")
    fields[0] = b"1"
    roots.write_bytes(b"\0".join(fields))
    run([BIN, "build", "--targets", s / "safety.targets", "--roots", roots,
         "--manifest", s / "timeout.manifest", "--summary", s / "timeout.build"],
        STAT_DELAY_US="5000", **preload)
    v = values(s / "timeout.build")
    assert int(v["timed_out_dirs"]) > 0 and v["scan_complete"] == "0", v
    fields[0], fields[1] = b"0", b"1"
    roots.write_bytes(b"\0".join(fields))
    run([BIN, "build", "--targets", s / "safety.targets", "--roots", roots,
         "--manifest", s / "global.manifest", "--summary", s / "global.build"], code=124)
    assert not (s / "global.manifest").exists()

    # Exercise the real scan/clean shell pipeline, including stale accumulators and a shell-visible crash.
    s, target = fixture("wrapper", 40)
    module = WORK / "module"
    module.mkdir()
    for script in ("deep-scan-manifest.sh", "deep-manifest-clean.sh"):
        shutil.copy2(ROOT / "module" / script, module / script)
    scanner = module / "native-cleaner.sh"
    scanner.write_text((ROOT / "module/native-scan.sh").read_text().replace("#!/system/bin/sh", "#!/bin/bash", 1))
    scanner.chmod(0o755)
    (s / "config.conf").write_text("deep_dir_timeout_seconds=8\ndeep_stage_limit_seconds=180\n")
    (s / "whitelist.conf").write_text("")
    (s / "rules").write_text(f"{target}|low\n")
    (s / "builtin-risk").write_text("")
    environment = dict(BAIZE_STATE_DIR=str(s), BAIZE_DEEP_RULES=str(s / "rules"),
                       BAIZE_BUILTIN_RISK_OVERRIDES=str(s / "builtin-risk"),
                       BAIZE_NATIVE_ENGINE=str(ENGINE), BAIZE_DEEP_SNAPSHOT_ENGINE=str(BIN))
    run(["bash", module / "deep-scan-manifest.sh", "deep-scan", "manual"], **environment)
    for path in (s / "deep_scan.env", s / "latest.env"):
        v = values(path)
        assert v["files"] == "40" and v["bytes"] == "280" and v["scan_complete"] == "1", v
    assert "candidate\\tlow\\t".replace("\\t", "\t") in (s / "reports/latest.tsv").read_text()
    run(["bash", module / "deep-manifest-clean.sh", "deep-clean", "manual"], code=9,
        STOP_AFTER="7", STOP_PATH=str(s / "stop"), **environment, **preload)
    assert values(s / "latest.env")["files"] == "7"
    snapshot_id = values(s / "deep_scan.env")["snapshot_id"]
    (s / "deep_clean.accum.env").write_text(f"snapshot_id={snapshot_id}\nfiles=999\nbytes=999\n")
    run(["bash", module / "deep-manifest-clean.sh", "deep-clean", "manual"], code=137,
        KILL_BEFORE="10", **environment, **preload)
    run(["bash", module / "deep-manifest-clean.sh", "deep-clean", "manual"], **environment)
    v = values(s / "latest.env")
    assert v["files"] == "40" and v["bytes"] == "280", v
    rows = [line.split("\t") for line in (s / "history.tsv").read_text().splitlines()]
    cleaned = [row for row in rows if row[1] == "deep-clean"]
    assert len(cleaned) == 1 and cleaned[0][2:4] == ["280", "40"], cleaned
    assert len([row for row in rows if row[1] == "deep-scan"]) == 1
    assert not (s / "deep_scan.env").exists()

    # Reproducible synthetic checkpoint comparison, including directory fsyncs, same safety checks.
    measurements = []
    for batch in (1, 128):
        s, target = fixture(f"bench-{batch}", 2000)
        started = time.monotonic()
        clean(s, batch=batch, COUNTS=str(s / "counts"), **preload)
        elapsed = time.monotonic() - started
        count = int(values(s / "counts")["fsync"])
        assert values(s / "summary")["files"] == "2000"
        measurements.append((batch, elapsed, count))
    assert measurements[1][2] * 20 < measurements[0][2], measurements
    print("clean checkpoint comparison (2000 files + directory):", measurements)
    print("deep performance, SIGKILL replay, uncertainty, identity, budgets and durability failures: ok")
finally:
    run(["sudo", "rm", "-rf", DATA])
    shutil.rmtree(WORK)
