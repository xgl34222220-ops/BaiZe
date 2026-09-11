# Module deep scan and cleanup performance contracts

These changes apply to `baize_engine_42_4.c`, `baize_deep_snapshot.c` and the module shell pipeline. They do not change the Kotlin workbench or ordinary native `scan-deep` invocations.

## One recursive scan

`deep-scan-manifest.sh` sets `BAIZE_DEEP_MANIFEST_ROOTS` only for its rule-expansion subprocess. The native engine still expands and sorts rules, applies risk overrides/ceilings and whitelist exclusions, rejects unsupported roots, and records each selected root's identity. It does not recursively collect provisional file statistics. Ordinary `scan-deep` still does.

The ephemeral NUL-delimited roots stream carries the original per-target budget and monotonic global deadline, followed by root metadata. The builder verifies that stream against the target list and current roots, then performs the authoritative file walk using directory-relative, no-follow operations. Ancestor symlinks, changed roots, oversized files, cross-device descendants, whitelist conflicts, unreadable/incomplete directories, stop requests, and deadlines remain checked. Directory identity and mutation times are checked again after enumeration; readdir errors invalidate publication.

Coverage suppression is deferred to this authoritative walk. An oversized or mounted parent is protected without suppressing an explicitly selected low/medium-risk descendant; high/critical descendants retain the standalone scanner's protected-parent suppression. Accepted targets produce post-order directory records. Target timeout discards that target's temporary records and reports `timed_out_dirs` and `scan_complete=0`; the global deadline or a traversal error aborts publication. Blocking filesystem calls cannot be preempted by a millisecond deadline; expiration is checked when they return. Existing standalone scan budget behavior is unchanged.

The shell replaces provisional expansion totals with manifest totals, appends authoritative target report rows, and writes one final scan history entry. Rejected/timeout target counts are explicit. The original target list includes expanded roots that may subsequently be protected; only the file manifest authorizes cleanup.

## Batched, cumulative cursor journal

A newly scanned cursor starts as `0\n`. Cleanup upgrades it in place to an append-only, checksummed text journal bound to the kernel boot UUID and the manifest's device, inode, size, mtime and ctime. Its records are:

- `H`: manifest identity and kernel boot UUID.
- `B`: the highest record in an intended batch. This is **not** the processed cursor. The intent is flushed and fsynced before any operation in that batch.
- `R`: processed cursor and cumulative file/directory/byte/skip/error/uncertainty counters, written only after handling that record. Each outcome is flushed before the next record starts.
- `C`: end of a completed durability checkpoint for the current run's mutations. Modified parent directories are deduplicated and fsynced before this marker and the journal are fsynced.
- `U`: a sticky recovery warning. A later checkpoint cannot retroactively prove the durability of outcomes recovered from an interrupted batch.

The default batch is 128 records, with `--checkpoint-records 1..128` available for testing/comparison. There is no per-record temp-file write/fsync/rename. A stop file, SIGINT, SIGTERM, EOF or a handled error forces a final checkpoint attempt. I/O/flush/fsync/close failures are returned as code 71 rather than ignored. A failed outcome write stops further mutation. SIGKILL and loss of power cannot execute a final checkpoint.

Recovery validates contiguous complete outcomes and cumulative accounting, discards only a torn trailing append, and refuses corrupt complete records, mismatched manifests, a changed/unavailable kernel boot UUID, and legacy nonzero integer-only cursors. Resuming an already-started deep cleanup after device reboot requires a fresh scan, including after a previously sealed stop. Root-process restarts in the same boot remain resumable. This deliberately prevents outcome records from being promoted after power loss when filesystem names may have reappeared. The boot UUID is read from `/proc/sys/kernel/random/boot_id`; failure to verify it stops cleanup before mutation. Legacy nonzero cursors cannot supply trustworthy historical totals and also require a fresh scan. Already recorded outcomes are never reapplied, so recreated objects at those paths are untouched. Records without outcomes are retried against the original manifest metadata and no-follow parent handles; neither an intent nor an absent old file authorizes a replacement file.

