# 白泽 v2 Alpha 33

白泽是面向 Magisk / KernelSU / APatch 的原生 Android 清理模块。App 通过 libsu RootService 调用模块清理引擎，不依赖 WebUI。

当前开发分支：`v2-alpha33-ui-step1`。

## Alpha 33：双皮肤第一阶段

- 新增 `UiStyle.MATERIAL / UiStyle.MIUIX`，使用 Preferences DataStore 持久化，并由全局 `AppearanceViewModel` 暴露。
- 主题设置新增“界面风格”，用户可在 Material 与 Miuix 两档之间切换。
- 新增统一 `BaiZeTheme`：两套皮肤共用种子色、柔和/鲜艳/中性取色风格、三态明暗模式和纯黑开关。
- Material 主题通过 MaterialKolor 生成 Material 3 色板；Miuix 主题保留更大的数字、字重和圆角层级。
- 首页通过 `HomeRoute` 分流为 Material 与 Miuix 两份实现，业务状态与操作回调完全共用。
- 本阶段不修改 RootService、扫描、清理、历史记录和 Shell 清理逻辑。

## Alpha 32

- 历史记录新增垃圾分类排行与涉及应用排行，点击任务可展开查看实际清理内容。
- 兼容旧版历史格式；旧记录继续可读，新记录保存完整分类和应用明细。

## Alpha 31

- 修复扫描完成后首页被强制滚动、顶部标题只剩半截的问题。
- 普通清理、垃圾扫描和快照清理使用独立待执行状态，不再在 Root 重连后误走完整清理。
- 扫描引擎首次连接完成后自动继续原扫描，无需重复点击。
- 扫描快照有效时首页固定显示“扫描结果已就绪”和“快照就绪”，不再与“正在连接/未就绪”互相矛盾。
- RootService 断开后自动恢复连接，同时保留有效快照和正在执行的任务状态。

## Alpha 30

- 新增 APK/APKS/XAPK/APKM 安装包扫描，覆盖 Download、QQ、微信和常见浏览器下载目录，并支持保留天数设置。
- 记录页增加其他垃圾分类，安装包、日志、临时文件和碎片不再只混在总数中。
- 结果卡片改用实心 Surface，移除透明叠层造成的白色矩形渲染异常。
- AMOLED 开关真正启用纯黑背景、纯黑卡片和纯黑底栏，并关闭背景彩色光晕。
- 保持 Alpha 29 的单 RootService 一键清理与按应用明细链路。

## Alpha 29

- 清理结果按应用与垃圾分类记录实际删除文件、实际释放空间、未清理数量和示例路径。
- 重叠缓存与扩展规则使用规范化路径去重，避免重复删除和重复统计。
- 记录页读取应用真实图标；图标在后台线程加载并使用有界内存缓存，读取失败自动显示占位图。
- 应用结果卡片使用简洁分类标签，点击后展开内部缓存、外部缓存、WebView、空文件等分类明细。
- 增加本次清理汇总、最大垃圾来源，并修复悬浮底栏遮挡最后一张结果卡片的问题。
- 保持 Alpha 28 已验证的一键 Root 清理主链路，不重做首页连接逻辑。

## 安全边界

- 不跟随符号链接，不跨挂载点。
- 白名单、聊天记录、下载、照片视频、数据库和应用数据目录受到保护。
- 超过用户设置上限的单文件只统计、不删除。
- 深度清理按风险分级；高风险候选项不会后台自动删除。

## 构建

需要 JDK 17、Android SDK 36 和 Gradle 8.13；执行规则校验时还需要 Python 3：

```bash
cd v2
gradle --no-daemon :app:assembleDebug
sh scripts/package-module.sh
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `dist/BaiZe-v2-Alpha33-Module.zip`
