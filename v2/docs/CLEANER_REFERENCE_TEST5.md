# 白泽 2.0.0 Test5：清理执行链路参考与修正

用户反馈 Test4 无实际效果后，重新搜索项目并检出代码进行对照。以下记录实际读过的来源与落地内容，不代表已复刻参考项目全部功能。

| 来源 | 已检查的实现 | 白泽的改动 |
| --- | --- | --- |
| [SD Maid SE](https://github.com/d4rken-org/sdmaid-se/tree/1a90bc3d5a31994dae6104d5521bccdef812207f) | `app-common-io/.../pkgs/PackageManagerExtensions.kt` 与 `pkgops/ipc/PkgOpsHost.kt`，按用户调用缓存删除接口、等待观察者回调 | 即时清缓存优先使用系统 `deleteApplicationCacheFilesAsUser`；保留所选用户；收到回调才报告确认成功；超时或失败不重放删除 |
| [ClearBox](https://github.com/FLYCOM-E/ClearBox/tree/7933206060b65bb9dd44b5f0833d6047bbbfb2e7) | `src/app_cache_clean.c`、`src/INCLUDE/utils.c`，包名/用户目录定位、原生删除累计 | 专项缓存定位已知目录后直接删除并计数，省去原先整棵缓存树的预扫描；保留白泽已有的路径、白名单和重定向检查 |
| [CZero](https://github.com/Xocio/CZero/tree/f374ac5ebe9d3e63605e078e26dc066cf74896f0) | 当前公开仓库的工作原理、功能说明与规则文件 | 检出内容没有可移植的清理器源码，因此仅作为行为对照，不宣称移植其闭源执行器 |
| [AOSP 回调接口](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/pm/IPackageDataObserver.aidl) 与 [PackageManager API](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ApplicationPackageManager.java) | 缓存删除专用方法、异步完成回调 | 加入带 Apache 2.0 标注的 AIDL 声明，Root 调用前清除传入 Binder 身份，调用后恢复 |

SD Maid 与 ClearBox 均为 GPLv3 项目；白泽仓库同为 GPLv3。本次没有复制它们的界面、图标、翻译或二进制。白泽的缓存协调器与目录遍历修改独立实现，AOSP 的回调声明保留原版权与许可。

## 修正的具体问题

- 原即时工具先 `waitFor()` 再读 `cmd package help` 管道；帮助输出超过管道容量时可互相等待，随后误报不支持。替换为专用观察者接口；仅当反射接口不存在时探测 cache-only 命令。命令输出写入受限临时文件，不再等待尚未排空的管道。
- 命令回退始终带 `--cache-only`，必须同时返回零退出码和 `Success`。不会退化为清空应用数据；系统回调失败、超时或取消后不重复提交。
- 四组应用专项原来扫描统计一次、清理又遍历一次。现在一次删除遍历累计实际删除字节与文件，手动逐项选择仍使用原扫描快照。
- 深度专项原来展开整套规则再按包名丢弃结果；现在先限定规则中的包名位置，再展开目录。普通深度扫描保持原规则范围。
- 标准 WebView 目录之外的合法进程后缀（例如 `app_webview_remote`、`app_hws_webview7`）纳入缓存定位；Cookies、Local Storage 和备份目录不纳入。
- 缓存部分删除失败现在返回失败结果，不再因为“没取消”就报告成功。
- 扩展应用名单优先读 PackageManager；失败时通过有超时限制的 Root 包目录命令补取包名、UID 与第三方分类。同次任务复用名单。
- 应用名单查询失败不会阻断全部系统工具；扩展页的状态读取和操作错误始终可见，提供重新读取入口，不再只剩无法解释的灰色按钮。

## 验证与边界

测试覆盖实际临时文件的单次遍历删除、删除失败保留、其他应用/保护目录/Cookies 保留、专项规则范围、系统缓存观察者的异步成功与失败、超时不重放、不同用户、超过管道容量的真实子进程输出、命令回退仍为 cache-only、Root 包目录回退。

系统接口测试使用 Android 主机测试环境和回调替身；目录删除测试实际读写临时文件。没有把它们写成 Root 真机兼容性、所有 ROM 支持或固定提速比例的证明。版本保持 2.0.0 / 30005。
