# 参与贡献

欢迎提交问题、设备兼容反馈和清理规则。

## 提交前检查

```sh
bash v2/tests/run-all.sh                     # 全量回归
sh v2/scripts/sync-version.sh --check        # 版本一致性
python3 v2/scripts/validate-rules.py --check # 规则库一致性
sh scripts/check.sh                          # v1 兼容引擎静态检查
cd v2 && ./gradlew :app:testDebugUnitTest    # JVM 单元测试
```

CI 会跑同一套检查，本地过了基本就不会在 CI 上炸。

## 规则贡献要求

新增或修改清理规则时，请提供：

1. 对应应用或系统组件；
2. 路径用途；
3. 目标是否可再生；
4. 建议风险等级；
5. 测试设备、Android 版本和 Root 方案；
6. 规则来源。

不得把下载、文档、相册、影音、聊天媒体、数据库、SharedPreferences、
密钥、草稿、备份和 OBB 主体作为默认定时清理目标。

规则可以写成 `路径|risk` 显式标注等级。不确定时**不要**标注 `low`——
不标注的规则会走路径推断，推断不出来会落到 `high`（只扫描），这是安全的默认。

改完规则后运行 `python3 v2/scripts/validate-rules.py`
重新生成 `config/rules.meta.env` 的条数与 SHA，并一起提交。

## 风险等级判定

修改 `deep_risk()` 或风险相关逻辑时，必须同步更新
`v2/tests/test-deep-risk-classification.c` 中的断言。

这个函数决定哪些路径会被定时任务自动删除，是整个项目最需要谨慎的地方。
历史上它用 `strstr` 做子串匹配，导致 `/nfc/logo`、`/login-identifier`
这类用户数据被判为 low 并自动删除——回归测试就是为了防止这类问题重现。

## 版本发布

版本号以 `module.prop` 为唯一来源：

```sh
# 准备源码版本，保留上一版 OTA，防止用户下载尚未生成的包。
sh v2/scripts/sync-version.sh --source-only --set v2.8.3
sh v2/scripts/sync-version.sh --source-only --check
```

补充对应的 `RELEASE_NOTES_vX.Y.Z.md`，运行提交前检查并合并到 `main`。
当前正式发布入口是 `.github/workflows/stable-release.yml`，不是推送 tag：

1. 将已验证、已合并的源码完整 SHA 写入 `.github/release.publish`，提交并推送到 `main`。
2. 工作流校验源码版本，构建原生引擎，运行 App 测试和 lint，使用正式密钥签名并校验证书，然后跑全量模块回归。
3. 工作流将已校验的 APK、模块 ZIP、SHA256 和签名证书发布到 `downloads` 分支和 GitHub Release；版本 tag 指向指定源码 SHA。
4. 工作流重新下载正式 Release，对照本次产物和已推送镜像的 APK/ZIP 字节及 SHA256，确认源码版本和发布指针未改变，然后运行 `sync-version.sh` 提交 `update.json`，最后执行完整版本一致性检查。若该步骤因 `main` 并发更新而失败，人工重新验证产物和当前版本后再运行同步，不得提前推进 OTA。

不要手工编辑 `update.json`、`build.gradle.kts` 里的版本号；它们由
`sync-version.sh` 生成。签名缺失或校验失败时停止发布，不得用 Debug 签名替代。

## 提交规范

- 提交信息用中文，说明**为什么**改，而不只是改了什么
- 涉及安全边界的改动，在提交信息里写明影响范围
- 请勿提交运行日志、设备隐私信息或包含个人路径的审计报告
