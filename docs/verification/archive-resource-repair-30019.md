# 30019: borrowed archive resource lifetime

The 30018 / 6508a8cd signed-APK emulator run (37053202512) completed all new navigation, audit, Back and both-theme draft checks, then crashed while returning the APK review screen from landscape to portrait. The actual crash buffer reports `AssetManager has been destroyed` inside `ResourcesManager.applyConfigurationToResources` and `ResourcesImpl.updateConfiguration`. The process restarted into the dashboard; this was a real crash, not simply a missing selection label. That candidate is not accepted for delivery.

`AndroidApkArchivePlatform.archiveResources` returns `PackageManager.getResourcesForApplication`. Those resources remain framework-owned even when their archive path is a unique private alias. `readArchive` incorrectly closed the borrowed AssetManager in its finally block, leaving a framework-tracked ResourcesImpl with destroyed assets.

The minimal fix removes that close. Caller-owned source descriptors, temporary aliases and ZipFile streams retain their existing scoped cleanup. No independent AssetManager is created by production code. Test fixture providers now explicitly release their own constructed AssetManagers in @After.

Three ownership regressions were first run against the old implementation and all failed: successful preview/configuration changes, cancellation after resource acquisition, and icon-unavailable partial metadata. All three passed after the fix, together with existing APK metadata and recreation tests. The signed-device check retains the selected APK and verifies three full landscape/portrait cycles and Back. It does not bypass or weaken the selection assertion.

The existing preview-density adjustment is unchanged in this focused fix. Exact-commit full CI, archive icon/name correctness, repeated rotation and final package verification are still required before delivery. Main/release/OTA are unchanged.

Local validation: 642 JVM/UI tests passed with zero failures/errors/skips, including the three red-to-green ownership checks; lintDebug passed. Python smoke syntax and source-version consistency passed. Source review confirmed all production archive Resources come from PackageManager and caller-owned stream/descriptor/alias cleanup remains in place. Device verification is pending; no 30018 crash candidate is to be delivered.
