package io.github.xgl34222220.baize.root;

import android.os.ParcelFileDescriptor;

interface IPersistentCleanPlanService {
    String ping();
    String scanSafe(String optionsJson);
    String getPage(String snapshotId, int offset, int limit);
    String cleanSafe(String snapshotId, String selectionJson, String optionsJson);
    String getTaskState();
    void cancelCurrentTask();
    // Appended: preserve the transaction IDs used by existing clients.
    ParcelFileDescriptor exchangeJson(String operation, in ParcelFileDescriptor request);
}
