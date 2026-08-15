/*
 * deep_risk() 路径分段匹配回归测试。
 *
 * 历史缺陷：deep_risk() 用 strstr 在整条路径上找子串，导致
 *   /nfc/logo               命中 "log"   → low → 定时任务自动删除
 *   /files/login-identifier 命中 "log"   → low
 *   /Cacheapps2sdcard/...   命中 "cache" → low
 * 这些都是用户数据。修复后必须按完整路径分段匹配。
 *
 * 构建：sh v2/tests/run-native-tests.sh
 */
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <ctype.h>
#include <limits.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/* ——— 被测实现：与 v2/native/baize_engine_42_4.c 保持一致 ——— */

static bool lower_copy(char *out, size_t cap, const char *in) {
    size_t i = 0;
    for (; in[i] && i + 1 < cap; i++) out[i] = (char)tolower((unsigned char)in[i]);
    out[i] = '\0';
    return in[i] == '\0';
}

static bool has_path_segment(const char *lower_path, const char *seg) {
    size_t n = strlen(seg);
    if (n == 0) return false;
    const char *p = lower_path;
    while ((p = strstr(p, seg)) != NULL) {
        char next = p[n];
        if (p > lower_path && p[-1] == '/' && (next == '\0' || next == '/')) return true;
        p += 1;
    }
    return false;
}

static const char *deep_risk(const char *p) {
    char s[PATH_MAX];
    if (!lower_copy(s, sizeof(s), p)) return "critical";

    static const char *critical[] = {"download", "downloads", "documents", "dcim", "pictures",
                                     "movies", "music", "obb", "backup", "backups", "draft",
                                     "drafts", "database", "databases", "shared_prefs"};
    for (size_t i = 0; i < sizeof(critical) / sizeof(critical[0]); i++)
        if (has_path_segment(s, critical[i])) return "critical";

    static const char *low[] = {"cache", "caches", "code_cache", "code cache", "gpucache",
                                "tmp", "temp", "log", "logs", ".cache", ".thumbnails"};
    for (size_t i = 0; i < sizeof(low) / sizeof(low[0]); i++)
        if (has_path_segment(s, low[i])) return "low";
    if (strstr(s, "/crashpad/completed/") || has_path_segment(s, "completed")) {
        if (strstr(s, "/crashpad/")) return "low";
    }

    static const char *medium[] = {"crash", "tombstone", "tombstones", "debug", "trace",
                                   "traces", "dump", "dumps"};
    for (size_t i = 0; i < sizeof(medium) / sizeof(medium[0]); i++)
        if (has_path_segment(s, medium[i])) return "medium";

    return "high";
}

/* ——— 测试 ——— */

static int failures = 0;
static int checks = 0;

static void expect(const char *path, const char *want, const char *why) {
    const char *got = deep_risk(path);
    checks++;
    if (strcmp(got, want) != 0) {
        failures++;
        printf("  [FAIL] %s\n         期望 %-8s 实际 %-8s  (%s)\n", path, want, got, why);
    }
}

