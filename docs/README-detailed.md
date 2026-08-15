# 白泽 详细使用说明

作者：惜故里丶。面向 Android 8.0+ 的缓存、日志与存储垃圾清理模块。

本文档说明日常使用与配置。版本历史见 [CHANGELOG.md](../CHANGELOG.md)，
项目总览见[根 README](../README.md)。

## 支持环境

- Magisk / KernelSU / APatch
- Android 8.0+
- arm64-v8a、armeabi-v7a、x86_64
- 不修改 `/system`，不依赖 KernelSU 元模块
- 首次完整深度清理前，先执行一次深度扫描并检查审计报告

v2 起主界面是原生 App，通过 libsu RootService 调用模块引擎，不再需要 WebUI。

## 风险分级

白泽把每条清理目标分成四级：

| 等级 | 含义 | 默认行为 |
|---|---|---|
| `low` | 明确的缓存、临时文件、日志 | 定时任务自动清理 |
| `medium` | 崩溃转储、调试痕迹 | 定时任务自动清理 |
| `high` | 用途不确定的应用数据 | 只扫描，需手动确认 |
| `critical` | 下载、相册、影音、数据库、备份等用户内容 | 只扫描，且需显式放开上限 |

### 等级是怎么定的

按优先级从高到低：

1. **用户覆盖** — `config/risk-overrides.conf`，格式 `绝对路径|风险等级`，
   对该路径及其所有子路径生效，多条命中取最长匹配。
2. **规则标注** — `config/deep.rules` 中写成 `路径|risk` 的行。
3. **路径推断** — 按**完整路径分段**匹配关键词。

第 3 步用的是分段匹配而不是子串匹配。也就是说
`/files/nfc/logo` 不会因为含有 `log` 就被当成日志，
`/Cacheapps2sdcard` 也不会因为含有 `cache` 就被当成缓存。

### 调整清理力度

编辑 `/data/adb/baize-v2/config.conf`：

```conf
# 定时任务允许自动删除的最高等级，默认 medium
deep_scheduled_max_risk=medium

# 手动完整深度清理允许的最高等级，默认 high
deep_manual_max_risk=high

# 高风险总开关，关闭时上面两项都会被压回 medium
deep_high_risk_enabled=0
```

无论怎么配置，**定时任务永远不会执行 high 与 critical**——
这是脚本层的硬边界，把 `deep_scheduled_max_risk` 改成 `critical` 也会被压回 `medium`。

想让定时任务更保守就设成 `low`；想让手动清理连下载和相册都纳入，
需要同时把 `deep_high_risk_enabled` 设为 `1`、`deep_manual_max_risk` 设为 `critical`。

### 保护单个路径

三种粒度，保护强度递增：

```conf
# risk-overrides.conf：升级风险等级，手动清理仍可处理
/storage/emulated/0/MyStuff|high

# risk-overrides.conf：升到 critical，需要显式放开上限才碰
/storage/emulated/0/MyStuff|critical

# whitelist.conf：彻底保护，任何清理都不碰
/storage/emulated/0/MyStuff
```

反过来，确认是缓存的目录也可以下调：

```conf
/storage/emulated/0/Android/data/com.example/webcache|low
```

改完不需要重启，下一次扫描即生效。

## 定时任务

每组任务可以独立设置周期，或统一使用每日固定时刻。

| 任务组 | 默认周期 |
|---|---|
| 应用缓存 | 24 小时 |
| 空文件与空目录 | 24 小时 |
| 应用规则 | 24 小时 |
| 残留碎片 | 72 小时 |
| 深度规则 | 168 小时（默认关闭） |
| 文件归类 | 24 小时（默认关闭） |

执行条件可叠加：息屏、充电、系统空闲、最低电量、最高温度。

「智能定时」模式下由自动驾驶控制器根据存储压力和历史清理收益动态调整间隔；
用户主动发起的一键清理不受其拦截。

## 清理档位

三个预设档位只调整普通垃圾的覆盖范围和保留天数，
**不会**修改任何定时周期，也**不会**开启高风险直删：

| 档位 | 特点 |
|---|---|
| 保守 | 只默认勾选低风险；缓存保留 3 天；不提供高风险隔离 |
| 均衡 | 默认勾选低+中风险；日志碎片保留 7 天；高风险可手动隔离 |
| 积极 | 启用 OEM 日志；碎片保留 1 天；突出建议手动隔离 |

## 安全机制

- **不跟随软链接**：全程用 `lstat`，删除用 `unlink`，符号链接只删链接本身
- **快照一致性**：删除前双次 `lstat` 并与扫描快照的 dev / ino / size / mtime / ctime 比对，
  文件在扫描后被改动过即跳过
- **扫描授权一次性**：完整深度清理必须先扫描，授权 30 分钟内有效且用一次即失效
- **路径白名单**：引擎只允许操作 `/data/data`、`/data/user`、`/data/user_de`、
  `/data/cache`、`/data/media`、`/data_mirror/data_ce` 前缀，
  并显式拒绝 `/data/adb`、`/data/app`、`/system` 等
- **单文件上限**：超过 `max_file_mb` 的文件一律保护
- **规则完整性**：`config/rules.meta.env` 记录条数与 SHA-256，运行时校验
- **隔离区**：高风险候选可移入隔离区而非直接删除，默认保留 7 天，卸载模块时自动恢复

## 目录与文件

| 路径 | 内容 |
|---|---|
| `/data/adb/modules/baize_v2/` | 模块本体 |
| `/data/adb/baize-v2/config.conf` | 用户配置 |
| `/data/adb/baize-v2/whitelist.conf` | 白名单 |
| `/data/adb/baize-v2/risk-overrides.conf` | 风险覆盖 |
| `/data/adb/baize-v2/history.tsv` | 任务历史 |
| `/data/adb/baize-v2/reports/` | 审计报告 |
| `/data/adb/baize-v2/logs/` | 运行日志 |

## 卸载

在 Root 管理器中卸载模块即可。卸载脚本会：

1. 逐个精确终止白泽后台进程（跳过卸载脚本自身与 Root 管理器父进程）
2. 恢复隔离区中的用户文件，原路径冲突时转存到 `内部存储/Download/BaiZe恢复`
3. 删除 `/data/adb/baize-v2` 与历史遗留的 `/data/adb/safesweep`
4. 卸载白泽 App

模块目录本体按 Magisk / KernelSU / APatch 标准流程在重启后删除。

## 排查

导出诊断信息（已脱敏）：

```sh
su -c /data/adb/modules/baize_v2/diagnostics-export.sh
```

提交问题时请附带这份导出，以及 ROM、Android 版本和 Root 方案。
