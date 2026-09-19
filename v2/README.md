# 白泽 v2

白泽是原生 Android 清理 App，前台扫描、文件管理与清理由 App 自身负责。
私有缓存和深度规则通过 App 自带的 libsu RootService 执行；共享文件分析使用系统文件索引。
Magisk / KernelSU / APatch 模块是可选的后台自动化组件，只在需要定时扫描、自动清理和开机调度时安装。
前台使用不以安装模块为前提；私有缓存与深度清理仍需要 Root，文件分析需要相应存储权限。

本轮功能与竞品参照见 [清理工作台 v5](docs/CLEANER_WORKBENCH_V5.md)。

项目总览与安全边界见[根目录 README](../README.md)，
版本历史见 [CHANGELOG.md](../CHANGELOG.md)，
架构说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，
Root 连接测试与真机验收边界见 [docs/ROOT_CONNECTION_TESTING.md](docs/ROOT_CONNECTION_TESTING.md)，
扫描、清理性能回归见 [docs/PERFORMANCE_TESTING.md](docs/PERFORMANCE_TESTING.md)。

## 目录结构

```
v2/
├── app/            Android App（Kotlin + Compose）
│   └── src/
│       ├── main/   主源码
│       └── test/   JVM 单元测试
├── native/         C 扫描引擎与深度不可变快照引擎
├── module/         Magisk 模块脚本（打包进 ZIP 根目录）
├── scripts/        构建、打包、版本与规则校验脚本
├── tests/          shell / python / 原生回归测试
└── macrobenchmark/ 启动与滚动性能基准
```

## 构建

```sh
sh scripts/build-native.sh      # 编译三个 ABI 的原生引擎
./gradlew :app:assembleRelease  # 需要签名环境变量，见根 README
sh scripts/package-module.sh    # 打包模块 ZIP 到 dist/
```

`scripts/build-native.sh` 默认编译 `arm64-v8a`、`armeabi-v7a`、`x86_64`。
只想编某一个：`BAIZE_ABIS=arm64-v8a sh scripts/build-native.sh`。

## 测试

```sh
bash tests/run-all.sh                 # 全量：原生 + shell + python
bash tests/run-native-tests.sh        # 只跑 C 引擎相关（宿主 cc 即可）
./gradlew :app:testDebugUnitTest      # JVM 单元测试
```

`tests/run-all.sh` 自动发现 `tests/test-*.sh` 与 `tests/test-*.py`，
新增测试文件不需要再改 CI 配置。

## 关键脚本

| 脚本 | 作用 |
|---|---|
| `scripts/sync-version.sh` | 以 `module.prop` 为唯一来源同步各处版本号；`--check` 供 CI 校验 |
| `scripts/validate-rules.py` | 校验规则库并回写 `config/rules.meta.env` 的条数与 SHA |
| `scripts/build-native.sh` | 多 ABI 交叉编译原生引擎 |
| `scripts/package-module.sh` | 打包模块 ZIP，打包前跑全量回归 |
| `module/scripts/abi-resolve.sh` | 运行时按设备 ABI 解析引擎路径 |

## 模块脚本入口

- `module/scripts/cleaner.sh` — 清理总入口，原生引擎不可用时退回 `cleaner-compat.sh`
- `module/scripts/native-cleaner.sh` — 原生扫描执行器
- `module/scripts/scheduler.sh` — Root 调度器（打包后重命名为 `scheduler.sh`）
- `module/scripts/supervisor.sh` — 调度器守护进程
- `module/scripts/task-worker.sh` — 统一 Root Worker