The journal is O(records) on disk and scanned on resume, in addition to the existing manifest scan. It is not a compact random-access database. `processed` in the summary is invocation-local; `files`, `dirs`, `bytes`, `skipped`, `errors`, `uncertain_records` and `uncertain_bytes` are cumulative. `run_*` fields expose invocation deltas.

## Crash and power-loss limits

Filesystem unlink and accounting are not one atomic transaction. The implementation does **not** claim exactly-once physical deletion or exact free-space accounting across power loss:

- A normal process kill between outcome writes preserves all flushed outcomes in the kernel. Recovery retains their cumulative accounting without adding it twice.
- A kill after unlink but before its outcome leaves a durable intent but no proof of success. If that record is missing or changed on replay, it is skipped and counted in `uncertain_records`/`uncertain_bytes`, never silently credited as freed space. External deletion or modification in the same interrupted range is indistinguishable and can also be classified uncertain. These are bounds, not proven deletion counts.
- A power failure may persist an outcome before the corresponding directory mutation. The journal is therefore boot-bound and is rejected after a device restart, before replay or publication of new totals. Same-boot recovery reports outcomes after the last `C` as `recovered_unsealed_records`, with `recovery_requires_audit=1` retained across later resumes. These describe observed successful syscalls in the same kernel, not a claim of power-loss-atomic reporting. Completed checkpoints order parent-directory sync before the journal seal, subject to the filesystem/device honoring fsync.
- Double metadata checks and pinned no-follow parent handles retain the existing conservative authorization model. They do not eliminate the final concurrent name-replacement race between checking an entry and unlinkat. This is not a transactional cleaner for adversarial writers.

The shell treats the native cumulative journal as authoritative and ignores the former accumulator. History is replaced by snapshot ID with cumulative totals, not appended invocation deltas. A shell crash before writing history is repaired by resume. Snapshot removal happens only after latest/history writes succeed. Shell report/latest/history publication and final snapshot removal are still separate, non-fsynced shell operations; they are **not** a power-loss-atomic transaction. The per-invocation TSV is not the recovery ledger. Full transactional power-loss accounting would require a broader storage/cleanup protocol, not simply fewer fsync calls.

## Reproduce

From the repository root, with host gcc, Python 3 and sudo available:

```sh
export TMPDIR="$(mktemp -d)"
python3 v2/tests/test-deep-performance.py
python3 v2/tests/test-deep-recovery-bounds.py
bash v2/tests/test-deep-manifest.sh
```

The performance test uses an isolated `/data/media/deep-performance-*` tree and removes it on exit. Fault injection is compiled into a host-only LD_PRELOAD shim, not production code. It exercises SIGKILL before/after unlink outcomes, recreation, repeated resume, torn/corrupt/legacy cursors, stop and SIGTERM checkpoints, fsync errors, ancestor symlinks, simulated cross-device mounts, readdir errors, root replacement, oversized-parent/safe-child selection, budgets, and the actual scan/clean shell wrappers including history repair and stale accumulators.

`test-deep-recovery-bounds.py` additionally models a persisted outcome with a lost unlink across a changed boot UUID, retries the rejected journal twice, covers an intent without an outcome, unavailable boot identity and same-boot stop/resume, and compares full/one-pass manifests for all four descendant risk levels under an oversized parent. This is a host model, not physical power-cut testing.

The synthetic comparison uses 2,000 regular files plus one directory. It verifies byte-identical full-scan and expansion-only final manifests and first-pass recursive fstatat counts of 2,000 versus 0. Checkpoint batches of 1 versus 128 exercise the same new safety checks: a host run used 6,007 versus 53 fsync calls (including parent directory sync), taking approximately 1.03 seconds versus 0.073 seconds. Timings are environment-dependent and are not an Android benchmark. This comparison is batch size 1 versus 128, not an assertion that the legacy binary had the same I/O profile.
