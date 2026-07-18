package io.github.xgl34222220.baize.root;

interface IProfileRootService {
    String ping();
    String getProfileCatalog();
    String scanProfile(String profile, String optionsJson);
    String getProfilePage(String snapshotId, int offset, int limit);
    String cleanProfileSelected(String snapshotId, String selectionJson, String optionsJson);

    String runModuleTask(String mode);
    String getModuleState();
    String getSchedulerConfig();
    String saveSchedulerConfig(String configJson);

    String getWhitelistPackages();
    String saveWhitelistPackages(String packagesJson);

    String getTaskState();
    void cancelCurrentTask();
}
