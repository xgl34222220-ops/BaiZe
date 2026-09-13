# 参与贡献

欢迎提交问题、设备兼容反馈、性能改进和清理规则。

当前公开版本线从 **重构版 1.0.0** 开始，旧版本号不再用于新提交或正式发布。

## 提交前检查

```sh
bash v2/tests/run-all.sh                     # 完整核心回归
sh v2/scripts/sync-version.sh --source-only --check
python3 v2/scripts/validate-rules.py --check # 规则库一致性
sh scripts/check.sh                          # 基础 Shell / 模块静态检查
cd v2 && ./gradlew :app:test                 # Android JVM / UI 测试
```

涉及 Android 界面的改动还应至少执行对应的 assemble / lint，并检查窄屏、大字体、深色模式和无实时玻璃效果时的降级表现。

## 规则贡献要求

新增或修改清理规则时，请提供：

1. 对应应用或系统组件；
2. 路径用途；
3. 目标是否可再生；
4. 建议风险等级；
5. 测试设备、Android 版本和 Root 方案；
6. 规则来源。

不得把下载、文档、相册、影音、聊天媒体、数据库、SharedPreferences、密钥、草稿、备份和 OBB 主体作为默认定时清理目标。

规则可以写成 `路径|risk` 显式标注等级。不确定时**不要**标注 `low`。无法可靠判定风险的项目宁可进入高风险人工确认，也不能为了“清得更多”降低安全等级。

改完规则后运行：

```sh
python3 v2/scripts/validate-rules.py
```

并把重新生成的规则元数据一起提交。

## 风险等级判定

修改 `deep_risk()` 或其他风险相关逻辑时，必须同步更新对应回归测试。

风险判定直接决定哪些项目可以批量选择、哪些项目能进入自动任务，因此不能只验证“是否扫描到”，还必须验证“是否允许清理”。高风险和关键风险不得因为定时任务、批量选择或 UI 改动绕过人工确认与保护限制。

## 白名单与清理结果

对白名单、扫描快照或清理结果模型的改动需要保证：

- 读取失败不能当作空白名单；
- Root 断线或结果未确认时不能误写；
- 移除白名单只取消保护记录，不删除对应文件；
- 白名单变化后旧扫描授权必须失效；
- 只有实际删除成功的内容才计入释放空间。

## 正式版本规则

公开版本从重构版重新起算：

**1.0.0 → 1.1.1 → 2.0.0 → 2.2.2 → 3.0.0 → 3.3.3 → 4.0.0 → 4.4.4 …**

`module.prop` 是显示版本和内部 `versionCode` 的源头。内部构建号独立递增，不跟随显示版本回退。

准备下一版本时使用：

```sh
sh v2/scripts/sync-version.sh --source-only --next
sh v2/scripts/sync-version.sh --source-only --check
```

然后新增对应的：

```text
docs/releases/refactor-vX.Y.Z.md
```

正式 Release 标签统一为：

```text
refactor-vX.Y.Z
```

## 正式发布流程

当前唯一正式发布入口是 `.github/workflows/refactor-release.yml`，由 `.github/refactor.publish` 触发，不手工创建正式 tag，也不提前修改 OTA。

正式发布流程会依次完成：

1. 校验发布请求与源码版本；
2. 同步版本元数据；
3. 构建原生引擎并执行 Android 测试、lint 与正式签名；
4. 使用重构版 1.0.0 作为升级链基线执行 Android 16 安装 / 覆盖升级验证；
5. 运行完整模块回归并打包模块；
6. 校验模块内置 APK 与独立 APK 字节一致、签名证书和 SHA-256 正确；
7. 发布 GitHub Release，并同步 `downloads/releases/refactor-vX.Y.Z/` 镜像；
8. 重新下载已发布文件进行字节级核对；
9. 只有正式产物与镜像完全一致后，才提交 `update.json` 启用 OTA。

不要手工提前编辑 `update.json`，也不要用 Debug 签名代替正式签名。任何签名、哈希、升级、回归或发布镜像校验失败都应停止发布。

## 提交规范

- 提交信息说明**为什么**修改，而不只写改了什么；
- 涉及清理安全边界时说明影响范围与回归覆盖；
- UI 改动说明对应页面、交互和降级行为；
- 不提交运行日志、设备隐私、账号信息或包含个人路径的审计数据。
