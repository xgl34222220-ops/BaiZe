# 白泽 v2 Alpha 1

白泽 v2 的第一阶段工程骨架：**原生 App + libsu RootService + AIDL + 模块桥接**。

当前版本只验证三件事：

1. App 能稳定连接独立 Root 进程。
2. Root 服务能非递归枚举应用缓存候选目录。
3. 模块管理器可以检测 App 并直接打开原生界面。

## 当前不会做什么

- 不删除任何文件。
- 不导入旧版 4,746 条深度规则。
- 不启动定时清理。
- 不依赖 WebUI。

先把通信、权限和基础扫描性能验证稳定，再进入原生扫描核心开发。

## 构建

需要 JDK 17、Android SDK 36 和 Gradle 8.13：

```bash
gradle :app:assembleDebug
sh scripts/package-module.sh
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `dist/BaiZe-v2-Alpha1-Bridge.zip`

详细架构见 `docs/ARCHITECTURE.md`。
