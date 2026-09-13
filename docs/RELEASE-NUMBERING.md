# 白泽重构线正式发布规则

2026-09-13 用户指定：1.0.0 → 1.1.1 → 2.0.0 → 2.2.2 → 3.0.0 → 3.3.3 → …。一般形式为 n.0.0 → n.n.n → (n+1).0.0，不自行插入 1.0.1 等版本。

重构线使用 refactor-v<版本> 标签，不覆盖旧 v1/v2/v3 标签；App 显示版本仍为指定数字。module.prop 是单一来源。1.0.0 的内部 versionCode 为 30003，此后每次正式版取大于当前源码和 OTA 的构建号，禁止重置成 10000。

准备下一版本：`sh v2/scripts/sync-version.sh --source-only --next`，校验：`sh v2/scripts/sync-version.sh --source-only --check`。更新 docs/releases/refactor-v<版本>.md。

将已审阅的源码提交 SHA 写入 .github/refactor.publish 触发 Refactor Formal Release。工作流仅同步并提交白名单内的版本元数据，然后构建并测试最终提交，使用该提交创建 Release 标签。只有签名、回归、最终压缩 APK 安装启动、正式附件及镜像校验通过后才推进 OTA。

保留包名 io.github.xgl34222220.baize、模块 ID baize_v2、正式签名和配置目录。不得卸载、清数据库、重置用户规则或计划。最终包必须覆盖升级旧 30002 并验证启动，不能只测试 Debug 或软件截图。真机验收边界需如实记录。
