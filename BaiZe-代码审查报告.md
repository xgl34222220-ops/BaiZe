# 白泽 BaiZe 代码审查与优化建议

审查对象：`xgl34222220-ops/BaiZe` @ `885524b`（module.prop `v2.5.7` / versionCode `25007`）
规模：Kotlin 42,817 行（115 文件）、C 约 101 KB（3 文件）、Shell 约 35 个脚本、GitHub Workflow 71 个、清理规则 4,755 条

先说结论：这个项目的**安全模型设计得比绝大多数同类清理模块认真**——`deep_allowed()` 用的是白名单而不是黑名单，删除前双次 `lstat` + manifest 元数据比对，全程 `unlink` 不跟随软链接，高风险默认 scan-only。这些是真本事。但**这套设计有一个实现层的缺口，会让保护失效**，另外工程化（测试、CI、版本管理）欠账较多。下面按优先级列出。

---

## P0 — 会导致误删用户数据

### 1. `deep_risk()` 用子串匹配定风险，把用户数据判成 low 后自动删除 ⚠️ 最严重

`v2/native/baize_engine_42_4.c:1192`：

```c
static const char *deep_risk(const char *p) {
    char s[PATH_MAX];
    lower_copy(s, sizeof(s), p);
    const char *low[] = {"/cache", ..., "/tmp", "/temp", "/logs", "/log", ...};
    for (...) if (strstr(s, low[i])) return "low";   // ← strstr 匹配路径任意位置
    ...
}
```

`strstr` 匹配的是**整条路径里任意位置的子串**，不是路径分段。只要目录名以 `log`、`cache`、`temp` 开头，后面接什么都会被判成 low。而 `rules.meta.env` 里 `scheduled_allowed_risk=safe`，**low 风险是允许定时任务自动执行删除的**——README 承诺的"定时任务永不执行高/关键风险"这道闸门在这里被绕过了。

我按这个逻辑跑了一遍现有的 `config/deep.rules`，1,132 条被判为 low，其中 **45 条的路径分段根本不是 cache/log/tmp**。实际命中的例子：

| 规则路径 | 误命中 | 实际是什么 |
|---|---|---|
| `.../com.huawei.wallet/files/nfc/logo` | `/log` ⊂ `/logo` | 华为钱包 NFC 卡面图标 |
| `.../com.tencent.ig/files/login-identifier.txt` | `/log` ⊂ `/login-identifier` | 游戏登录标识，删了可能掉登录/丢账号绑定 |
| `/storage/emulated/0/Cacheapps2sdcard/data/data/com.tencent.mm` | `/cache` ⊂ `/cacheapps2sdcard` | 整个微信数据镜像目录 |
| `.../cn.kuwo.player/files/KuwoMusic/login/LOGIN_CACHE` | `/log` | 登录凭据 |
| `.../com.Chovvy.CytoidEditor/files/temps` | `/temp` | 用户编辑中的谱面 |
| `.../com.ihuman.recite/files/cache_1` | `/cache` | — |

更危险的是这个规则不只作用于仓库内规则——用户自己往 `custom.rules` 里加一条 `/storage/emulated/0/temp_backup_2026`，会被判 low 然后定时删掉整个备份目录。

**修复方向**（按路径分段精确匹配，而不是子串）：

```c
/* 判断 needle 是否是 path 的某一个完整分段 */
static bool has_segment(const char *lower_path, const char *seg) {
    size_t n = strlen(seg);
    const char *p = lower_path;
    while ((p = strstr(p, seg)) != NULL) {
        char next = p[n];
        if (p > lower_path && p[-1] == '/' && (next == '\0' || next == '/'))
            return true;
        p += n;
    }
    return false;
}

static const char *deep_risk(const char *p) {
    char s[PATH_MAX];
    if (!lower_copy_checked(s, sizeof(s), p)) return "critical";  /* 超长路径按最保守处理 */
    static const char *critical[] = {"download","documents","dcim","pictures","movies",
                                     "music","obb","backup","backups","draft","drafts",
                                     "database","databases","shared_prefs"};
    for (size_t i = 0; i < N(critical); i++) if (has_segment(s, critical[i])) return "critical";
    static const char *low[] = {"cache","code_cache","gpucache","tmp","temp",
                                "logs","log",".cache",".thumbnails"};
    for (size_t i = 0; i < N(low); i++) if (has_segment(s, low[i])) return "low";
    static const char *medium[] = {"crash","tombstone","debug","trace","dump"};
    for (size_t i = 0; i < N(medium); i++) if (has_segment(s, medium[i])) return "medium";
    return "high";
}
```

