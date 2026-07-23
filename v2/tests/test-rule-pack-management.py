#!/usr/bin/env python3
from pathlib import Path

# Stage seven primitives remain auditable maintainer tooling, but are not exposed in the built-in-rule product.
ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
SERVICE = (APP / "root/RulePackRootService.kt").read_text(encoding="utf-8")
ACTIVITY = (APP / "RulePackActivity.kt").read_text(encoding="utf-8")
AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IRulePackService.aidl").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
CENTER = (APP / "CleanCenterActivity.kt").read_text(encoding="utf-8")
BUILDER = (ROOT / "v2/tools/build-rule-pack.py").read_text(encoding="utf-8")
DOC = (ROOT / "v2/docs/rule-pack-format.md").read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


for method in ("getCurrent", "previewPackage", "applyPreview", "rollback", "getHistory"):
    require(method in AIDL, f"rule pack binder method missing: {method}")

for marker in (
    "JarFile(file, true)", "entry.certificates", "trustedSignerFingerprints",
    "GET_SIGNING_CERTIFICATES", "signer_mismatch", "entry_unsigned", "mixed_signers",
    "validateImportPath", "rule-pack-imports", "MAX_EXPANDED_BYTES", "MAX_ENTRY_BYTES",
    "validateManifest", "rule_hash_mismatch", "safeRuleSyntax", "FORBIDDEN_RULE_ROOTS",
    "backupCurrent", "applyRuleSet", "atomicCopy", "rollbackAvailable", "MAX_BACKUPS = 3",
    "running.env",
):
    require(marker in SERVICE, f"signed rule pack safety primitive missing: {marker}")

require("scanProfile(" not in SERVICE and "scanSafe(" not in SERVICE and "cleanSelected(" not in SERVICE,
        "rule pack service must not scan or clean user data")
require("http://" not in SERVICE and "https://" not in SERVICE,
        "rule pack service must not fetch untrusted remote content")
require('setOf("app.rules", "external.rules", "hidden.rules", "deep.rules")' in SERVICE,
        "only managed official rule files may be replaced")
require("custom.rules" not in SERVICE,
        "user-owned custom rules must not enter the managed replacement set")

# Source remains available for maintainer-side inspection and possible future tooling.
for marker in (
    "ActivityResultContracts.OpenDocument", "copyImport", "previewPackage", "applyPreview",
    "rollback", "签名验证", "更新预览", "custom.rules 不会被修改",
):
    require(marker in ACTIVITY, f"archived rule pack UI primitive missing: {marker}")

require('android:name=".RulePackActivity"' not in MANIFEST,
        "rule pack activity must not be exposed in the built-in-rule product")
require('android:name=".root.RulePackRootService"' not in MANIFEST,
        "rule pack Root service must not be exposed in the built-in-rule product")
require("RulePackActivity::class.java" not in CENTER and "规则管理中心" not in CENTER,
        "clean center must not expose the removed rule pack manager")

for marker in (
    "build-rule-pack", "rule-pack.json", "META-INF/MANIFEST.MF", "deep.rules",
    "jarsigner", "same certificate",
):
    require(marker in BUILDER or marker in DOC, f"rule pack publishing contract missing: {marker}")

require("custom.rules" in DOC and "never replaced" in DOC,
        "documentation must preserve user-owned custom rules")

print("maintainer signed rule pack tooling contract: ok")
