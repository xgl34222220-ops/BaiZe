# 清理 / 归类工作台测试候选

基线：`main` 的 `2fe7af869b9ead4385e7f68a8fbfc45849b85623`。这是独立测试分支，不是正式发布或 OTA。

## 有界验收范围

1. 安全清理：白名单解析失败停止；实时合并持久应用与路径保护；Android 同用户路径别名一致；选择的父目录包含受保护文件时整根保留。
2. 可测扫描：2–4 个受限工作线程，单根 15 秒 / 百万项目上限，总测量 90 秒预算，128 个项目或 200ms 进度；明确扫描覆盖、部分结果与首次结果时间。
3. 安全遍历：使用 API 26 `SecureDirectoryStream` 从根逐级打开，不跟随软链接；清理前校验扫描目录身份；不可用时关闭清理而非回退危险递归。
4. 结果可信：取消保留未处理项，部分成功不标记全成功；持久快照保留目录身份与应用名；历史列表可读但必须重扫后授权清理。
5. 归类闭环：扫描 → 完整分页预览来源/目标 → 类别及单项选择 → 确认移动；页面不能默认提交 `all=true`。
6. 恢复闭环：每次移动之前 fsync 意图日志，每项成功或撤销后追加状态；断线后可重读；存在同名目标绝不覆盖；跨盘复制校验完整 SHA-256 并支持停止。
7. 视觉/交互：窄屏、大字体、深浅色；历史可见、不可点击清理；中高风险筛选不改变授权规则；预览未全加载/过期/执行结果不明时不重复执行。
8. 构建交付：完整 Shell/Python/native、Android JVM/Compose、lint、签名 APK 与可选同包模块；仅 CI artifact，不更新 Releases/main/OTA。

## 检查与证据

- `bash v2/tests/test-secure-cache-tree.sh`：真实临时文件系统覆盖 10,000 文件、嵌套目录、根替换、根/祖先/子软链接、预算与取消，13 个断言。
- 云宿主单次 10,000 文件遍历约 35ms；这只是合成宿主微基准，不代表手机扫描或完整 RootService 性能。
- `ForegroundCacheSafetyTest`：严格白名单、受保护路径别名与父目录、直接 WebView 缓存路径、取消保留候选。
- `OrganizerRecoveryTest`：逐项日志恢复及半行写入、快照目录逃逸拒绝。
- `OrganizerReviewTest` / `OrganizerPreviewUiTest` / 工作台视觉测试：预览选择、历史锁定、完整覆盖说明。
- 原基线本地核心回归 57 通过；4 项因宿主无 sudo 失败、4 项缺 busybox 跳过。需要 CI 在完整环境补跑，不能将本地此轮表述为完整通过。
- CI 工作流 `.github/workflows/cleaner-workbench-test.yml` 提供最终真实测试结果、截图与签名产物。

## 明确保留的限制

- Root/SELinux/存储提供方行为及真实设备热量、耗电、跨卷移动仍需设备验收；本次不声称完成真实手机验收。
- 不支持安全目录句柄的文件系统会保留缓存并返回不完整，避免以兼容之名降低删除边界。
- 跨卷或中断情况下如果源与目标同时存在，恢复保留两份并报告冲突，不猜测删除个人文件。
- 已经移动后被用户编辑的文件不强制撤销；来源丢失、内容改变、同名冲突都会保留。
- 安装包执行间隔与保留期是两个独立条件；现有默认 30 天保留期未放宽，定时运行一次并不代表所有 APK 都应被删。
- 清理规则继续使用带风险等级的现有规则集，不为增加数字而猜测个人内容的用途。

## 参考资料和许可

只参考公开行为与 API 契约，没有复制第三方实现或规则；项目保持现有 GPL-3.0 许可。

- SD Maid [Exclusions](https://github.com/d4rken-org/sdmaid-se/wiki/Exclusions)：持久排除项
- SD Maid [SystemCleaner](https://github.com/d4rken-org/sdmaid-se/wiki/SystemCleaner)：路径规则与应用规则责任分开
- SD Maid [StorageAnalyzer](https://github.com/d4rken-org/sdmaid-se/wiki/StorageAnalyzer)：分析占用不等于可删除垃圾
- [organize](https://github.com/tfeldmann/organize)：执行前模拟、显式冲突策略
- Android [SecureDirectoryStream](https://developer.android.com/reference/java/nio/file/SecureDirectoryStream)：基于已打开目录的安全相对路径操作，API 26+
- Android [共享媒体访问](https://developer.android.com/training/data-storage/shared/media)：用户媒体操作与系统授权边界
- [Gradle 9.5.0](https://docs.gradle.org/9.5.0/release-notes.html)：将仓库 Wrapper 与已有成功 CI 版本统一，并保留官方分发 SHA-256 校验