顺带两个附带问题：

- `lower_copy()`（:1187）在超过 `PATH_MAX` 时静默截断。如果 `critical` 关键词恰好在被截掉的尾部，风险会被降级。建议截断即返回 `critical`。
- 建议把降级为 low/medium 的判定收紧为「必须同时命中 low 关键词**且**不在任何 `critical` 关键词的父路径下」，并给 `deep.rules` 加上显式风险列（见 P1-6），让风险等级可审计、可 review，而不是纯靠运行时字符串猜测。

### 2. 规则集里存在直接指向用户媒体目录的条目

`config/deep.rules` 中有 `/storage/emulated/0/哔哩哔哩下载`、`/storage/emulated/0/Bilibili下载`、`/storage/emulated/0/Youku` 等。这些目录装的是用户离线缓存的视频——用户主观上认为那是"我下载的东西"，不是垃圾。目前它们靠 fallback 落到 `high`（scan-only）才幸免。

建议：给这类"用户可感知内容"单独建一档 `user-content` 风险级，**即使手动完整深度清理也要单独二次确认并列出具体文件**，不要和缓存混在同一个确认流程里。

### 3. APK 完整性校验只生成、不验证

`v2/scripts/package-module.sh:57` 生成 `baize.apk.sha256`，`v2/module/customize.sh:149` 只是把它 `cp` 到 state 目录，**安装流程里没有任何一处真正比对过 hash**。这个校验目前是装饰性的。

```sh
# customize.sh 安装 APK 前应加：
EXPECTED=$(cat "$MODPATH/app/baize.apk.sha256")
ACTUAL=$(sha256sum "$MODPATH/app/baize.apk" | awk '{print $1}')
[ "$EXPECTED" = "$ACTUAL" ] || { ui_print "! APK 校验失败，安装中止"; abort; }
```

### 4. 11 个 `pull_request_target` 工作流指向已删除的分支

`v2.3.0-*` 和 `v230-materialize-branch.yml` 全部 `ref: feat/v2.3.0-stabilization`，而该分支在远端**已经不存在**了。后果：

- 任何人提 PR 都会触发这 11 个 workflow 并全部失败，PR 页面一片红叉，真正有用的 `android-ui-ci` 被淹没；
- 其中 4 个带 `permissions: contents: write`，且由 fork PR 的 `synchronize` 事件触发。虽然它们 checkout 的是固定分支（没有直接的代码注入洞），但 `pull_request_target` + `contents: write` + fork 可触发是明确的高危组合，不该保留。

建议直接删掉这 11 个文件。

---

## P1 — 工程质量欠账

### 5. 42,817 行 Kotlin，0 个单元测试

`v2/app/src/` 下只有 `main/`，没有 `test/` 也没有 `androidTest/`。`v2/tests/` 里 36 个 shell 脚本中 **21 个是 grep 断言**——只检查源码里有没有某个字符串，逻辑写错了照样通过，重构一下反而挂掉。

剩下的 15 个（如 `test-scheduler-fairness.sh`、`test-concurrent-scheduler.sh`）是真正的行为测试，质量不错，说明能力是有的，只是没往 JVM 侧铺。

优先补这几处纯逻辑、无 Android 依赖、最容易出错的地方：

- `deep_risk` 的等价 Kotlin/C 单测（P0-1 的回归网）
- `PathCompat`（`/sdcard` ↔ `/data/media/0` 归一化）
- `CleanupEffectivenessAnalyzer` / `RuleQualityAnalyzer` / `PolicyAdvisor`
- `HistoryRepository` 的新旧格式兼容解析
- `SchedulerRepository` 的周期与每日定时计算

CI 的 `android-ui-ci.yml` 目前只跑 `compileDebugKotlin + lintDebug`，加上 `:app:testDebugUnitTest` 即可。

### 6. 版本号散落 6 处，规则 SHA 散落 3 处

发一个版本要手动同步：`module.prop` / `v2/app/build.gradle.kts`（versionCode + versionName）/ `update.json` / `v2/scripts/package-module.sh:12` 的 `OUTPUT` 硬编码 / 新建一个 `v2.5.N-release.yml` / `.github/release-v2.5.N.publish`。规则 SHA 则同时写在 `README.md`、`config/rules.meta.env`、`v2/scripts/validate-rules.py:12`。

建议单一真相源：以 `module.prop` 为准，`build.gradle.kts` 读取它，`update.json` 和 `package-module.sh` 由 release workflow 生成；`validate-rules.py` 从 `rules.meta.env` 读期望 SHA 而不是硬编码。

