#!/usr/bin/env python3
# Stage ten: signed rule release tooling remains maintainable, while end users consume expanded built-in rules.
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/rule-release.yml").read_text(encoding="utf-8")
VERIFY = (ROOT / "v2/tools/verify-rule-release.py").read_text(encoding="utf-8")
JAR_VERIFY = (ROOT / "v2/tools/verify-signed-jar.sh").read_text(encoding="utf-8")
PACK_BUILDER = (ROOT / "v2/tools/build-rule-pack.py").read_text(encoding="utf-8")
INDEX_BUILDER = (ROOT / "v2/tools/build-rule-index.py").read_text(encoding="utf-8")
CLIENT = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/RuleUpdateClient.kt").read_text(encoding="utf-8")
DOC = (ROOT / "v2/docs/rule-release-playbook.md").read_text(encoding="utf-8")
MANIFEST = (ROOT / "v2/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
APPLICATION = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeApplication.kt").read_text(encoding="utf-8")
CLEAN_CENTER = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/CleanCenterActivity.kt").read_text(encoding="utf-8")
HOME = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt").read_text(encoding="utf-8")
CLEAN_UI = (ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize/ui/clean/miuix/CleanScreenMiuix.kt").read_text(encoding="utf-8")
APP_RULES_TEXT = (ROOT / "config/app.rules").read_text(encoding="utf-8")
EXTERNAL_RULES_TEXT = (ROOT / "config/external.rules").read_text(encoding="utf-8")


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
        "maintainer release tooling asset names changed unexpectedly")
require('INDEX_ASSET="BaiZe-Rules-Index-${CHANNEL}.jar"' in WORKFLOW,
        "workflow index asset naming must match the release tooling")
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

# The updater remains a maintainer-side release capability, not an end-user product surface.
for marker in (
    "RulePackActivity", "RuleUpdateActivity", "RulePackRootService", "RuleIndexRootService",
    "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
):
    require(marker not in MANIFEST, f"unused rule-center component still exposed in manifest: {marker}")
require("RuleUpdateWorker.ensureScheduled" not in APPLICATION,
        "online rule worker must not start in the built-in-rule product")
for source_name, source in (
    ("clean center", CLEAN_CENTER),
    ("MIUIx home", HOME),
    ("MIUIx clean page", CLEAN_UI),
):
    for marker in ("RulePackActivity", "RuleUpdateActivity", "规则管理中心", "官方规则更新", "规则中心", "官方更新"):
        require(marker not in source, f"{source_name} still exposes removed rule center: {marker}")

PACKAGE = re.compile(r"^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$")


def parse_rules(text: str, name: str) -> list[str]:
    rules: list[str] = []
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        require(len(parts) == 3, f"{name}:{number}: expected package|relative-path|days")
        package, relative, days = (part.strip() for part in parts)
        require(bool(PACKAGE.fullmatch(package)), f"{name}:{number}: invalid package")
        require(relative and not relative.startswith(("/", "\\")), f"{name}:{number}: absolute path")
        require("\x00" not in relative and "\\" not in relative, f"{name}:{number}: invalid separator")
        require(all(part not in {"", ".", ".."} for part in relative.split("/")),
                f"{name}:{number}: path traversal or empty segment")
        require(days.isdigit() and 0 <= int(days) <= 3650, f"{name}:{number}: invalid retention")
        rules.append(line)
    require(len(rules) == len(set(rules)), f"{name}: duplicate rules")
    return rules


app_rules = parse_rules(APP_RULES_TEXT, "app.rules")
external_rules = parse_rules(EXTERNAL_RULES_TEXT, "external.rules")
require(len(app_rules) >= 430, f"app.rules expansion regressed: {len(app_rules)}")
require(len(external_rules) >= 232, f"external.rules expansion regressed: {len(external_rules)}")
require("2026.07.2" in APP_RULES_TEXT and "2026.07.2" in EXTERNAL_RULES_TEXT,
        "built-in rule refresh marker missing")

for rule in (
    "com.xingin.xhs|app_webview/Default/GPUCache|0",
    "com.android.chrome|app_chrome/Crashpad/pending|0",
    "com.qiyi.video|files/logs|0",
    "me.ele|app_webview/Default/Code Cache|0",
    "com.baidu.BaiduMap|files/MiPushLog|0",
):
    require(rule in app_rules, f"representative built-in app rule missing: {rule}")

for rule in (
    "com.xingin.xhs|files/xlog|0",
    "tv.danmaku.bili|files/perfUploading|0",
    "com.taobao.taobao|files/tnetlogs|0",
    "com.tencent.qqlive|files/crash|0",
    "com.microsoft.office.outlook|files/logs|0",
):
    require(rule in external_rules, f"representative external rule missing: {rule}")

print(
    "built-in rule product contract: ok "
    f"(app={len(app_rules)}, external={len(external_rules)})"
)
