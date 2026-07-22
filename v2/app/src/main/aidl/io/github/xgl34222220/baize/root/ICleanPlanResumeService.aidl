package io.github.xgl34222220.baize.root;

interface ICleanPlanResumeService {
    String ping();
    String begin(String planId, String cacheSnapshotId, String safeSnapshotId, int cacheCount, int safeCount);
    String checkpointCache(String planId, String resultJson);
    String checkpointSafe(String planId, String resultJson);
    String recover(String planId);
    String finish(String planId);
}
