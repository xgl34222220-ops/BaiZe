# 白泽 v2.6.0

本次正式版重点提升清理安全性、稳定性与工程可靠性。

- 深度清理风险判定改为路径分段匹配，减少误判风险，并支持可配置风险上限。
- Root 扫描快照改为不可变对象与 AtomicReference，消除并发撕裂读。
- 恢复断点续清相关注册并清理上一代 UI/Activity 遗留。
- 支持多 ABI，完善 ProGuard、Gradle Wrapper 与 JVM 单元测试。
- 清理并去重规则库，深度规则与元数据保持一致。
- 收敛 CI/发布体系，增加 APK 完整性、Shell、Native 与回归校验。
- 接入 detekt、ktlint，并建立国际化基础设施与迁移工具。
