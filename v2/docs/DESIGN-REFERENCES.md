# 清理器设计对照

本轮加固参考的是公开设计与一手文档，不复制第三方规则或实现代码。

- [SD Maid SE](https://github.com/d4rken-org/sdmaid-se)：扫描结果先展示、用户选择后删除；CorpseFinder、StorageAnalyzer、Deduplicator 分成独立工具；持久排除优先于删除。
- [SD Maid SE Setup](https://github.com/d4rken-org/sdmaid-se/wiki/Setup)：应用清单不完整时停用应用相关工具，避免把“看不见的已安装应用”误判为卸载残留。白泽采用同样的失败关闭策略，并扩展到每个 Android 用户。
- [SD Maid SE releases](https://github.com/d4rken-org/sdmaid-se/releases)：近期专门修复递归删除跟随符号链接，印证白泽需要在扫描和执行两端都复核 symlink/mount 边界。
- [Android WorkManager / alarms](https://developer.android.com/develop/background-work/services/alarms)：周期工作最短 15 分钟且运行时间受系统优化影响。白泽用周期任务做 watchdog，用一次性任务逼近 Root 计算出的下一到期时间，界面不承诺精确时刻。
- [Magisk module guide](https://topjohnwu.github.io/Magisk/guides.html)：模块生命周期采用标准 `customize.sh`、`service.sh`、`action.sh`、`uninstall.sh`，安装脚本按 `ARCH` 与 `API` 拒绝不支持设备。
- [Czkawka](https://github.com/qarmin/czkawka) 与 [find-duplicates](https://github.com/twpayne/find-duplicates)：重复检测先按大小分组，再只对碰撞组读取内容并缓存不变文件的哈希。白泽进一步用元数据键、首尾快速哈希和完整 SHA-256，最终仍只展示不自动删除。

由这些对照落地的原则是：扫描与变更分离、身份而非路径授权、清单不完整即停止、排除规则是结构化事实源、后台时间只表述为“最早检查”、个人文件分析默认只读。
