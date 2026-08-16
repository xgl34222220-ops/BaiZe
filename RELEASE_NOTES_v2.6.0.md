# 白泽 v2.6.0

## 在线更新

已安装旧版本时，直接在 Magisk、KernelSU 或 APatch 的模块更新页面刷新并更新到 v2.6.0 即可。

本次修复了“能检测到 v2.6.0，但点击更新后下载失败”的问题：

- 在线更新包改为 GitHub Raw 稳定镜像，不再依赖 GitHub Release 附件的多级重定向。
- Raw 镜像与正式 Release 中的 `BaiZe-v2.6.0-Module.zip` 为同一个正式模块包，SHA-256 完全一致。
- 更新页面只显示当前版本说明，不再把整份历史 CHANGELOG 当作在线更新说明。
- GitHub Releases 继续保留正式 ZIP、独立 APK、SHA-256 与签名证书，供手动下载和归档。

更新完成后按 Root 管理器提示刷入模块并重启即可。

## v2.6.0 主要更新

- 深度清理风险判定改为路径分段匹配，减少误判风险，并支持可配置风险上限。
- Root 扫描快照改为不可变对象与 AtomicReference，消除并发撕裂读。
- 恢复断点续清相关注册并清理上一代 UI/Activity 遗留。
- 支持多 ABI，完善 ProGuard、Gradle Wrapper 与 JVM 单元测试。
- 清理并去重规则库，深度规则与元数据保持一致。
- 收敛 CI/发布体系，增加 APK 完整性、Shell、Native 与回归校验。
- 接入 detekt、ktlint，并建立国际化基础设施与迁移工具。
