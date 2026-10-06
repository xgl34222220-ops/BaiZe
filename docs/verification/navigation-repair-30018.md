# 30018 navigation and settings repair

Baseline: delivered 30017 commit 2c827bce, tree e2c1cba628bbf96873fd9ff887c5c679af8a9523. The user's navigation review exposed six failing independent UI assertions: three secondary-tool double-open routes, the Material detail dock, settings draft leakage into unrelated auto-save, and draft loss after state restoration/runtime refresh.

## Changes

- Secondary APK/storage trash and photo routes share the existing lifecycle navigation guard. Returning from a destination enables a deliberate next navigation; repeated taps before lifecycle changes do not create duplicate activities.
- Material and Miuix both hide the bottom dock inside settings details and restore it on Back.
- Task-setting edits remain a local, saved-instance-state draft. Runtime polling updates status without replacing the draft. Only Save submits it; Back discards unsaved edits. A failed service save leaves the previously authoritative global configuration in place. Clean-page immediate saves no longer publish their unconfirmed configuration first.
- The Clean page exposes “即时缓存” and accurately named “规则与保护” directly. The center no longer repeats deep cleaning, uninstalled remnants or the main scan button. Those tools retain their direct Clean-page entry.
- Settings has a “清理审计” entry. The old audit timeline, rule-quality review (including notes/status), effectiveness, review trends and read-only improvement drafts contain unique functions, so their code/data are retained. Their activity starts use the same guard; the draft-to-quality link replaces the draft instead of building another review chain. Root quarantine and ordinary file Trash remain separate.
- The orphan raw-log UI and unused legacy screens are not deleted in this repair. Existing crash/connection diagnostics remain reachable. Restoring the raw-log surface is not claimed as completed.

## Verification

The original six red assertions passed after the focused repairs. Two newly added landscape probes initially could not locate uncomposed LazyColumn items; their navigation now scrolls the list to compose each target before performing its original assertions. Permanent tests retain the behavior assertions and use CI-relative screenshot paths. Added explicit failed-save/draft-discard coverage.

Full local regression and exact-commit CI remain required before delivery. The signed emulator smoke now exercises secondary triple taps/one-step Back, both themes' detail dock, actual rotation with draft retention, Back discard and value-dialog Cancel, plus the restored audit/review route. Successful Root-backed settings persistence is not claimed by this Root-disconnected signed-APK navigation smoke; host tests separately verify submission/isolation behavior.

Local final evidence: 98 suites / 639 JVM and UI tests passed with zero failures/errors/skips; lintDebug passed. A shared-host SIGKILL interrupted the first full attempts, so the successful local run used the same test set with one worker and a fresh test process every 12 classes (temporary init script, no relaxed assertions). Independent read-only review rechecked the six fixes, final XML and Material portrait/landscape screenshots; no new blocking finding. The source-version staging, scheduler-health, settings-draft and foreground architecture checks remain required; final signed-device results will be attached to delivery rather than inferred from host tests.
