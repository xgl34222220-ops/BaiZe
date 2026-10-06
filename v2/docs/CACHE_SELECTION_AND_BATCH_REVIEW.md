# Cache selection and consecutive review batches

The 30007 workbench scanned with the App cache engine, which stores its snapshot under
`app-foreground`. Its cleanup preparation still called `CacheSelectionRepository`, which reads
the module's separate `cache_scan` files and requires the module's `nul-v2` manifest. A current
App scan could therefore display normally and fail with `snapshot_missing` before deletion.

The workbench now sends the original App snapshot ID and explicit candidate paths directly to
`IBaiZeRootService.cleanSelected`. That service checks the ID under its operation lock, rejects
unknown paths and non-Boolean selections, and cleans only the selected server-owned candidates.
Existing explicit `__all_safe__` callers remain supported. Unselected roots remain in the App
snapshot; selected paths never come from a new filesystem search.
Discovery measures the validated canonical path, matching the displayed and cleaned path.
Android's `/data/user/0` owner alias must not be mistaken for an unsafe ancestor link; arbitrary
links and paths outside that package's cache boundary remain rejected.
An empty path whitelist does not query Framework storage APIs. If Android rejects a Root
process's package/uid attribution, explicit path comparison still works, and unresolved
user-relative whitelist aliases stay protected instead of disappearing. The optional storage
identity lookup is bounded to once per scan or cleanup batch.

Both cache and profile cleanup return `remainingSnapshotId`, `remainingCandidates`, and
`snapshotExpiresInMs`. The profile engine removes this batch's selected candidates and retains
the unselected candidates with the original creation time. A later batch rechecks identity,
permissions, path boundaries, risk confirmation, whitelist and retention. Changing the target
inode or a selected file's size/mtime invalidates that candidate. The UI never lengthens the
original review expiry. High-risk items still require explicit selection and confirmation.

Before submitting cleanup or quarantine, the App waits for its interrupted-task review record
to commit. Stopping during preflight prevents later mutation requests. Service disconnection
cancels the old operation coroutine; obsolete replies cannot replace the current local report.
No automatic retry is made after a submitted mutation loses its response.

The APK and module share `config/operation-lock.sh`. Foreground mutations own an exclusive
kernel flock lease. Read-only foreground scans and compatible scheduled lanes use shared
leases. Detached module children inherit the descriptor until they finish. This mutual
exclusion requires the matching module; old module versions do not participate in this gate.
The unchanged legacy per-lane locks still prevent incompatible scheduled workers overlapping.
The lock inode is never removed, and a lost App process closes its helper's stdin and lease.
Android's mksh requires explicit descriptor inheritance for flock providers and detached
runners. Both the operation gate and stale-lock recovery guards now carry those descriptors
explicitly. A broken provider or bad descriptor is reported as a tool error, not a busy task.
See the [Linux flock semantics](https://man7.org/linux/man-pages/man2/flock.2.html).

Bundled rule updates are staged as complete immutable generations. Asset-read, staging, or
activation failure keeps the prior complete rules bundle. Existing scanners retain their
generation; neither a failed rename nor an incomplete asset set overwrites active rules.

Validation includes session tests with blocked IO and services, selection authorization tests,
kernel-lock tests against the real module launcher/runner, and rules publication fault tests.
The debug-only `CacheRootDeviceProbe` runs on a disposable Android emulator as uid 0. It uses
the real service FD transport to scan synthetic cache roots, reject an unknown path, delete only
the selected root's contents, preserve the unselected root, recreate the service and continue.
It also exercises consecutive low/high-risk profile batches with the same snapshot. The probe
is excluded from release APKs and refuses to run outside the CI emulator.
