package io.github.xgl34222220.baize.root;

interface IBaiZeRootService {
    String ping();
    String scanCandidates(String whitelistJson);
    String getResultPage(String snapshotId, int offset, int limit);
    String cleanSelected(String snapshotId, String selectionJson, String whitelistJson);
    String getTaskState();
    void cancelCurrentTask();
}
