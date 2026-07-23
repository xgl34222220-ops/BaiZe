# BaiZe signed rule release playbook

This playbook is the operational contract for `.github/workflows/rule-release.yml`.
The workflow uses the same production keystore alias as the BaiZe APK. There is no second rule-signing key.

## Published assets

A rule release publishes assets in this order:

1. A versioned pack release is created at tag `rules-<channel>-<versionCode>`.
2. The signed pack is uploaded as `BaiZe-Rules-<channel>-<version>.jar`.
3. The fixed `rules-index` release is updated with `BaiZe-Rules-Index-<channel>.jar`.
4. The workflow downloads both public assets again and verifies their signatures, hashes, sizes and cross-links.

Publishing the pack first prevents clients from observing an index that points to an asset that does not exist yet.
The fixed index asset names match the URLs compiled into `RuleUpdateClient.kt`.

## Required repository secrets

The workflow reuses the formal APK signing secrets:

- `BAIZE_KEYSTORE_BASE64`
- `BAIZE_KEYSTORE_PASSWORD`
- `BAIZE_KEY_ALIAS`
- `BAIZE_KEY_PASSWORD`

The extracted certificate SHA-256 must equal the certificate pinned in the app release workflow. A mismatch stops the job before any release asset is uploaded.

## Pull request rehearsal

Every pull request that changes the rule release workflow, builders, verifier, documentation or managed rules runs a complete rehearsal with a temporary certificate:

- build the deterministic full rule pack from `config/`;
- sign and strictly verify the JAR;
- build a signed channel index pointing to the versioned pack URL;
- verify exact pack/index linkage;
- upload both signed rehearsal artifacts to GitHub Actions.

A pull request never publishes GitHub Release assets and never reads the production keystore secrets.

## Formal manual release

Run **BaiZe Signed Rule Release** with `workflow_dispatch` from the `main` branch.

Required inputs:

- channel: `stable` or `beta`;
- version: a display value such as `2026.08.1`;
- version code: a positive monotonic integer such as `20260801`;
- minimum app version code;
- release notes;
- signed index validity period from 1 to 45 days.

To publish, enable `publish` and type `PUBLISH` in the confirmation field. The workflow rejects publishing from any ref other than `refs/heads/main`.

The new version code must be greater than the highest version code already present in the signed channel index. Normal publication cannot downgrade rules. Device-side rollback remains the only supported downgrade path.

## Release history and index refresh

The current signed index is downloaded and verified before a new release. Existing releases are preserved, the new release is prepended, and the list is capped at 50 entries.

A weekly scheduled job refreshes the validity window of existing stable and Beta indexes. It does not create a new rule pack, change release history or install anything on devices. Missing channel indexes are skipped safely.

## End-to-end verification

Before publication, the workflow runs:

- `jarsigner -verify -strict -certs` for the pack and index;
- `keytool -printcert -jarfile` and certificate fingerprint comparison;
- SHA-256 and byte count calculation;
- `verify-rule-release.py` for manifest, rule metrics, URL and index linkage validation.

After publication, the workflow downloads the public pack and index through GitHub Releases and repeats the same cryptographic and structural checks. A release is considered complete only after this public-path check succeeds.

## Recovery

If pack publication succeeds but index publication fails, clients do not see the new version because the old signed index remains active. Re-run the same workflow inputs; versioned assets are uploaded with `--clobber`, then the fixed index is updated.

If an incorrect index is published but still signed, immediately publish a higher monotonic version or a corrected index with a later `generatedAt`. The Root verifier rejects older replayed indexes and same-time equivocation.

If the APK signing key is suspected to be compromised, stop rule publication. A new app release with a new pinned certificate and an explicit trust migration is required; do not bypass signature checks.
