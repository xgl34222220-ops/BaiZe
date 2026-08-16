<div align="center">

# 白泽 BaiZe

**Android Root 缓存、日志与存储垃圾清理模块**

适用于 Magisk、KernelSU 与 APatch

![Version](https://img.shields.io/badge/version-v2.6.0-1677ff)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android)
![License](https://img.shields.io/badge/license-GPL--3.0-orange)

</div>

白泽以准确统计、分级保护和可审计清理为核心。项目保留完整深度规则库，但把规则存在与删除权限分离：低、中风险内容可参与安全定时，高风险与关键风险内容默认只扫描，完整深度清理必须由用户手动确认。

v2 以原生 App 为主界面，通过 libsu RootService 调用模块内的 C 扫描引擎，不再依赖 WebUI。

## 界面预览

<p align="center">
  <img src="docs/images/home.png" width="46%" alt="白泽首页">
  <img src="docs/images/rules.png" width="46%" alt="白泽规则页">
</p>

## 核心功能

- 应用内部缓存、`code_cache` 与 `Android/data` 外部缓存清理
- 空文件、空目录、隐藏垃圾、系统日志和残留碎片清理
- 4,714 条深度规则分级扫描与安全执行
- 安装包（APK / APKS / XAPK / APKM）扫描与保留期清理
- 卸载应用残留扫描，清理前再次检查应用是否重新安装
- 应用下载、接收、附件与导出文件的自动归类
- 每组任务独立周期或每日固定时间
- 息屏、充电、系统空闲、电量、温度和运行时长条件
- 扫描快照、规则 SHA 校验、白名单与单文件上限保护
- 实际删除后复核，通知和累计统计只记录真正释放的空间
- 审计报告、任务历史、隔离区与原子配置保存
- 深度扫描实时进度、慢目录限时保护与缓存根目录合并扫描
- 断点续清：扫描快照持久化，任务中断后可继续

## 安全边界

- 风险分为 `low` / `medium` / `high` / `critical` 四级
- **定时深度任务永不执行 high 与 critical**，这是脚本层的硬边界，改配置也绕不过
- 完整深度清理必须先扫描，并在 30 分钟内手动确认；扫描授权使用一次后失效
- 不跟随软链接，不允许清理模块、Root 配置和系统关键路径
- 删除前双次 `lstat` 并与快照元数据比对，文件被改动过即跳过
- 默认保护下载、文档、相册、影音、数据库、SharedPreferences、密钥、草稿、备份与 OBB 主体

### 自己决定删到哪一级

风险等级不是写死的，四个层次都可以调：

| 位置 | 作用 |
|---|---|
| `config/default.conf` 的 `deep_scheduled_max_risk` | 定时任务的上限，默认 `medium`；设为 `low` 更保守 |
| `config/default.conf` 的 `deep_manual_max_risk` | 手动完整清理的上限，默认 `high` |
| `config/risk-overrides.conf` | 按路径逐条覆盖，可升可降，优先级最高 |
| `config/whitelist.conf` | 彻底保护，连手动清理都不碰 |

`deep.rules` 里的规则也可以写成 `路径|risk` 形式显式标注等级。
优先级：用户覆盖 > 规则标注 > 按路径分段推断。

### 规则完整性

`config/rules.meta.env` 是规则元数据的唯一来源，包含条数与 SHA-256。
改动规则后运行以下命令重新生成，CI 会校验一致性：

```sh
python3 v2/scripts/validate-rules.py
```

## 安装

1. 从 GitHub Releases 下载最新 ZIP。
2. 在 Magisk、KernelSU 或 APatch 中选择该 ZIP 安装。
3. 重启设备。
4. 打开白泽 App 进行扫描、清理与定时设置。
5. 第一次完整深度清理前，先执行深度扫描并检查审计报告。

安装时会校验内嵌 APK 的 SHA-256，不匹配会拒绝安装 App。

## 在线更新

白泽直接使用 Magisk、KernelSU、APatch 自带的模块更新机制，不需要在 App 里另外下载更新包。

1. 打开 Root 管理器的模块页面并刷新更新。
2. 出现白泽新版本后直接点击更新。
3. Root 管理器会下载正式模块 ZIP 并刷入。
4. 按提示重启设备。

在线更新的版本信息来自 `update.json`，模块 ZIP 使用 GitHub Raw 稳定镜像，避免部分 Root 管理器无法正确处理 GitHub Release 附件重定向的问题。Raw 镜像与 GitHub Releases 中的正式模块 ZIP 保持同一份文件和同一 SHA-256。

GitHub Releases 仍然是正式版本归档和手动下载入口，独立 APK、校验文件和签名证书也继续保留在那里。

## 从源码构建

需要 JDK 17、Android SDK（platform 36、build-tools 36）与 Android NDK：

```sh
cd v2
sh scripts/build-native.sh        # 编译 arm64-v8a / armeabi-v7a / x86_64 引擎
./gradlew :app:assembleRelease    # 构建 APK（需要签名环境变量）
sh scripts/package-module.sh      # 打包模块 ZIP
```

产物位于 `v2/dist/`。

提交前的检查：

```sh
bash v2/tests/run-all.sh                  # 全量回归（shell / python / 原生）
sh v2/scripts/sync-version.sh --check     # 版本一致性
python3 v2/scripts/validate-rules.py --check
cd v2 && ./gradlew :app:testDebugUnitTest # JVM 单元测试
```

发布：

```sh
sh v2/scripts/sync-version.sh --set v2.6.0
git commit -am "chore: v2.6.0" && git push
git tag v2.6.0 && git push origin v2.6.0
```

发布正式版后，需要把对应的 `BaiZe-vX.Y.Z-Module.zip` 同步到 `downloads/releases/vX.Y.Z/`。`update.json` 的在线更新地址固定使用该 Raw 镜像；GitHub Releases 继续保存正式归档与独立 APK。

## 文档

- [详细使用说明](docs/README-detailed.md)
- [更新日志](CHANGELOG.md)
- [参与贡献](CONTRIBUTING.md)
- [安全说明](SECURITY.md)
- [来源与致谢](NOTICE.md)

## 兼容性

- Android 8.0+
- Magisk / KernelSU / APatch
- arm64-v8a、armeabi-v7a、x86_64

模块不修改 `/system`，也不依赖 KernelSU 元模块。不同 ROM 对通知、Doze、电池状态和外部存储权限的实现可能不同，提交问题时请附带经过脱敏的环境信息。

## 许可证

本项目以 GPL-3.0 许可证发布。第三方项目、规则来源、名称和资源仍遵循其各自许可证，详见 [NOTICE.md](NOTICE.md)。

作者：**惜故里丶**
