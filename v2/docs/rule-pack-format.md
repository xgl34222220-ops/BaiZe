# BaiZe signed rule packs

BaiZe rule packs are complete JAR files. They are imported through Android's system file picker and must be signed with the same certificate as the installed BaiZe APK.

## Contents

```text
META-INF/MANIFEST.MF
META-INF/<SIGNER>.SF
META-INF/<SIGNER>.RSA
rule-pack.json
rules/app.rules          optional
rules/external.rules     optional
rules/hidden.rules       optional
rules/deep.rules         required for official full packs
```

`custom.rules` is user-owned and is never replaced by the rule-pack manager.

## Manifest

```json
{
  "schema": 1,
  "mode": "full",
  "packId": "baize-official",
  "version": "2026.07.1",
  "createdBy": "BaiZe GitHub Actions",
  "releaseNotes": "Improve OEM log and fragment coverage.",
  "minAppVersionCode": 24001,
  "files": [
    {
      "path": "rules/deep.rules",
      "sha256": "<64 lowercase hexadecimal characters>",
      "rules": 4714,
      "bytes": 123456
    }
  ]
}
```

The manifest must list every payload under `rules/`. Extra payload files, duplicate entries, path traversal, unsigned entries, mixed signers and certificate mismatches are rejected.

## Build and sign

```bash
python3 v2/tools/build-rule-pack.py \
  --rules-dir <module-config-directory> \
  --version 2026.07.1 \
  --release-notes "Rule maintenance update" \
  --output BaiZe-Rules-2026.07.1-unsigned.jar

jarsigner \
  -keystore <production-keystore> \
  -signedjar BaiZe-Rules-2026.07.1.jar \
  BaiZe-Rules-2026.07.1-unsigned.jar \
  <production-alias>

jarsigner -verify -strict -certs BaiZe-Rules-2026.07.1.jar
```

The key alias must resolve to the same signer certificate used for the production APK. Pull-request APKs use a temporary certificate, so production rule packs intentionally do not validate in PR builds.

## Installation safety

Before installation, the Root service:

1. Reads the selected file only from the app's private import cache.
2. Verifies the JAR signature of `rule-pack.json` and every rule entry.
3. Compares the signer SHA-256 fingerprint with the installed APK signer.
4. Verifies manifest hashes, byte sizes and effective rule counts.
5. Rejects unsafe roots, top-level wildcards, traversal, oversized entries and ZIP bombs.
6. Shows added, changed, removed and unchanged files before mutation.
7. Saves a root-only complete backup.
8. Atomically replaces only managed rule files.

The manager keeps at most three backups and exposes the latest one through one-click rollback. Active scans, cleans and organizer tasks block installation and rollback.
