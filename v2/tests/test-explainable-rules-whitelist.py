#!/usr/bin/env python3
from pathlib import Path

# Sixth-stage contract: reports explain matches and mutate only explicit whitelist settings.
ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "v2/app/src/main/java/io/github/xgl34222220/baize"
ACTIVITY = (APP / "CleanResultActivity.kt").read_text(encoding="utf-8")
SERVICE = (APP / "root/CleanResultRootService.kt").read_text(encoding="utf-8")
PROFILE_AIDL = (ROOT / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl").read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        raise AssertionError(message)


for marker in (
    "EXPLANATION_SCHEMA", "rule-explanation-v1", "enrichItem", "inferPackageName",
    "explainRule", "fragmentExplanation", "rulePathExplanation", "reasonCode",
    "ruleId", "ruleLabel", "ruleSource", "matchReason", "protectionTarget",
    "canProtectPackage", "canProtectPath", "PACKAGE_PATH_PATTERNS"
):
    require(marker in SERVICE, f"explainable result primitive missing: {marker}")

require("add(enrichItem(source))" in SERVICE,
        "old archived result rows must be enriched when read")
require("scanCandidates(" not in SERVICE and "scanSafe(" not in SERVICE and "engine.scan(" not in SERVICE,
        "rule explanation service must remain read-only and must not discover candidates")
require("MUTABLE_ROOTS" in SERVICE and "HARD_EXACT" in SERVICE and "READ_ONLY" in SERVICE,
        "path whitelist suggestions must respect fixed safety roots")

for marker in (
    "BaiZeProfileRootService", "IProfileRootService", "saveWhitelistPackages",
    "getWhitelistPackages", "protectPackage", "protectPath", "resolveAppName",
    "package_whitelist", "path_whitelist", "保护整个应用", "保护此路径",
    "规则来源", "应用归属", "settingsCanChange", "state.live && state.remainingCandidates > 0"
):
    require(marker in ACTIVITY, f"report whitelist or app UI contract missing: {marker}")

require("String saveWhitelistPackages(String packagesJson);" in PROFILE_AIDL,
        "package whitelist must be written through the Root profile service")
require("preferences.edit().putStringSet(PREF_PACKAGE_WHITELIST" in ACTIVITY,
        "successful Root package whitelist writes must synchronize local plan options")
require("preferences.edit().putStringSet(PREF_PATH_WHITELIST" in ACTIVITY,
        "path protection must persist into smart-clean options")
require("scanCandidates(" not in ACTIVITY and "scanSafe(" not in ACTIVITY and "cleanSelected(" not in ACTIVITY,
        "report UI actions must not scan or clean")

save_pos = ACTIVITY.index("service.saveWhitelistPackages")
local_package_pos = ACTIVITY.index("preferences.edit().putStringSet(PREF_PACKAGE_WHITELIST", save_pos)
require(save_pos < local_package_pos,
        "package whitelist must be accepted by Root before local options are updated")

print("explainable rule and whitelist contract: ok")