### 7. 71 个 workflow，约 60 个已死

- `v2-alpha21` ~ `v2-alpha42-*` 共 30 个，绑定的 alpha 分支早已删除；
- `v2.1.0-alpha8~12-direct-verify` 5 个同理；
- `v2.5.2` ~ `v2.5.7` 六个 release workflow **两两 diff 只差版本号字符串和一行测试**（我实测 v2.5.6 vs v2.5.7 只有 2 行不同）；
- 触发方式还是靠往仓库里 push `.github/release-v2.5.7.publish` 这种空文件，`.cleanup/`、`.release/`、`.v240-chinese-ui-trigger` 同理。

建议收敛成 4 个：`ci.yml`（PR：编译 + lint + 单测 + shell 测试）、`release.yml`（`on: push: tags: v*`，版本从 tag 取）、`rules-validate.yml`、`nightly.yml`。触发文件全部删除，改用 tag 和 `workflow_dispatch`。

### 8. 没有 Gradle Wrapper

仓库里没有 `gradlew` / `gradle/wrapper/`，CI 靠 `gradle/actions/setup-gradle@v4` 指定 `gradle-version: '8.13'`。这意味着本地构建和 CI 构建用的 Gradle 版本无法保证一致，也没有 `gradle-wrapper.jar` 的校验和保护。补上 wrapper 并提交。

同时建议引入 `gradle/libs.versions.toml` 版本目录——现在 25 个依赖版本号散在 `build.gradle.kts` 里，Renovate/Dependabot 也不好接。

### 9. 约 2,900 行死代码仍被编译进 APK

这 4 个 Activity 有源码、能编译，但**没有在 `AndroidManifest.xml` 里注册**，也没有任何地方 `startActivity` 它们：

| 文件 | 行数 |
|---|---|
| `ResumableSmartScanActivity.kt` | 1,125 |
| `SmartScanActivity.kt` | 942 |
| `PersistentSmartScanActivity.kt` | 836 |
| `SettingsActivity.kt` | — |

直接删除。

### 10. 三代 UI 实现并存

同一个 App 里同时存在：

1. XML + ViewBinding + `AppCompatActivity` + RecyclerView Adapter（`DashboardActivity` 962 行、`CacheActivity`、`ProfileActivity`、`CandidateAdapter`）
2. Activity 内联 Compose（`ScanWorkbenchActivity` 1,339 行、`AuditActivity` 930 行）
3. `ui/` 包下的 Route/Contract + Material/Miuix 双皮肤（较新、结构最好，但只有 Home/Clean/History/Settings/Logs/Appearance 六页迁移完成）

`build.gradle.kts` 里 `viewBinding = true` 和 `compose = true` 同时开着。建议把第 3 代定为唯一目标形态，按页面逐个迁移，每迁完一页删掉对应的旧 Activity 和 XML，最终关掉 `viewBinding`。

### 11. 全项目只有 1 个 ViewModel，UI 状态挂在 Activity 字段上

`MiuixDashboardActivity.kt:84`：

```kotlin
private var dashboardState = androidx.compose.runtime.mutableStateOf(DashboardUiState())
private var schedulerState = androidx.compose.runtime.mutableStateOf(SchedulerUiState())
```

后果：进程被系统杀死后扫描结果和进度全部丢失（虽然 RootService 侧有磁盘快照兜底，但 UI 得重新走一遍恢复流程）；1,782 行的 Activity 同时承担了状态持有、Root 绑定、业务编排和 UI 组装。

`ui/appearance/AppearanceViewModel.kt` 已经是正确写法了，把这个模式推广到 Dashboard 即可。同时 `rememberSaveable` 只在 9 个文件里用了，其他都是 `remember`，旋转屏幕会丢展开态/滚动位置。

### 12. ProGuard 规则过于单薄

开了 `isMinifyEnabled = true` + `isShrinkResources = true`，但 `proguard-rules.pro` 只有两行：

```
-keep class io.github.xgl34222220.baize.root.** { *; }
-keep class com.topjohnwu.superuser.ipc.** { *; }
```

AIDL 生成的 `IBaiZeRootService$Stub$Proxy` 在 `root` 包下侥幸被覆盖了，但 `org.json` 反射、`ITaskProgressCallback` 回调、Compose 相关的 keep 规则都没有。建议补上 AIDL 显式 keep、`-keepattributes Signature,*Annotation*`，并且**在 CI 里跑一次 release 变体的冒烟安装**，否则混淆问题只会在用户手上暴露。

