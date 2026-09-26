# Aurora 0927 功能对照与白泽 2.0.0 Test3

基线：白泽 `b189179`（2.0.0 / 30005，含 Test2 缓存优化）。参考用户提供的 `模块-1790447562689.zip`，SHA-256 `8e9daae0de8de82580dab65051ae82cbb717e8301d7713f75d933871458475ea`。

参考证据：Aurora 反编译 `com/aurora/cleanup/j.java:248–258` 的功能注册表、`defpackage/gj.java` 的 RUN / RUN_ALL / GET_FILES 调用、admin 下各配置文件。`Aurora_timed` 仅有 ARM64 二进制，没有引擎源码；本次是按可观察功能重写，未嵌入、运行该二进制，未移植其授权接口。ZIP 中 `apk-re` 是另一个 BoxProxy App，与清理模块分开。

## 可测试的实现

入口：清理 → 功能控制台（两套主题均可用）。原有扫描工作台、风险确认与隔离区继续提供详细选择。

| Aurora 功能 | Test3 实现 |
| --- | --- |
| RUN_ALL / 单项 RUN | 17 项功能注册；逐项勾选、立即执行、顺序队列、取消；提交与完成分开记录 |
| 碎片 / 空项目 / 规则垃圾 | 复用白泽原生前台引擎、保留期、白名单和路径保护 |
| 应用 / 系统缓存 | 按系统/第三方分类；沿用 Test2 去重、WebView、有限并行清理 |
| 微信 / QQ / TIM / 抖音 / 快手 / 网易云 | 限定包名的缓存与低风险深度规则；不选择高风险聊天记录、下载等数据 |
| 文件归类 | 复用校验、冲突重命名、撤销；新增 来源+类型+目标 可编辑映射 |
| MounterMover | 规则转移与撤销、可选整目录 bind 重定向；进入 init 挂载命名空间并核对挂载和 inode；可解除，重启后需手动/计划恢复 |
| logcat | 系统命令清空缓冲，记录真实返回码和超时 |
| 内存压制 | sync 后回收页缓存；单独记录 MemAvailable 前后观测，不计入文件删除空间 |
| 进程压制 | 所选第三方应用、内存阈值、保护名单、前台检查；kill / 系统非 sticky 冻结 / OOM 调整；观测 cgroup 冻结状态、保留已冻结进程、持续阈值检查、恢复入口 |
| 数据库 / SQLite 额外优化 | 所选空闲应用的标准 SQLite，PRAGMA optimize / 可选 VACUUM，跳过加密/未提交日志，超时取消、恢复所有者 |
| Dex2oat | 调用系统 ART，所选应用、编译模式、强制重新编译，逐应用结果 |
| F2FS GC | 探测 sysfs、脏段阈值、最长 60 秒、结束恢复 gc_urgent，独立恢复进程覆盖 Root 服务异常退出 |
| 独立定时 | 每项时间与间隔天数；充电/关屏条件；本地日期去重；六小时补做窗口；Root daemon 30 秒检查，WorkManager 恢复连接 |
| 黑/白名单 | 额外规则编辑；复用应用/路径保护名单；保护名单优先 |
| 独立云规则 | 从白泽自己的仓库更新前台规则，大小上限、SHA-256、原子替换、上版回退；模块规则仍随模块更新 |
| 配置热生效 | 原子保存、校验、导入/导出；当前队列固定配置快照 |
| 状态统计 | 今日/累计实际删除字节与文件；分区、内存、F2FS；失败/不支持如实显示 |
| 记录与清零 | 最多 100 次结果、界面最近 20 次；次数阈值、手动统计清零与记录清空 |
| 外观 / 全局搜索 | 接入现有白泽主题与背景设置；控制台按名称、描述、分组搜索 |
| 快捷磁贴 | 系统快捷设置可添加白泽控制台入口 |
| 通知 | App/后台看门狗收到实际完成结果后发布通知，遵循系统通知权限 |

本次没有声称复现 Aurora 二进制全部隐藏算法、5000+ 远端规则、Aurora 私有冻结算法、热更新协议、收费授权或云端规则上传服务。Aurora 的 `DEVICE_ID,2500` 参数是请求超时，不是 2.5 秒统计缓存。`QUEUED_ALL` 表示排队成功，不是清理结束。

## 验证边界

新增测试覆盖配置原子保存/拒绝非法输入、当地日期计划去重/补做/时钟回拨、统计语义、记录上限、来源匹配/通配符/路径越界、真实子进程大输出/超时/取消。原有缓存行为测试、模块脚本和原生引擎回归继续运行。正式签名构建检查 2.0.0 / 30005、原证书、模块布局、内嵌 APK 与独立 APK 一致。

F2FS 内核、ART 模式和第三方数据库行为依赖设备，主机测试不能替代 Root 真机验证；不支持时显示原因，不将跳过伪装成成功。不提供未经真机测量的速度提升比例。

平台依据：
- https://source.android.com/docs/core/perf/cached-apps-freezer
- https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/ActivityManagerShellCommand.java
- https://source.android.com/docs/core/runtime/configure/art-service
- https://sqlite.org/pragma.html
- https://sqlite.org/wal.html
- https://www.kernel.org/doc/html/next/admin-guide/abi-testing-files.html
