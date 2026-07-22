package io.github.xgl34222220.baize.root;

interface ICleanResultService {
    String ping();
    String registerPlan(String planId, int authorizedCandidates, long estimatedBytes);
    String archive(String planId);
    String getSummary(String reportId);
    String getPage(String reportId, int offset, int limit, String filterJson);
}
