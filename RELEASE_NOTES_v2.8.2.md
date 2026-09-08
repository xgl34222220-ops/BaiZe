# 白泽 v2.8.2

修复 Android 16 上 Root 服务连上后立即断开的问题。

2.8.1 的真机 Root 堆栈显示，MediaScannerConnection 在 android.bg 线程获取 media provider 时抛出 SecurityException：独立 Root 进程没有 ActivityManager 登记的 IApplicationThread。这不是 Root 授权失败，也不是旧的主题崩溃。

- Root 媒体刷新改用隔离的系统 content call scan_file 命令，经 external provider 入口调用；不再在 Root 进程使用 App 媒体扫描接口。
- 媒体刷新始终在后台串行执行，提供命令超时；失败保留 pending/inflight 队列，收到明确成功回复后才确认完成。
- 校验命令的实际回复，避免 content 命令报错但返回退出码 0 时误删队列；路径使用独立参数，支持空格、中文和多用户存储路径。
- 保留 Root 崩溃诊断及之前的清理功能。

依据：AOSP MediaProvider.getResultForScanFile 与 content 命令的 getContentProviderExternal / CallCommand 实现。
https://github.com/aosp-mirror/platform_packages_providers_mediaprovider/blob/main/src/com/android/providers/media/MediaProvider.java
https://github.com/aosp-mirror/platform_frameworks_base/blob/main/cmds/content/src/com/android/commands/content/Content.java

请同时更新模块和 App。修正针对已收到的真机异常，最终稳定性仍需设备验证。
