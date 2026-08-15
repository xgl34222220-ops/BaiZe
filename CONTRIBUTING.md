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
sh v2/scripts/sync-version.sh --set v2.6.0   # 同步到所有相关文件
git commit -am "chore: v2.6.0"
git push
git tag v2.6.0 && git push origin v2.6.0     # 触发 release workflow
```

不要手工编辑 `update.json`、`build.gradle.kts` 里的版本号，
它们由 `sync-version.sh` 生成，CI 会校验一致性。

## 提交规范

- 提交信息用中文，说明**为什么**改，而不只是改了什么
- 涉及安全边界的改动，在提交信息里写明影响范围
- 请勿提交运行日志、设备隐私信息或包含个人路径的审计报告
