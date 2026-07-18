# 白泽 v2 Alpha 21

白泽是面向 Magisk / KernelSU / APatch 的原生 Android 清理模块。App 通过 libsu RootService 调用模块清理引擎，不依赖 WebUI。

## Alpha 21

- MIUIx 风格的渐变环境背景、半透明玻璃卡片和悬浮液态底栏；只做轻量属性动画，避免长页面滚动卡顿。
- 首页直接显示 `/data` 分区的可用、已用和总容量，全部使用自动换算的 GB / MB 单位。
- 应用缓存、空文件、规则垃圾、残留碎片和深度安全项可分别开关、分别设置 1～720 小时周期。
- 手动清理不再重写计划配置；计划页只更新调度字段，不覆盖清理范围和用户保留策略。
- 累计次数、释放空间、普通文件、空文件、空目录、碎片与总耗时永久保存；列表仅保留最近 100 次。
- App 手动任务使用 Android 原生通知渠道，定时任务继续由模块脚本通知。
- 内置 4,714 条通过校验的有效深度路径规则；危险、重复或格式无效规则不会进入删除链。

## 安全边界

- 不跟随符号链接，不跨挂载点。
- 白名单、聊天记录、下载、照片视频、数据库和应用数据目录受到保护。
- 超过用户设置上限的单文件只统计、不删除。
- 深度清理按风险分级；高风险候选项不会后台自动删除。

## 构建

需要 JDK 17、Android SDK 36 和 Gradle 8.13：

```bash
cd v2
gradle --no-daemon :app:assembleDebug
sh scripts/package-module.sh
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `dist/BaiZe-v2-Alpha21-Module.zip`
