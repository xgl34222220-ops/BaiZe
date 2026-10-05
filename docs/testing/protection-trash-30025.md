# Protection and ordinary-file trash follow-up (test build 30025)

This is a test-branch update. Display version stays 2.0.0, build code increases to 30025, and the existing signing certificate is retained. No main-branch merge, OTA update, formal release, or device configuration change is part of this work.

## User-visible changes

- APK results explain the actual matching file/ancestor path or Android/data application rule using the same matcher as the deletion guard. Internal trash protection has its own explanation and links to the trash screen.
- “Manage matching protection” opens the current rules for that file. Every exact removal is confirmed; other matching rules remain visible and continue to protect the file. Unknown/disconnected protection remains fail-closed. Returning to APK results invalidates the prior selection and requires a fresh scan.
- File trash has compact cards, count/capacity, collapsed safety/settings details, multi-select, batch restore, and clear-all with explicit irreversible confirmation of frozen paths/count/size.
- Batch operations re-check both the reviewed journal and payload before acting, do not add newly arrived entries, report each failure independently, and support stopping unstarted work. Finished operations are never described as rolled back.

## Verification

The branch-only `BaiZe Protection and Trash Validation` workflow runs the full Android unit/Compose suite, lint, and debug build. It collects screenshots from the UI tests and executes a nonexported debug probe on disposable AOSP API 26 and 36 emulators. The probe operates only on newly created application-cache fixtures and checks production batch logic with actual Android filesystem operations.

Relevant tests: `ApkProtectionDetailsTest`, `ApkProtectionManagementUiTest`, `OrdinaryFileTrashTest`, `TrashBatchTest`, `TrashBatchUiTest`, and the existing guard/whitelist tests. Device evidence includes late-arrival exclusion, stale journal rejection, content changes, restore conflicts, mixed outcomes, and stopping remaining entries.

The existing paired functional-test workflow independently runs shell/native regressions and builds the signed APK/module pair. Review CI results for the exact commit; this document is a test plan, not a claim that every check passed. AOSP emulators do not establish real HyperOS/ColorOS or actual user Root-service behavior. The screenshot does not reveal the affected file's full path; the update makes that rule diagnosable without blindly removing unrelated protection.
