# 洛书同源底栏的主机测试环境

miuix blur/squircle 0.9.3 的 AAR 包含 class file version 65（Java 21）字节码。Android APK 由 D8 转换，已能在构建主机 JDK 17 上打包；Robolectric 在主机 JVM 直接加载这些类，必须使用 JDK 21。

本次 UI CI 切换主机到 JDK 21。App 的 Java/Kotlin JVM target 仍为 17，minSdk 26、targetSdk 36 不变。没有通过跳过 Miuix 组件、移除断言或忽略失败来绕过测试。

本地完整 UI 验证需 JDK 21、Gradle 9.5：

```sh
cd v2
gradle --no-daemon --continue :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug :macrobenchmark:assemble
```

排查依据：3020c93 的 UI CI 输出 247 tests / 27 failed；27 个失败全部是 UnsupportedClassVersionError，分别来自 SquircleBackgroundKt 和 RuntimeShaderKt。编译、lint、宏基准 APK 组装和另外 220 项测试通过。修正环境后仍需完整重跑，不能把环境修复本身当作测试通过。

Robolectric 的绘制用于布局、回退和导航验证；真实 GPU 折射、厂商系统表现与触控流畅度仍须真机验证。
