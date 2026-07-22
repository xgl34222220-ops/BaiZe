package io.github.xgl34222220.baize.root;

interface IRulePackService {
    String ping();
    String getCurrent();
    String previewPackage(String packagePath);
    String applyPreview(String previewId);
    String rollback();
    String getHistory(int limit);
}
