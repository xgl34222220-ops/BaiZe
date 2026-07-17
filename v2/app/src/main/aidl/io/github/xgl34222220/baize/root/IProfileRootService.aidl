package io.github.xgl34222220.baize.root;

interface IProfileRootService {
    String ping();
    String getProfileCatalog();
    String scanProfile(String profile, String optionsJson);
    String getProfilePage(String snapshotId, int offset, int limit);
    String cleanProfileSelected(String snapshotId, String selectionJson, String optionsJson);

    // Alpha 6 product path: run the module cleaner directly. "clean" performs one-tap safe
    // discovery + deletion; "scan" only audits and never requires opening every category.
    String runModuleTask(String mode);
    String getModuleState();
    String getSchedulerConfig();
    String saveSchedulerConfig(String configJson);

    String getTaskState();
    void cancelCurrentTask();
}
