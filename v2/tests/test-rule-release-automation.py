#!/usr/bin/env python3
# Stage nine final verification: signed release rehearsal, visible rule entry, and full repository build must pass.
# The rule center must be visible from both the MIUIx home page and clean page.
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/rule-release.yml").read_text(encoding="utf-8")
VERIFY = (ROOT / "v2/tools/verify-rule-release.py").read_text(encoding="utf-8")
JAR_VERIFY = (ROOT / "v2/tools/verify-signed-jar.sh").read_text(encoding="utf-8")
PACK_BUILDER = (ROOT / "v2/tools/build-rule-pack.py").read_text(encoding="utf-8")
INDEX_BUILDER = (ROOT / "v2/tools/build-rule-index.py").read_text(encoding="utf-8")
CLIENT = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/RuleUpdateClient.kt").read_text(encoding="utf-8")
DOC = (ROOT / "v2/docs/rule-release-playbook.md").read_text(encoding="utf-8")
VISIBLE_HOME = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt").read_text(encoding="utf-8")
VISIBLE_CLEAN = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt").read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


for marker in (
    "pull_request:", "workflow_dispatch:", "schedule:", "cron:",
    "channel:", "version:", "version_code:", "min_app_version_code:",
    "release_notes:", "mandatory:", "valid_days:", "publish:", "confirm_publish:",
):
    require(marker in WORKFLOW, f"rule release trigger/input missing: {marker}")

for marker in (
    "BAIZE_KEYSTORE_BASE64", "BAIZE_KEYSTORE_PASSWORD", "BAIZE_KEY_ALIAS", "BAIZE_KEY_PASSWORD",
    "BAIZE_CERT_SHA256", "EXPECTED_RULE_CERT_SHA256", "Prepare temporary rehearsal signer",
    "Prepare formal production signer", "keytool -exportcert", "openssl x509",
):
    require(marker in WORKFLOW, f"release signer contract missing: {marker}")

require("[ \"$GITHUB_REF\" = 'refs/heads/main' ]" in WORKFLOW,
        "formal rule publication must be restricted to main")
require("[ \"$INPUT_CONFIRM\" = 'PUBLISH' ]" in WORKFLOW,
        "formal publication requires an explicit confirmation word")
require("if: steps.release.outputs.publish == 'true'" in WORKFLOW,
        "release upload steps must be guarded by the publish output")
require("github.event_name != 'schedule'" in WORKFLOW and "github.event_name == 'schedule'" in WORKFLOW,
        "release and index refresh jobs must remain separated")

for marker in (
    "python3 v2/tools/build-rule-pack.py", "python3 v2/tools/build-rule-index.py",
    "python3 v2/tools/verify-rule-release.py", "bash v2/tools/verify-signed-jar.sh",
    "keytool -printcert -jarfile", "sha256sum", "rule-pack.json", "rule-index.json",
):
    require(marker in WORKFLOW, f"end-to-end release verification missing: {marker}")

for marker in (
    "jarsigner -verify -strict -certs", "jar verified", "certificate chain is invalid",
    "signer certificate is self-signed", "unsigned entr", "disabled algorithm",
    "certificate.*(expired|not yet valid|revoked)", "unexpected jarsigner status",
):
    require(marker in JAR_VERIFY, f"strict pinned JAR verifier primitive missing: {marker}")
require("case \"$STATUS\"" in JAR_VERIFY and "0)" in JAR_VERIFY and "4)" in JAR_VERIFY,
        "JAR verifier must distinguish success from Android self-signed chain status")

pack_publish = WORKFLOW.index("Publish versioned rule pack before channel index")
index_publish = WORKFLOW.index("Atomically update fixed signed channel index asset")
public_verify = WORKFLOW.index("Download published assets and verify the public path")
require(pack_publish < index_publish < public_verify,
        "workflow must publish pack, then index, then verify public assets")
require("gh release upload \"$PACK_TAG\"" in WORKFLOW and "--clobber" in WORKFLOW,
        "versioned rule assets must support idempotent recovery")
require("gh release upload rules-index" in WORKFLOW,
        "fixed signed index release must be updated explicitly")
require("gh release download \"$PACK_TAG\"" in WORKFLOW and "gh release download rules-index" in WORKFLOW,
        "published assets must be downloaded again for public-path verification")
require("--latest=false" in WORKFLOW,
        "rule-only releases must never replace the latest BaiZe application release")

for marker in (
    "previous-index.json", "versionCode", "must be greater than published",
    "releases[:50]", "rules-index", "matrix:", "channel: [stable, beta]",
    "Re-sign index with a fresh validity window", "valid-days 30",
):
    require(marker in WORKFLOW, f"history/refresh release safety missing: {marker}")

stable_asset = "BaiZe-Rules-Index-stable.jar"
beta_asset = "BaiZe-Rules-Index-beta.jar"
require(stable_asset in CLIENT and beta_asset in CLIENT,
        "client fixed index assets changed unexpectedly")
require('INDEX_ASSET="BaiZe-Rules-Index-${CHANNEL}.jar"' in WORKFLOW,
        "workflow index asset naming must match the client")
require('PACK_TAG="rules-${CHANNEL}-${VERSION_CODE}"' in WORKFLOW,
        "versioned pack release tag is not deterministic")
require('PACK_ASSET="BaiZe-Rules-${CHANNEL}-${VERSION}.jar"' in WORKFLOW,
        "versioned pack asset name is not deterministic")

for marker in (
    "read_pack", "read_index", "payload_entries", "effective_rules",
    "pack URL", "versionCode", "generatedAt", "expiresAt", "sorted(codes, reverse=True)",
    "packSha256", "indexSha256", "expected-signer-sha256",
):
    require(marker in VERIFY, f"release cross-verifier primitive missing: {marker}")
require("META-INF/" in VERIFY and "unexpected rule pack payload entries" in VERIFY,
        "cross-verifier must reject unsigned payload-shaped extras")
require("deep.rules" in VERIFY and "rule count mismatch" in VERIFY,
        "cross-verifier must validate official managed rule metrics")

require('parser.add_argument("--version-code", required=True' in PACK_BUILDER,
        "rule pack builder must require a monotonic version code")
require('"versionCode": args.version_code' in PACK_BUILDER,
        "rule pack manifest must carry the monotonic version code")
require("Historical deep.rules" in PACK_BUILDER and 'if not line.startswith("/")' in PACK_BUILDER,
        "legacy deep annotations must be ignored, not treated as executable rules")
require('parser.add_argument("--generated-at"' in INDEX_BUILDER and 'parser.add_argument("--valid-days"' in INDEX_BUILDER,
        "index builder must support deterministic release timestamps")
require("releases.sort" in INDEX_BUILDER and "duplicate versionCode" in INDEX_BUILDER,
        "index builder must enforce ordered unique versions")

for marker in (
    "same production keystore alias", "Publishing the pack first", "Pull request rehearsal",
    "Formal manual release", "type `PUBLISH`", "weekly scheduled job",
    "downloads the public pack and index", "If pack publication succeeds but index publication fails",
):
    require(marker in DOC, f"release operations documentation missing: {marker}")

for marker in ("规则中心", "官方更新", "RulePackActivity::class.java", "RuleUpdateActivity::class.java"):
    require(marker in VISIBLE_HOME, f"home rule shortcut missing: {marker}")
for marker in ("规则管理中心", "官方规则更新", "更多清理工具", "RulePackActivity::class.java", "RuleUpdateActivity::class.java"):
    require(marker in VISIBLE_CLEAN, f"clean page rule entry missing: {marker}")

print("signed rule release automation contract: ok")
