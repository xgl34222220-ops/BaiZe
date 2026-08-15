# 白泽 v2

白泽 v2 是面向 Magisk / KernelSU / APatch 的原生 Android 清理模块。
App 通过 libsu RootService 调用模块内的 C 扫描引擎，不依赖 WebUI。

项目总览与安全边界见[根目录 README](../README.md)，
版本历史见 [CHANGELOG.md](../CHANGELOG.md)，
架构说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

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
| `module/abi-resolve.sh` | 运行时按设备 ABI 解析引擎路径 |

## 模块脚本入口

- `module/cleaner.sh` — 清理总入口，原生引擎不可用时退回 `cleaner.sh.compat`
- `module/native-scan.sh` — 原生扫描执行器
- `module/scheduler-v2.5.sh` — Root 调度器（打包后重命名为 `scheduler.sh`）
- `module/supervisor.sh` — 调度器守护进程
- `module/task-worker.sh` — 统一 Root Worker
