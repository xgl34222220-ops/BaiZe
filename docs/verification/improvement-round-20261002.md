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

## Android 16 acceptance finding (7f7007fc)

Paired signing/build run 36991598532 passed, and Workbench build/JVM/lint stage 36991598569 passed. Its real emulator Trash probe failed with EXDEV on Download → Android/data: these can be distinct FUSE/passthrough mounts. The original file was preserved. This candidate must not be delivered as accepted.

Local correction implemented and reviewed: keep ordinary payloads at a reserved hidden root on the same shared-storage mount, outside the original parent, match actual st_dev, preserve atomic-move-only semantics, and exclude the reserved root from App/native/Shell discovery and cleanup. Hidden shared storage is not private access control. Private restore metadata remains separate; uninstall/clear-data loses restore records. Parent rename/removal, cross-mount failure, symlink/unavailable journal retention and reserved-path regressions pass locally. The fresh Android 16 probe remains required. Renewed user authorization received; publish only to the existing test branch, then require a new emulator acceptance pass.

Latest local correction checks: 620 JVM/UI tests passed with zero failures/errors/skips; lintDebug passed; all three native engines compile under strict warning/error flags; modified Shell scripts pass sh and busybox ash syntax. Native and Shell index fixtures exclude both reserved roots and case variants. Full core remains 74 passed with the same four sudo-only fixture groups deferred to CI.


## 30016 navigation acceptance correction

Candidate 819a51e3 passed both full CI runs (36998228366 and 36998228330), including actual Android 16 ordinary Trash move/conflict restore and original-parent rename/removal, and signed APK installation. However, the user's subsequent question exposed an acceptance blind spot: the scheduler ledger dialog and rule bundle activity existed but were not reachable through the production Miuix navigation. The prior claim that all seven features were user-accessible was incorrect. Existing tests verified definitions/isolated pages, not all entry chains.

30017 also simplifies discovery: the existing home tool grid now puts Photo compression, Duplicates and Trash directly on the home screen, while rule/version and task-history tools stay under Settings.

30017 correction adds explicit Settings-home rows for task history and rule versions/preview, with shared routing for both UI styles. Ledger empty state remains visible. Acceptance now requires launcher/home-driven settings dialogs, rule preview and storage/duplicate/photo navigation, in addition to prior data-safety probes. No UI redesign or cleanup-policy expansion is part of this correction. Local checks and exact-commit CI must complete before replacement delivery.

30017 local evidence: full JVM/UI run wrote 623 tests with zero failures/errors/skips; after the review's repeat-tap guard correction, all 3 targeted launcher/settings tests passed again and lintDebug completed successfully. Scheduler-health and foreground architecture contracts passed. The new signed-emulator navigation script parses and synthetic helper checks pass; its real device execution is still required by CI, not counted as passed locally.