### 13. 没有任何静态检查配置

没有 `.editorconfig`、`detekt.yml`、ktlint、`lint.xml`、lint baseline。42k 行 Kotlin 完全靠人眼。建议至少加 ktlint + detekt，并把 Android Lint 的 `abortOnError` 打开配 baseline。

---

## P2 — 可维护性与用户覆盖面

### 14. 3,273 处硬编码中文，等于放弃了非中文用户

`res/values/strings.xml` 里**只有 1 条**（`app_name`），86 个 Kotlin 文件里散落 3,273 处中文字面量。Top 5：

```
192  MiuixDashboardActivity.kt
149  DashboardActivity.kt
124  ProfileActivity.kt
111  ResumableSmartScanActivity.kt   ← 还是死代码
 99  AuditActivity.kt
```

Magisk / KernelSU 生态里海外用户占比不低，现在等于完全排除在外。这是投入产出比最高的一项功能性改进：抽取到 `strings.xml` + 补一份 `values-en/`。可以先写个脚本半自动抽取，优先做 Home/Clean/Settings 三个主界面。

Shell 侧同理（`echo "当前架构不支持原生扫描"` 等）。

### 15. 只支持 arm64-v8a

`build-native.sh` 只编 `aarch64-linux-android`，`native-scan.sh:40`、`cache-snapshot-clean.sh:32` 遇到非 arm64 直接 `exit 8`。armeabi-v7a 老设备（恰恰是最需要清理垃圾的那批）完全用不了。

NDK 交叉编译加一个 ABI 成本很低：

```sh
for ABI in arm64-v8a armeabi-v7a x86_64; do
  case "$ABI" in
    arm64-v8a)   TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
    x86_64)      TRIPLE=x86_64-linux-android ;;
  esac
  "$TOOLCHAIN/${TRIPLE}${API}-clang" $COMMON_FLAGS "$ENGINE_SOURCE" -o "build/native/$ABI/baize_engine"
done
```

同时把 `BaiZeRootService.kt:84` 里硬编码的 `bin/arm64-v8a/baize_engine` 改成按 `Build.SUPPORTED_ABIS` 查找。

### 16. 两套构建产物共用同一个模块 id，README 描述的是已废弃的那套

- `scripts/build.sh` 打包**根目录的 v1 shell 模块**（`cleaner.sh` 105 KB + `webroot/` WebUI）
- `v2/scripts/package-module.sh` 打包**实际发布的 v2 模块**（APK + native + `v2/module/`）

两者产出的 zip 都是 `id=baize_v2`。而根 `README.md` 的"从源码构建"章节写的是 `sh scripts/build.sh`——照做会得到一个和 Release 完全不同的东西。

更麻烦的是 `package-module.sh:37` 还把 v1 的 `cleaner.sh` 当 `cleaner.sh.compat` 塞进 v2 包里，再用 `sed` 改路径。105 KB 的遗留脚本靠 sed 打补丁维持兼容，是个定时炸弹。

建议：确认 v1 是否还有兼容需求；若无，删除根目录的 `cleaner.sh` / `webctl.sh` / `status.sh` / `job-runner.sh` / `webroot/` / `scripts/build.sh`，仓库瞬间清爽。若有，把 compat 路径写成显式配置项而不是构建期 sed。

### 17. 文档版本全线滞后

| 文件 | 声称版本 | 实际 |
|---|---|---|
| `README.md` | v1.0.3 | v2.5.7 |
| `docs/README-detailed.md` | v1.0.3 | v2.5.7 |
| `v2/README.md` | "v2 Alpha 33"，"当前开发分支 `v2-alpha33-ui-step1`" | v2.5.7 |

根 README 通篇在讲 WebUI 和 shell 模块，而 v2 已经是"App 通过 libsu RootService 调用引擎，不依赖 WebUI"。新用户看完 README 装上包会完全对不上。

另外根目录有 7 个 `CHANGELOG-*.md` + 12 个 `RELEASE_NOTES_*.md`，`v2/` 根目录还散着 20 个 `ALPHA*-CHANGES.md` / `*-PLAN.md`。建议合并成单个 `CHANGELOG.md`，历史 release notes 移到 GitHub Releases 页面。

### 18. `BaiZeRootService` 的 30+ 个 `@Volatile` 字段

`BaiZeRootService.kt:41-76` 有 30 多个 `@Volatile private var snapshotXxx`，然后 `ping()` 里写了 40 次 `.put("x", if (ready) snapshotX else 默认值)`。

