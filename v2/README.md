# 白泽 v2 Alpha 2

第二阶段验证版：**原生 App + libsu RootService + AIDL 分页结果**。

当前实现：

1. 只返回真实存在且非空的 `cache` / `code_cache` 目录。
2. 发现阶段不递归读取文件，不调用 `find`、`du`、`stat` Shell 链。
3. 显示应用名称、包名、用户、分类、路径、大小和文件数。
4. 大小与文件数按当前页延迟统计；每个目录最多 1.5 秒，每页最多 8 秒。
5. 扫描结果每页 30 条，通过 Binder 分页读取，避免大事务和界面卡顿。
6. 支持结果勾选与应用级白名单。
7. 当前仍然没有任何删除接口。

## 构建

需要 JDK 17、Android SDK 36 和 Gradle 8.13：

```bash
gradle :app:assembleDebug
sh scripts/package-module.sh
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `dist/BaiZe-v2-Alpha2-Bridge.zip`
