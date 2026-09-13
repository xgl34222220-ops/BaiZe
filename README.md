<div align="center">

# 白泽 BaiZe

**Android Root 智能清理模块 + 原生 App**

适用于 Magisk、KernelSU 与 APatch

![Version](https://img.shields.io/badge/重构版-1.0.0-2364db)
![Build](https://img.shields.io/badge/build-30003-6f42c1)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android)
![License](https://img.shields.io/badge/license-GPL--3.0-orange)

[下载正式版](https://github.com/xgl34222220-ops/BaiZe/releases/tag/refactor-v1.0.0)

</div>

白泽是一套面向 Root Android 设备的清理工具，目标不是“扫得越多越好”，而是把 **扫描、风险判断、用户确认、实际删除、结果复核** 做成一条完整且可追踪的流程。

**当前公开版本线从「重构版 1.0.0」重新开始，1.0.0 就是第一个正式版本。**

## 重构版 1.0.0

本次重构把 App、模块、清理流程和界面统一到新的正式版本线：

- 洛书同源的 MIUIX / 液态玻璃视觉：首页、设置页与悬浮底栏统一设计语言。
- 扫描结果按应用和文件展示，不再只给一个模糊的总大小。
- 明确区分实时扫描、历史结果、过期快照与未完成任务，避免把旧记录误认为当前垃圾。
- 常驻“全选低、中风险”和“仅选中风险”，高风险项目坚持逐项选择与再次确认。
- 新增统一白名单管理，可管理应用保护和手动路径保护。
- 白名单读取失败或 Root 连接异常时禁止误写，不把“读取失败”当成“空名单”。
- 清理记录、实际释放空间与任务结果继续保留，只有真正删除成功的内容才计入统计。
- 模块内置 App 与独立 APK 使用同一正式签名，支持直接覆盖升级。

## 核心功能

### 垃圾扫描与清理

- 应用内部缓存、`code_cache` 与外部缓存
- 系统日志、临时文件、空文件、空目录与隐藏垃圾
- 卸载应用残留与残留碎片
- APK / APKS / XAPK / APKM 安装包扫描与保留期清理
- 4,714 条深度规则分级扫描
- 深度扫描实时进度、慢目录限时与扫描快照
- 清理中断后的断点续清与结果复核

### 文件归类

- 对下载、接收、附件、导出等文件进行分类整理
- 分类规则与垃圾清理规则分离，避免把正常文件当垃圾
- 操作结果可追踪，异常时不静默吞掉文件

### 自动任务

- 每个任务可独立设置周期或每日固定时间
- 支持充电、息屏、系统空闲、电量、温度与运行时长条件
- 自动任务只执行允许的风险等级，不会因为定时配置而绕过高风险保护

### 白名单与保护

白泽提供两层保护：

- **应用保护**：保护某个应用相关的清理内容
- **路径保护**：保护指定路径，支持逐条管理和移除

移除白名单只会取消保护记录，**不会删除对应应用或文件**。对白名单进行修改后，旧扫描快照会失效，需要重新扫描，避免拿旧授权继续清理。

## 风险分级

| 等级 | 默认行为 |
|---|---|
| `low` | 可批量选择，可进入安全自动任务 |
| `medium` | 可批量选择，但仍受规则与白名单保护 |
| `high` | 默认不选，必须手动逐项选择并再次确认 |
| `critical` | 关键数据保护，默认禁止自动清理 |

白泽把“发现某个文件”和“允许删除某个文件”分开处理。规则命中不等于直接删除，高风险和关键数据始终经过额外限制。

## 安全边界

- 不跟随软链接进行跨路径删除
- 模块自身、Root 配置和关键系统路径禁止清理
- 删除前重新检查文件状态，内容变化后会跳过
- 默认保护下载、文档、相册、影音、数据库、SharedPreferences、密钥、草稿、备份与 OBB 主体
- 自动深度任务不会执行 `high` / `critical`
- 完整深度清理必须基于有效扫描结果和用户确认
- 扫描、清理、统计分离，只有实际删除成功才计入释放空间

## 安装

### 完整安装

1. 打开 [GitHub Releases](https://github.com/xgl34222220-ops/BaiZe/releases/tag/refactor-v1.0.0)。
2. 下载 `BaiZe-v1.0.0-Module.zip`。
3. 在 Magisk、KernelSU 或 APatch 中刷入模块。
4. 重启设备。
5. 打开白泽 App，授予所需 Root 权限后开始扫描。

### 只更新 App

可以直接安装 `BaiZe-v1.0.0.apk`。完整功能仍建议同时使用对应版本模块。

模块安装时会校验内置 APK 的 SHA-256；正式模块中的 App 与 Release 提供的独立 APK 字节一致。

## 在线更新

白泽使用 `update.json` 提供模块在线更新信息，正式 ZIP 同步到 `downloads` 分支的稳定 Raw 镜像。

Root 管理器检测到新版本后可直接更新；GitHub Releases 同时保留独立 APK、模块 ZIP、SHA-256 与签名证书。

## 正式版本规则

从重构版开始只使用这一套版本序列：

**1.0.0 → 1.1.1 → 2.0.0 → 2.2.2 → 3.0.0 → 3.3.3 → 4.0.0 → 4.4.4 …**

显示版本与内部 `versionCode` 分开管理，内部构建号持续递增，用于保证覆盖升级和在线更新判断正常。

Release 标签统一使用：

```text
refactor-v1.0.0
refactor-v1.1.1
refactor-v2.0.0
...
```

## 兼容性

- Android 8.0+
- Magisk / KernelSU / APatch
- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

不同 ROM 对 Root 服务、Doze、存储权限和后台任务的实现不同。自动化测试不能替代所有真机环境；出现问题时建议附上系统版本、Root 方案、白泽版本和脱敏后的错误信息。

## 从源码构建

主要 Android 与模块源码位于 `v2/` 目录。目录名属于源码结构，不代表公开版本号。

需要 JDK 21、Android SDK、NDK 27.2 与对应 Build Tools：

```sh
cd v2
sh scripts/build-native.sh
./gradlew :app:test :app:assembleRelease
sh scripts/package-module.sh
```

常用检查：

```sh
bash v2/tests/run-all.sh
sh v2/scripts/sync-version.sh --source-only --check
python3 v2/scripts/validate-rules.py --check
```

正式发布由 GitHub Actions 完成签名、单元/界面测试、lint、Android 模拟器安装与覆盖升级验证、核心回归、模块内置 APK 一致性、SHA-256、Release 镜像和 OTA 校验。

## 文档

- [重构版 1.0.0 发布说明](docs/releases/refactor-v1.0.0.md)
- [详细使用说明](docs/README-detailed.md)
- [更新日志](CHANGELOG.md)
- [参与贡献](CONTRIBUTING.md)
- [安全说明](SECURITY.md)
- [来源与致谢](NOTICE.md)

## 许可证

本项目以 GPL-3.0 许可证发布。第三方项目、规则来源、名称和资源继续遵循各自许可证，详见 [NOTICE.md](NOTICE.md)。

作者：**惜故里丶**
