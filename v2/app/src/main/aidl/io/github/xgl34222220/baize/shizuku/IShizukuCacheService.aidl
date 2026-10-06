package io.github.xgl34222220.baize.shizuku;

interface IShizukuCacheService {
    String capabilities() = 0;
    String clearCaches(String packagesJson) = 1;
    void cancel() = 2;
    void destroy() = 16777114;
}
