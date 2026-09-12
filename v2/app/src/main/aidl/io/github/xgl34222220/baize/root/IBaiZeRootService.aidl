package io.github.xgl34222220.baize.root;

import android.os.ParcelFileDescriptor;

interface IBaiZeRootService {
    String ping();
    String scanCandidates(String whitelistJson);
    String getResultPage(String snapshotId, int offset, int limit);
    String cleanSelected(String snapshotId, String selectionJson, String whitelistJson);
    String getTaskState();
    void cancelCurrentTask();
    // Appended: preserve the transaction IDs used by existing clients.
    ParcelFileDescriptor exchangeJson(String operation, in ParcelFileDescriptor request);
    // App-created response FD: never return a Root-created inode to the App.
    int exchangeJsonInto(String operation, in ParcelFileDescriptor request, in ParcelFileDescriptor response);
}
