package io.github.xgl34222220.baize.root;

interface ICandidatePlanService {
    String ping();
    String finalizePlan(String cacheSnapshotId, String safeSnapshotId, String selectionJson);
}
