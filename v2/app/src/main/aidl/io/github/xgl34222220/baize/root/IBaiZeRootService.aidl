package io.github.xgl34222220.baize.root;

interface IBaiZeRootService {
    String ping();
    String getProfileCatalog();
    String scanCandidates(String whitelistJson);
    String getResultPage(String snapshotId, int offset, int limit);
    String cleanSelected(String snapshotId, String selectionJson, String whitelistJson);
    String scanProfile(String profile, String optionsJson);
    String getProfilePage(String snapshotId, int offset, int limit);
    String cleanProfileSelected(String snapshotId, String selectionJson, String optionsJson);
    String getTaskState();
    void cancelCurrentTask();
}
