package io.github.xgl34222220.baize.root;

interface IPersistentCleanPlanService {
    String ping();
    String scanSafe(String optionsJson);
    String getPage(String snapshotId, int offset, int limit);
    String cleanSafe(String snapshotId, String selectionJson, String optionsJson);
    String getTaskState();
    void cancelCurrentTask();
}
