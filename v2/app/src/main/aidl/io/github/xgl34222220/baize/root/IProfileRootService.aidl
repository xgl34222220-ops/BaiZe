package io.github.xgl34222220.baize.root;

import io.github.xgl34222220.baize.root.ITaskProgressCallback;

interface IProfileRootService {
    String ping();
    String getProfileCatalog();
    String scanProfile(String profile, String optionsJson);
    String getProfilePage(String snapshotId, int offset, int limit);
    String cleanProfileSelected(String snapshotId, String selectionJson, String optionsJson);
    String quarantineProfileSelected(String snapshotId, String selectionJson, String optionsJson);
    String prepareCacheSelection(String snapshotId, String selectionJson);

    String getQuarantinePage(int offset, int limit);
    String restoreQuarantineItem(String id);
    String purgeQuarantineItem(String id);
    String purgeExpiredQuarantine();

    String runModuleTask(String mode);
    String runMaintenanceTool(String tool, String optionsJson);
    String getModuleState();
    String getTaskHistory(int limit);
    String getTaskHistoryPage(int offset, int limit);
    String getAuditTimelinePage(int offset, int limit);
    String clearAuditTimeline();
    String updateRuleQualityReview(String ruleKey, String action, String note);
    String getScanCoverage();
    String clearTaskHistory();
    String getRawLog(int maxChars);
    String clearRawLogs();
    String recordNativeTask(String taskJson);
    String getSchedulerConfig();
    String saveSchedulerConfig(String configJson);
    String resetScanWorkerProfile();
    String clearPackageCaches(String requestJson);
    String scanFileOrganizer();
    String applyFileOrganizer(String snapshotId, String selectionJson);
    String undoFileOrganizer();

    String getInstalledPackageCatalog();
    String getWhitelistPackages();
    String saveWhitelistPackages(String packagesJson);
    String getWhitelistPaths();
    String addWhitelistPath(String path);
    String getExclusions();
    String addExclusion(String exclusionJson);
    String removeExclusion(String id);

    String getTaskState();
    void registerTaskProgressCallback(ITaskProgressCallback callback);
    void unregisterTaskProgressCallback(ITaskProgressCallback callback);
    void cancelCurrentTask();
}
