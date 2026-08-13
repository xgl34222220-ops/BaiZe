# Formal release evidence

Formal tags are created only by the manual release workflow. Add `vX.Y.Z.json` here after testing at least two distinct physical ARM64 devices; one must run Android 8 / API 26. Each device must record `kind`, `abi`, `api`, `buildFingerprint`, and true checks for `install`, `scan_only`, `snapshot_clean`, `cancel_resume`, `reboot_restore`, `quarantine_restore`, and `multi_user_guard`.

Release tags are never reused or moved. A failed release gets a new version and tag.
