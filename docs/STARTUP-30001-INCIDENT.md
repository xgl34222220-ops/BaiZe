# 30001 正式包启动崩溃

## 已复现的事实

复现运行：34734741905（Android API 36，google_apis x86_64，硬件 Canvas/SwiftShader，未关闭默认玻璃设置）。

APK 为公开发布的 30001 原签名包，SHA-256 `570ece2a97e348d392ed45fd89b7ab452937af3cb58f923744f6260ef9021f76`，不是从源码重编译的 Debug 包。启动返回后进程已退出。

```
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider:
  java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
Caused by: java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
  at java.lang.Class.getDeclaredConstructor(Class.java:3067)
  at androidx.work.WorkManagerInitializer.b(Unknown Source:58)
```

直接检查该 APK 的 DEX：WorkDatabase_Impl 存在，但其 `<init>` 不存在。ContentProvider 初始化早于 Application.onCreate，因此 App 自带 CrashRecorder 还未安装，不能要求用户从打不开的设置页导日志。

## 修复与发布检查

对 WorkDatabase 与生成的 WorkDatabase_Impl 添加明确的反射保留规则，包括 public 无参构造器。不关闭 WorkManager、R8 或玻璃效果，不清除数据库。内部构建号提升到 30002。

原 247 项测试没有启动最终压缩 APK 的 ContentProvider；软件渲染验证不能覆盖这个失败。正式替换前新增真实安装最终签名 APK 的 Android 模拟器启动门槛，覆盖失败构建覆盖升级、冷启动、四标签切换、新安装和返回前台。仅成功运行后才发布，最终结果以工作流和 passed.json 为准。

参考 R8 FAQ：https://r8.googlesource.com/r8/+/refs/heads/main/compatibility-faq.md
R8 full mode 不会因类被保留就隐式保留默认构造器。