问题不只是难看：这些字段是**分别**更新的，读取方（`ping`）可能拿到一半新一半旧的快照。

改成单个不可变数据类 + `AtomicReference`，一次性原子替换：

```kotlin
private data class SnapshotState(
    val id: String = "", val createdAt: Long = 0L,
    val files: Long = 0L, val bytes: Long = 0L, /* ... */
) { fun toJson(): JSONObject = ... }

private val snapshot = AtomicReference(SnapshotState())
override fun ping(): String = snapshot.get().toJson().toString()
```

顺带：手写 40 次 `.put()` 建议换成 `kotlinx.serialization`，AIDL 两侧的字段就不会再对不上。

### 19. 用 250–750ms 轮询，而 AIDL 回调接口已经写好了

`MiuixDashboardActivity.kt` 里有 `delay(250)` / `delay(320)` / `delay(350)` / `delay(420)` / `delay(700)` / `delay(750)` 六种间隔的轮询循环，全项目共 34 处 `delay()`。但 `ITaskProgressCallback.aidl` 已经定义好了，说明推送通道是有的，只是没用起来。

按最激进的 250ms 算，扫描期间每秒 4 次跨进程 Binder 调用 + JSON 序列化 + 磁盘读——对续航和扫描本身的速度都是净损失。改成回调推送 + 一个 5s 的兜底轮询即可。

### 20. 19 个 shell 脚本没有 `set -eu`

包括核心的 `v2/module/cleaner.sh`、`one-pass-scan.sh`、`native-scan.sh`、`cache-snapshot-clean.sh`、`profile-snapshot-clean.sh`、`customize.sh`、`service.sh`。在一个**以 root 身份删文件**的项目里，未定义变量静默展开成空字符串是相当危险的（`rm -rf "$UNDEFINED/foo"` → `rm -rf "/foo"`）。

这些脚本里已经有 140 处 `rm -rf`/`rm -f`。虽然抽查下来变量都做了引号包裹、`deep_allowed()` 也有兜底，但 `set -u` 是几乎零成本的第二道防线。

另外 CI 里应该加 shellcheck。

### 21. 规则文件的一致性问题

- `rules.meta.env` 声明 `rules_count=4746`，实际有效行 **4,755** 条，差 9；
- `config/deep.rules` 内有 **33 条完全重复**的规则；
- README 里贴的 SHA-256 与文件当前值一致（`73d4c898…`），但这个值同时硬编码在 `validate-rules.py` 里，改规则要同步三处。

建议：`validate-rules.py` 改为「去重 + 排序 + 重新计算 count/SHA 并回写 `rules.meta.env`」，然后 CI 检查 `git diff --exit-code`。README 里不要贴具体 SHA，改成指向 `rules.meta.env`。

---

## 建议的执行顺序

**第一批（本周，安全相关）**
1. 修 `deep_risk()` 的分段匹配 + 补 C 单测（P0-1）
2. 删掉 11 个失效的 `pull_request_target` workflow（P0-4）
3. `customize.sh` 加 APK hash 实际校验（P0-3）
4. 给 19 个脚本补 `set -eu`（P2-20）

**第二批（工程基线）**

5. 提交 Gradle Wrapper（P1-8）
6. 71 → 4 个 workflow，release 改 tag 触发（P1-7）
7. 删死代码：4 个未注册 Activity（P1-9）
8. 建 `src/test/`，先覆盖 `PathCompat` / `deep_risk` / `SchedulerRepository`（P1-5）
9. 版本号收敛到 `module.prop` 单一来源（P1-6）

**第三批（体验与覆盖面）**

10. 文档三处版本对齐 + CHANGELOG 合并（P2-17）
11. 抽取 strings.xml + 英文翻译（P2-14）
12. native 引擎补 armeabi-v7a（P2-15）
13. 轮询改回调（P2-19）
14. Dashboard 状态迁到 ViewModel（P1-11）

---

## 最后

需要澄清的是：这份报告列的问题多，不代表项目质量差。清理类 root 模块最容易出的事故是"删了不该删的"，而白泽在**架构层面**对此的防御（路径白名单、元数据双校验、不跟随软链接、风险分级、扫描授权一次性失效）比同类项目扎实得多。P0-1 恰恰是因为其他环节都做对了，才显得那一处子串匹配格外突兀——它是整条安全链上唯一一个能被绕过的环节。

工程化的欠账（71 个 workflow、0 单测、三代 UI 并存）本质上是快速迭代留下的痕迹，v2.5.7 之后花两三周做一次集中清理，之后的维护成本会显著下降。
