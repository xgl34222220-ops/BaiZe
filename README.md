<div align="center">

# 白泽 BaiZe

**Android Root 缓存、日志与存储垃圾清理模块**

适用于 Magisk、KernelSU 与 APatch

![Version](https://img.shields.io/badge/version-v1.1.0--Beta3-1677ff)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android)
![License](https://img.shields.io/badge/license-GPL--3.0-orange)

</div>

白泽以准确统计、分级保护和可审计清理为核心。项目保留完整深度规则库，但把规则存在与删除权限分离：低、中风险内容可参与安全定时，高风险与关键风险内容默认只扫描，完整深度清理必须由用户手动确认。

## 核心功能

- 应用内部缓存、`code_cache` 与 `Android/data` 外部缓存清理
- 空文件、空目录、隐藏垃圾、系统日志和残留碎片清理
- 4,746 条深度规则分级扫描与安全执行
- 卸载应用残留扫描，清理前再次检查应用是否重新安装
- 每组任务独立周期或每日固定时间
- 息屏、充电、系统空闲、电量、温度和运行时长条件
- 扫描快照、规则 SHA 校验、白名单与单文件上限保护
- 实际删除后复核，通知和累计统计只记录真正释放的空间
- WebUI 审计报告、任务历史、日志复制和原子配置保存
- 一次共享存储遍历同时分类空项目、隐藏垃圾与碎片，减少重复扫描
- 清理完成通知通道诊断、累计释放量和累计耗时展示

## 安全边界

- 定时深度任务永不执行高风险与关键风险规则
- 完整深度清理必须先扫描，并在 30 分钟内手动确认
- 扫描授权使用一次后失效
- 不跟随软链接，不允许清理模块、Root 配置和系统关键路径
- 默认保护下载、文档、相册、影音、数据库、SharedPreferences、密钥、草稿、备份与 OBB 主体
- `config/deep.rules` 保留 4,746 条有效规则，SHA-256：

```text
73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c
```

## 安装

1. 从 GitHub Releases 下载最新 ZIP。
2. 在 Magisk、KernelSU 或 APatch 中选择该 ZIP 安装。
3. 重启设备。
4. KernelSU / APatch 可直接打开 WebUI；Magisk 可使用模块 Action 按钮和定时服务。
5. 第一次完整深度清理前，先执行深度扫描并检查审计报告。

## 从源码构建

需要 `sh`、`zip` 和可选的 `sha256sum`：

```sh
sh scripts/check.sh
sh scripts/build.sh
```

生成文件位于 `dist/`。

## 文档

- [详细使用说明](docs/README-detailed.md)
- [v1.1.0-Beta3 更新日志](CHANGELOG-v1.1.0-Beta3.md)
- [参与贡献](CONTRIBUTING.md)
- [安全说明](SECURITY.md)
- [来源与致谢](NOTICE.md)

## 兼容性

- Android 8.0+
- Magisk
- KernelSU
- APatch

模块不修改 `/system`，也不依赖 KernelSU 元模块。不同 ROM 对通知、Doze、电池状态和外部存储权限的实现可能不同，提交问题时请附带经过脱敏的环境信息。

## 许可证

本项目以 GPL-3.0 许可证发布。第三方项目、规则来源、名称和资源仍遵循其各自许可证，详见 [NOTICE.md](NOTICE.md)。

作者：**惜故里丶**
