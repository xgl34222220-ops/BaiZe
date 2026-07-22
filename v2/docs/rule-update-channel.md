# BaiZe official rule update channel

BaiZe uses two independently signed artifacts:

1. a small signed index JAR containing `rule-index.json`;
2. a full signed rule-pack JAR containing `rule-pack.json` and managed rule files.

Both JARs must be signed with the same certificate as the production BaiZe APK. The App process downloads bytes, while Root services verify signatures and enforce installation policy.

## Official asset locations

The client checks these fixed GitHub Release assets:

- stable: `BaiZe-Rules-Index-stable.jar`
- beta: `BaiZe-Rules-Index-beta.jar`

They are published under the `rules-index` release tag. Release URLs inside the signed index may use only approved GitHub HTTPS asset hosts.

## Rule pack version codes

Every official rule pack must include a positive, monotonically increasing `versionCode` in `rule-pack.json`. Human-readable `version` strings are display-only; update ordering uses `versionCode`.

Build a rule pack:

```bash
python3 v2/tools/build-rule-pack.py \
  --rules-dir dist/rules \
  --version 2026.08.1 \
  --version-code 20260801 \
  --release-notes "Rule maintenance update" \
  --output dist/BaiZe-Rules-2026.08.1-unsigned.jar

jarsigner \
  -keystore "$BAIZE_KEYSTORE_PATH" \
  -signedjar dist/BaiZe-Rules-2026.08.1.jar \
  dist/BaiZe-Rules-2026.08.1-unsigned.jar \
  "$BAIZE_KEY_ALIAS"
```

Record the signed JAR byte length and SHA-256 in a releases JSON file:

```json
{
  "releases": [
    {
      "packId": "baize-official",
      "version": "2026.08.1",
      "versionCode": 20260801,
      "minAppVersionCode": 24001,
      "url": "https://github.com/xgl34222220-ops/BaiZe/releases/download/rules-2026.08.1/BaiZe-Rules-2026.08.1.jar",
      "sha256": "64-lowercase-hex-characters",
      "bytes": 123456,
      "publishedAt": 1785542400000,
      "mandatory": false,
      "releaseNotes": "Rule maintenance update"
    }
  ]
}
```

Build and sign an index:

```bash
python3 v2/tools/build-rule-index.py \
  --channel stable \
  --releases-json dist/stable-releases.json \
  --valid-days 14 \
  --output dist/BaiZe-Rules-Index-stable-unsigned.jar

jarsigner \
  -keystore "$BAIZE_KEYSTORE_PATH" \
  -signedjar dist/BaiZe-Rules-Index-stable.jar \
  dist/BaiZe-Rules-Index-stable-unsigned.jar \
  "$BAIZE_KEY_ALIAS"
```

## Root verification

`RuleIndexRootService` verifies the complete JAR signature, checks the signer against the installed APK, validates timestamps, URLs, release hashes and sizes, then stores a monotonic checkpoint for each channel.

An index is rejected when:

- it is unsigned or signed by a different certificate;
- its channel does not match the selected channel;
- it is expired or has an implausible future timestamp;
- its generated time is older than the last trusted checkpoint;
- the same generated time is reused with different content;
- a release uses a non-approved host, invalid SHA-256, duplicate version code or excessive size.

`RulePackRootService` independently verifies the downloaded pack before preview and installation. A valid index can never make an unsigned or differently signed pack installable.

## Download and automatic policy

Downloads use HTTP Range and If-Range with persisted ETag or Last-Modified metadata. Final byte length and SHA-256 must match the signed index.

Policies:

- manual: no periodic work;
- notify: check every 12 hours on any connected network;
- download: use unmetered network and verify the pack, but require manual installation;
- install: stable channel only, requiring unmetered network, charging, battery-not-low and device idle.

Root refuses installation while scanning, cleaning or organizing. Every successful install still creates the normal three-generation rollback backup. Beta channel never performs silent installation.
