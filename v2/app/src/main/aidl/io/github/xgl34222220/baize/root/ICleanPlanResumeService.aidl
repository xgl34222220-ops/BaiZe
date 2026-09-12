package io.github.xgl34222220.baize.root;

import android.os.ParcelFileDescriptor;

interface ICleanPlanResumeService {
    String ping();
    String begin(String planId, String cacheSnapshotId, String safeSnapshotId, int cacheCount, int safeCount);
    String checkpointCache(String planId, String resultJson);
    String checkpointSafe(String planId, String resultJson);
    String recover(String planId);
    String finish(String planId);
    // Appended: preserve the transaction IDs used by existing clients.
    ParcelFileDescriptor exchangeJson(String operation, in ParcelFileDescriptor request);
}
