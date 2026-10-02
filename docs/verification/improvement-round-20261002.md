# BaiZe improvement round — 2026-10-02

Base: 722848f44e50381c674c3408718d40a0a503ea89. Seven improvements are implemented and verified sequentially. Only one final paired test package is to be delivered after the round; no release or main merge.

1. Implemented; focused StorageWorkbenchTest and StorageFileIdentityTest passed (2026-10-02): identity-based incremental hashing, shared duplicate keeper verification, completed partial results and removal of singleton groups.
2. Implemented; OrdinaryFileTrashTest (4 tests) and StorageWorkbenchUiTest passed: recoverable ordinary-file Trash, capacity and conflict-safe restoration.
3. Implemented; keeper policy/manual choice tests and storage UI regressions passed: explainable duplicate keeper preferences.
4. Implemented; Scheduler JVM/UI tests, transition-ledger conditions test, scheduler fairness/health/concurrency, and 10 native APK coverage tests (including 121 synthetic packages) passed: auto-clean run ledger, skip reasons, next execution and interruption regression coverage.
5. Implemented; directory/growth unit tests and storage UI regressions passed: directory drill-down and comparable growth snapshots.
6. Implemented; signed import/rollback/tamper/custom-preview tests, immutable bundle tests and native rule coverage regressions passed: locally imported independently versioned rule bundles, validation, rollback and custom-rule preview. No new hosting, accounts, credentials or network permissions.
7. Implemented; synthetic JPEG encode/preview/orientation/original-preservation and unsupported-format tests passed; new screen recreation/UI tests passed: opt-in image compression preview, preserved originals and explicit format/metadata limitations.

All file-operation tests use synthetic fixtures. No user files are to be deleted or compressed. Local JVM/shell tests and CI emulator evidence do not constitute OEM Root-device verification.

Step 2 uses same-volume atomic moves only, refuses unsupported/cross-volume moves, journals first, enforces a configurable 1/5/10 GiB limit and uses exclusive verified conflict-safe restoration. The 30-day deadline does not silently delete files. Permanent purge is explicit. Foreground APK/download/large/duplicate tools use it; Root high-risk quarantine remains separately managed. Uninstall loses app-owned Trash, disclosed before confirmation and on its screen.

Step 4 records bounded deduplicated schedule transitions and worker exit outcomes, exposes reasons and next schedule in the existing health UI, and fails closed when battery telemetry is unavailable. Root/OEM lock-screen, reboot and killed-service behavior still require device verification; shell simulations and the existing emulator CI cannot establish all vendor behavior.

## Integrated review and local validation

- Added interrupted/changed-payload Trash recovery, exclusive retry names and metadata-only cleanup for confirmed missing payloads. Directory fsync ordering now makes journal and restore destination durable before destructive transitions; unsupported volumes fail closed. A new emulator probe covers same-volume move and conflict-safe restoration.
- Custom preview-enabled rules are file-only in native execution, with comment/whitespace parsing mismatches rejected.
- Smart-clean APKs also use the same Trash path and are not counted as released bytes or deleted files.
- Photo export distinguishes an outstanding picker from an interrupted copy across recreation; the JPEG parser validates complete marker streams, including trailing/concatenated data.
- Full local Android run: 614 JVM/UI tests, zero failures/errors/skips. Local lintDebug and assembleDebug passed. The final directory/file durability and explanatory-text changes are being rechecked before delivery; exact-commit remote CI remains required.
- Equipped local core run: 74 groups passed; four host privileged /data fixture groups are blocked by unavailable sudo (deep-manifest, deep-performance, deep-recovery-bounds, rule-engine-followup). These remain required in GitHub CI; they are not counted as passes locally.
