# 白泽 v2

白泽是面向 ARM64 Android 8.0+、Magisk、KernelSU 与 APatch 的原生清理模块。App 通过 libsu RootService 调用模块引擎，不依赖 WebUI。

## 当前主链路

- `MiuixDashboardActivity`：首页与任务入口。
- `ScanWorkbenchActivity`：安全扫描、快照确认和直接清理；确认清理不会重新扫描。
- `ProfileActivity`：深度规则和卸载残留任务。
- Root 模块：不可变快照、白名单、实际删除、累计账本和独立分组调度。

## 安全边界

- 不跟随符号链接，不跨挂载点。
- 快照校验设备、inode、大小、mtime/ctime、规则 SHA 与白名单 SHA，30 分钟后失效。
- 定时深度任务在扫描和删除两端都强制只允许 low/medium；配置不能放宽该边界。
- 下载、文档、照片视频、数据库、SharedPreferences、草稿、备份和 OBB 默认保护。
- 所有统计使用实际删除结果；模块路径通过幂等事件账本统一累计。
- 类型化排除支持按工具作用域和 Android 用户保护路径/应用；关键内置保护不可由用户输入绕过。
- 存储分类、大文件和精确重复文件分析默认只读，不自动删除个人文件。

## 调度说明

Root scheduler 保存到期时间、公平队列和任务状态。Android WorkManager 是系统唤醒桥，实际执行时间仍受 Doze、厂商省电策略和用户条件影响，因此界面显示的是最早执行时间而非精确闹钟时间。

## 构建

需要 JDK 17、Android SDK 36、Gradle 8.13、Python 3、C 编译器和 zip：

```bash
cd v2
gradle --no-daemon :app:assembleRelease
python3 scripts/validate-rules.py
sh scripts/package-module.sh
```

模块打包脚本会运行关键 Root、安全快照和调度回归测试。

正式发布只允许手动工作流执行，要求两个不同 ARM64 真机的完整安全检查，其中一个必须是 Android 8 / API 26；已有 tag 不会覆盖或移动。