int main(void) {
    printf("deep_risk 分段匹配回归测试\n\n");

    printf("— 回归：历史 strstr 实现会误判为 low 的真实规则 —\n");
    expect("/storage/emulated/0/Android/data/com.huawei.wallet/files/nfc/logo",
           "high", "logo 不是 log");
    expect("/storage/emulated/0/Android/data/com.tencent.ig/files/login-identifier.txt",
           "high", "login-identifier 不是 log");
    expect("/storage/emulated/0/Cacheapps2sdcard/data/data/com.tencent.mm",
           "high", "Cacheapps2sdcard 不是 cache");
    expect("/storage/emulated/0/Android/data/com.coolapk.market/cachett_ad",
           "high", "cachett_ad 不是 cache");
    expect("/storage/emulated/0/Android/data/com.ihuman.recite/files/cache_1",
           "high", "cache_1 不是 cache");
    expect("/storage/emulated/0/Android/data/com.Chovvy.CytoidEditor/files/temps",
           "high", "temps 不是 temp");
    expect("/storage/emulated/0/Android/data/com.huati/huluxia/logger",
           "high", "logger 不是 log");
    expect("/storage/emulated/0/logger/logs_0.csv",
           "high", "logger 与 logs_0.csv 都不是完整分段");
    expect("/storage/emulated/0/koolearn/cacheError.txt",
           "high", "cacheError.txt 不是 cache");
    expect("/storage/emulated/0/baidu/tempdata",
           "high", "tempdata 不是 temp");
    expect("/storage/emulated/0/Data/cachedir",
           "high", "cachedir 不是 cache");
    expect("/storage/emulated/0/temp_backup_2026",
           "high", "temp_backup_2026 不是 temp，且是用户备份");

    printf("— 正例：真正的缓存/日志目录仍应判为 low —\n");
    expect("/data/data/com.example.app/cache", "low", "cache 是完整分段");
    expect("/data/data/com.example.app/cache/webview/x.dat", "low", "父级 cache 命中");
    expect("/data/data/com.example.app/code_cache", "low", "code_cache");
    expect("/data/user/0/com.example.app/no_backup/../cache", "low", "结尾 cache");
    expect("/storage/emulated/0/Android/data/com.example/files/log", "low", "log 是完整分段");
    expect("/storage/emulated/0/Android/data/com.example/files/logs/a.txt", "low", "logs");
    expect("/storage/emulated/0/Android/data/com.example/tmp", "low", "tmp");
    expect("/storage/emulated/0/Android/data/com.example/temp", "low", "temp");
    expect("/data/data/com.example/.cache", "low", ".cache");
    expect("/storage/emulated/0/.thumbnails", "low", ".thumbnails");

    printf("— 关键：用户内容目录必须是 critical —\n");
    expect("/storage/emulated/0/Download", "critical", "Download");
    expect("/storage/emulated/0/Download/cache", "critical", "critical 优先于 low");
    expect("/storage/emulated/0/DCIM/Camera", "critical", "DCIM");
    expect("/storage/emulated/0/Pictures", "critical", "Pictures");
    expect("/storage/emulated/0/Movies", "critical", "Movies");
    expect("/storage/emulated/0/Music", "critical", "Music");
    expect("/storage/emulated/0/Documents", "critical", "Documents");
    expect("/storage/emulated/0/Android/obb/com.example", "critical", "obb");
    expect("/data/data/com.example/shared_prefs", "critical", "shared_prefs");
    expect("/data/data/com.example/databases", "critical", "databases");
    expect("/storage/emulated/0/backup", "critical", "backup");

    printf("— medium —\n");
    expect("/data/data/com.example/crash", "medium", "crash");
    expect("/data/tombstones", "medium", "tombstones");
    expect("/data/data/com.example/dump", "medium", "dump");
    expect("/data/data/com.example/crashlytics", "high", "crashlytics 不是 crash");

    printf("— 兜底：无法归类的一律 high（仅扫描） —\n");
    expect("/storage/emulated/0/Android/data/com.example/files", "high", "无关键词");
    expect("/storage/emulated/0/哔哩哔哩下载", "high", "中文目录名");

    printf("— 超长路径必须降级为最保守等级 —\n");
    {
        char *big = malloc(PATH_MAX + 64);
        memset(big, 'a', PATH_MAX + 60);
        big[0] = '/';
        big[PATH_MAX + 60] = '\0';
        memcpy(big + PATH_MAX + 50, "/cache", 6);
        const char *got = deep_risk(big);
        checks++;
        if (strcmp(got, "critical") != 0) {
            failures++;
            printf("  [FAIL] 超长路径 期望 critical 实际 %s\n", got);
        }
        free(big);
    }

    printf("\n%d 项断言，%d 项失败\n", checks, failures);
    if (failures == 0) printf("全部通过\n");
    return failures == 0 ? 0 : 1;
}
