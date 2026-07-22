package io.github.xgl34222220.baize.root;

interface IRuleIndexService {
    String ping();
    String verifyIndex(String indexPath, String channel, long currentVersionCode);
    String getCheckpoint(String channel);
}
