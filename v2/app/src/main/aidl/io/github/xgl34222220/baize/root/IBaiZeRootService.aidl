package io.github.xgl34222220.baize.root;

interface IBaiZeRootService {
    String ping();
    String scanCandidates(String whitelistJson);
    String getResultPage(int offset, int limit);
    void cancelCurrentTask();
}
