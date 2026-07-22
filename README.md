<div align="center">

# 白泽 BaiZe

**Android Root 缓存、日志与存储垃圾清理模块**

适用于 Magisk、KernelSU 与 APatch

![Version](https://img.shields.io/badge/version-v2.4.1-1677ff)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android)
![License](https://img.shields.io/badge/license-GPL--3.0-orange)

</div>

白泽以准确统计、分级保护和可审计清理为核心。项目保留完整深度规则库，但把“扫描发现”与“删除授权”分离：低、中风险内容可参与安全定时，高风险与关键风险内容默认只扫描，完整深度清理必须由用户手动确认。

v2.4.1 进一步固定“扫描一次、按原计划清理”的执行方式：扫描结果会生成不可变清理计划，App 或 Root 服务重启后仍可恢复；停止、异常中断或部分失败后只继续剩余项目，不会重新全盘扫描。

## 界面预览

<p align="center">
  <img src="docs/images/home.png" width="46%" alt="白泽首页">
  <img src="docs/images/rules.png" width="46%" alt="白泽规则页">
</p>

## 核心功能

- 应用内部缓存、`code_cache` 与 `Android/data` 外部缓存清理
- 空文件、空目录、隐藏垃圾、系统日志和残留碎片清理
- 4,746 条深度规则分级扫描与安全执行
- 卸载应用残留扫描，清理前再次检查应用是否重新安装
- 每组任务独立周期或每日固定时间
- 息屏、充电、系统空闲、电量、温度和运行时长条件
- 不可变扫描快照、清理计划 ID、规则 SHA、白名单与单文件上限保护
- 清理停止、App 回收或 Root 服务重启后的事务断点恢复
- 扫描、授权、处理、清理、变化、保护、部分失败与实际释放空间分别统计
- 原生白泽 App 提供操作、白名单、定时任务、审计历史和文件归类入口
- 深度扫描实时进度、慢目录限时保护与缓存根目录合并扫描

## 安全边界

- 定时深度任务永不执行高风险与关键风险规则
- 完整深度清理必须先扫描，并在 30 分钟内手动确认
- 点击清理只消费已保存快照，快照失效时不会自动重新扫描
- 扫描后新增或发生变化的文件不会被旧计划删除
- 不跟随软链接，不允许清理模块、Root 配置和系统关键路径
- 默认保护下载、文档、相册、影音、数据库、SharedPreferences、密钥、草稿、备份与 OBB 主体
- `config/deep.rules` 保留 4,746 条有效规则，SHA-256：

```text
73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c
```

## 安装

1. 从 GitHub Releases 下载最新的 `BaiZe-v2.4.1-Module.zip`。
2. 在 Magisk、KernelSU 或 APatch 中选择该 ZIP 安装。
3. 安装脚本会更新模块内置的白泽 App；安装失败时也可手动安装 ZIP 内的 `app/baize.apk`。
4. 重启设备后打开白泽 App，完成 Root 授权。
5. 第一次清理前先执行智能扫描并检查候选与风险统计。

## 从源码构建

需要 JDK 17、Android SDK 36、NDK 27、Gradle 8.13、`sh`、`zip` 和 `sha256sum`：

```sh
cd v2
sh scripts/build-native.sh
gradle --no-daemon :app:assembleRelease
sh scripts/package-module.sh
```

正式 Release 构建必须提供项目签名环境变量，禁止回退到 Debug 签名。生成文件位于 `v2/dist/`。

## 文档

- [v2.4.1 更新说明](RELEASE_NOTES_v2.4.1.md)
- [v2.4.0 更新说明](RELEASE_NOTES_v2.4.0.md)
- [详细使用说明](docs/README-detailed.md)
- [参与贡献](CONTRIBUTING.md)
- [安全说明](SECURITY.md)
- [来源与致谢](NOTICE.md)

## 兼容性

- Android 8.0+
- Magisk
- KernelSU
- APatch
- ARM64 原生扫描与清理引擎

模块不修改 `/system`，也不依赖 KernelSU 元模块。不同 ROM 对通知、Doze、电池状态和外部存储权限的实现可能不同，提交问题时请附带经过脱敏的环境信息。

## 许可证

本项目以 GPL-3.0 许可证发布。第三方项目、规则来源、名称和资源仍遵循其各自许可证，详见 [NOTICE.md](NOTICE.md)。

作者：**惜故里丶**
