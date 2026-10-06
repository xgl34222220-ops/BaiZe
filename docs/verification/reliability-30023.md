# 白泽 30023：直接迁移、压缩强杀恢复与时间戳碰撞

原测试分支 `codex/baize-safety-scan-20260930`，从已交付的
`341ef4acc97b8a1b145e4f5d6d3d8f3578664c7d` 续接。测试构建号增加到 30023，
显示版本仍为 2.0.0。所有设备操作均在一次性 CI 模拟器及其生成的夹具上进行。
未合并 main、未发布 Release、未更新 OTA、未部署、未操作用户设备，也没有扩大生产权限。

## 起点和验收口径

开始时核对了实际 Git 仓库、原分支远端引用、最新提交、工作区和 Actions：
queued/in_progress 均为 0，原分支没有待合并 PR。保留上一轮成功及失败运行；713 项旧测试
仅作为历史信息。本报告的通过必须来自本轮运行和实际产物。

冻结输入为同一正式证书签名的 30021（`bb5c8224`，Paired 37130389704）和
30022（`4bcdf8d9`，Paired 37158958651）。直接迁移没有经过中间版本、卸载或清除数据。
30023 产物来自 `3331c47ca065972311eb91858d85de16f1eb3830` 的
[Paired 37168287013](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37168287013)。
CI 续接仅等待和下载精确提交的产物，不派发或取消生产构建。

## 各批实际修改

| 批次 / 提交 | 实际问题与改动 |
| --- | --- |
| [c3155067](https://github.com/xgl34222220-ops/BaiZe/commit/c3155067e670bf949bea30e8c57f3c92d031b7a6) | 增加精确 30021→30022 覆盖安装、私有文件逐字节保留、权限保留、旧记录与返回栈验收。发现安装数据保留，但无 Root 时记录页没有加载 App 自有历史。 |
| [02932e03](https://github.com/xgl34222220-ops/BaiZe/commit/02932e034c74af968755ba81342b6093e299f5ae) | 修复 Root 不可用时的本地历史加载；旧 App 记录的缺失、null、非法值和歧义 0 保持无法测量；统一三个历史界面及视频时间线容量文字。记录用 AtomicFile 写入，新记录使用独立 ID，读取旧记录不重写文件。 |
| `e32ea5d7`、`457b1665`、`2b5f16ac` | 建立独立 DocumentsProvider 故障夹具，修正 CI 私有夹具初始化、文件列表可访问名称和目录根支持。前几次失败发生在到达强杀点之前，不计为产品恢复证据。 |
| [cf718246](https://github.com/xgl34222220-ops/BaiZe/commit/cf718246455d594f969386dc5ff95035d177249e) | 增加 App 私有原子导出日志：先记导出意图，取得目标 URI 后再记目标，随后才打开写入。成功历史落盘后才确认副本。冷启动提示上次中断，手动只读核对，禁止自动重放；不完整和无法读取的目标副本保留。只清理日志列出的 App 私有工作缓存。 |
| [3331c47c](https://github.com/xgl34222220-ops/BaiZe/commit/3331c47ca065972311eb91858d85de16f1eb3830) | 修复新回归暴露的四项失败：共享存储内容哈希跨扫描复用、同秒旧审计记录消失、审计镜像对应关系和非法 TSV 容量被当成已测量 0。每次新扫描重新读取内容；删除前重新读取保留副本的 SHA-256。 |
| [778a39dc](https://github.com/xgl34222220-ops/BaiZe/commit/778a39dc560ba8cd25cee148a94385efdc641f23) | CI 脚本核验被杀的原 PID 消失及 Android 死亡日志，允许系统立即拉起新 PID；恢复提醒通过实际滚动页面检查。保留原脚本失败，未改写其结论。 |
| [b60f9e2f](https://github.com/xgl34222220-ops/BaiZe/commit/b60f9e2f222b062f4f52ff43189acd2fea22bb8f) | 仅续跑已结束的 30023 签名照片 job；严格比对冻结生产源码并复用同一 `3331c47c` APK，不重复构建或取消正在运行的 JVM/lint job。 |
| [e70d1227](https://github.com/xgl34222220-ops/BaiZe/commit/e70d1227a592a774786a485248f825cd89b301cc) | 调试打包在 CI 30 分钟上限被平台自动终止后，只补跑构建及此前跳过的共享存储探针。使用新工作区，lint 与打包分别执行、打包限制一个 worker；严格比对全部 Android 源码/测试/探针与冻结提交一致。未重新执行已有完整 XML 的 739 项回归。 |
| [a6b9ac0d](https://github.com/xgl34222220-ops/BaiZe/commit/a6b9ac0d8cbec544982674cabc185848a87cced4) | 共享存储 CI 在调用 App 前因宿主没有 rg 而失败。改为 Bash 原生模拟器守卫，记录输入 APK 哈希并在退出时保留 logcat；仅续跑该探针，复用已完成的 e70d1227 调试产物，不重建或重跑单元。 |

旧审计没有唯一 ID 时，相同行可能来自独立任务，也可能是重复记录，不能证明额外容量。
现在保留每个出现项供查看，并将重合项标为身份歧义、无法测量，不重复累计容量；已有独立 ID 的
同秒同容量任务分别计数，App/Root 镜像仍按同一 ID 合并。所有报告中的夹具字节数都不是用户设备的释放量。

继续保留既定界面与无 hook 边界。容量与执行状态仍独立：无法测量、回收站尚占用、可信真实 0、
不计释放和部分可信容量各自显示；保护、跳过、失败及取消不转换为“实际释放 0”。

## 本轮失败证据

| 运行 | 实际结果及解释 |
| --- | --- |
| [37165881963](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37165881963) | 精确 30021→30022 安装成功且夹具数据保留；旧版 Root 不可用时历史不显示，产品验收失败。 |
| [37166296139](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37166296139)、[37166652542](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37166652542)、[37167040114](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37167040114) | 文件/目录选择器脚本和夹具兼容性失败，未到达实际强杀点，不用于证明产品恢复。 |
| [37167488141](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37167488141) | 独立解析 XML：110 套件、738 项、4 失败、0 错误/跳过。失败均在时间戳新回归；压缩日志 7 项、压缩行为 9 项等通过。故障夹具另有 Java override 异常声明编译错误，已修正。 |
| [37168287002](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37168287002) 的旧版强杀 job | 已记录第一份完整副本和第二份 64 字节残留；Android 日志确认旧 PID 4440 死亡并拉起新 PID 5267。脚本错误要求不存在任何新 PID，因此失败；尚不能把此失败当作完整冷启动恢复验收。 |
| 同一运行的 30023 签名照片 job 111335771162 | 正常单张/批量导出、旋转、重复项、元数据、原图与完成记录重启全部通过；随后实际强杀被相同 PID 脚本误判阻挡。其 artifact 11289739047 同时保留正常照片 JSON 和强杀点日志，整项仍为失败。 |
| 同一运行的 Android job 111335771015 | 单元步骤成功，lintReportDebug/lintDebug 已完成并生成 HTML；packageDebug 自 01:40:53 停留至 02:01:59，达到 30 分钟 job 上限后平台自动终止（cancelled）。没有取消 API 调用，也没有足够堆栈确认底层根因。artifact 11290474957 保留完整本轮 XML、lint 和未完成 APK，不能安装或当作成功构建。 |
| [37169949638](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37169949638) 的 storage-collision / 111341946123 | 宿主 `rg: command not found`，在安装及启动 App 前失败，未产生产品探针结果。该运行的独立 debug/lint job 已成功，整体结论仍为失败。 |

历史首批修复的 [37166579872](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37166579872)
已独立解析为 108 套件、724 项、0 失败/错误/跳过，lintDebug 和 assembleDebug 成功。
最终全量及设备结果在下面按精确产物补充。

## 最终验证

| 检查 | 本轮结果 | CI / job / artifact |
| --- | --- | --- |
| Cleaner 原生/Shell/Python | 78 组、0 失败；构建前与打包前各重新执行一次 | Paired 37168287013 / 111335771125 / 11289954596 |
| 正式签名 App 与配套模块 | 成功；文件哈希、正式证书、嵌入 APK 字节一致及 3 种 ABI 的 9 个 ELF 已独立核对 | 同上 |
| 全量 Android JVM/Compose | 独立解析 XML：110 套件、739 项、0 失败/错误/跳过；时间戳 5 项、日志 7 项、压缩行为 9 项、App 历史迁移 8 项、容量审计 23 项通过 | Reliability 37168287002 / 111335771015 / 11290474957 |
| lintDebug、assembleDebug | 续跑成功，调试打包 3m52s、lint 3m33s；APK CRC、完整 ZIP 与 versionCode=30023 已核对，未修改 lint baseline | [Reliability 37169949638](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37169949638) / 111340674430 / 11290892701 |
| 精确 30021→30022 数据迁移，再验证 30023 历史修复 | 通过；原 30022 安装数据/权限保留、历史显示仍失败；30023 旧记录无法测量、旧选择保留但不重新授权、照片历史不重放、原图 SHA 与单次返回/重复进入通过 | Reliability 37168287002 / 111335771110 / 11290272844 |
| 签名包正常照片批量导出和强杀恢复 | 通过；独立 JSON 核对实际 PID 7160 死亡、第二份 64 字节残留、持久日志与冷启动提醒、只读核对拒绝不完整副本、原图/完成历史/残留保留且不自动重放；恢复界面 XML 中提醒、错误说明与两个按钮可见，操作成功 | [Reliability 37169526674](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37169526674) / 111339453497 / 11291140730 |
| 旧 30022 强杀负面对照 | 对照验收通过：实际原 PID 4628 死亡、系统新 PID 5430，第二份 64 字节残留；旧版缺少持久日志和中断提醒，`baselineRecoveryPassed=false`；原图/完整副本/残留 SHA 均保留、无重放 | [Reliability 37168945676](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37168945676) / 111337755966 / 11290014893 |
| 共享存储真实时间戳及只读审计/内容核验 | 通过；独立 JSON 核对 API 36、App UID 10216、真实共享 mtime/MediaStore 秒级碰撞、新扫描拒绝变化内容、旧复核证明与强制完整身份碰撞被 SHA 拒绝；审计 2 行/可信夹具容量 4096/未知 1，未删除且两份共享夹具保留。真实完整 stat 身份碰撞未观察到（false） | [Reliability 37170827584](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37170827584) / 111343265076 / 11290504847 |

最终产物：`BaiZe-functional-test+3331c47c.apk`（4,365,123 字节）、
`BaiZe-functional-test+3331c47c-Module.zip`（4,080,018 字节），构建号 30023。

| 对象 | SHA-256 |
| --- | --- |
| APK | `5f2046f89cc3415f95c4e637df9e86f7f8326582defd258a5559b44d1bbdae18` |
| 模块 | `676a7bb90322c2e029c9c0c033120fbe44682fd42ba90081b448feb6574cba4d` |
| 正式证书 | `9efa848001ccdc168ea96753d332b1713e2668ba6a2fa22d5bfd98de65db1f0f` |

签名照片恢复采用独立进程的临时 DocumentsProvider、真实系统文件/目录选择器和 SAF grant。
强杀时正常照片验收已有 2 条历史，故障批次第一份再完成 1 条，第二份留下 64 字节；
恢复后仍为 3 条，未将残留写成成功。日志只记工作进度和复核证据，不授权删除或自动重试。
新 PID 是否立即出现由系统决定；判据为原 PID 消失且 Android 日志明确报告原进程死亡。

首轮 lint HTML 记录 133 个警告、3 个提示，另有 132 个警告被既有 baseline 过滤。
lint 不等于零历史告警；本轮没有通过修改 baseline 隐藏警告。
最终独立 lint 报告数量相同。调试探针 APK 为 27,537,242 字节，SHA-256
`44a8c96e8d795bc75fbbe69e0b2831377f805cb6e659dfca519d6433cf414728`；
其 App 源码、测试与探针均严格比对冻结提交一致。

## 同类开源参考、许可证与兼容性

参考资料于 2026-10-04 UTC 阅读；仅参考设计原则，本轮未复制上游源码、界面文字、图标或其他素材，
未引入新的第三方依赖。白泽仓库继续使用既有 GPLv3 许可证。

| 项目 / 参考链接 | 许可证与采用理由 | 白泽独立实现与兼容说明 |
| --- | --- | --- |
| SD Maid SE：[README](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/README.md)、[ChecksumSleuth 源码](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/scanner/checksum/ChecksumSleuth.kt)、[LICENSE](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/LICENSE) | 代码 GPLv3；README 明确部分素材、文档、翻译等不在该授权内。参考按大小分组后读取内容 SHA-256 确认重复的做法，避免把时间戳当内容证明。 | 在白泽既有大小/前缀/完整 SHA-256 链路上修复缓存寿命与删除前检查。每次刷新会增加读取 I/O，保留既有分组、大小上限和取消检查；真实手机性能尚未量化。 |
| Compressor：[README/许可证说明](https://github.com/zetbaitsu/Compressor/blob/a920146ba10421bcf214261870a69d56c52d5cc1/README.md)、[Compressor.kt](https://github.com/zetbaitsu/Compressor/blob/a920146ba10421bcf214261870a69d56c52d5cc1/compressor/src/main/java/id/zelory/compressor/Compressor.kt)、[Util.kt](https://github.com/zetbaitsu/Compressor/blob/a920146ba10421bcf214261870a69d56c52d5cc1/compressor/src/main/java/id/zelory/compressor/Util.kt) | README 标注 Apache 2.0；仓库未提供独立 LICENSE 文件。参考私有缓存副本、后台压缩、采样和方向处理，减少直接操作原图的风险。 | 白泽继续通过 SAF 另存副本、保留原图，保留 SDR JPEG 与色彩/HDR 保护。持久中断日志、目标保留和只读恢复由白泽自行实现。 |
| Android：[保存界面状态](https://developer.android.com/topic/libraries/architecture/saving-states)、[SavedStateHandle](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)、[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile) | 官方说明界面保存状态依赖生命周期时机；AtomicFile 完成写入时同步并提交，调用者承担并发保护。 | 导出日志位于 App 私有持久目录，独立于 SavedStateHandle；日志方法同步访问。使用既有 Android API 和已选目标的 SAF grant，保持 minSdk 26，不增加生产导出组件或敏感权限。 |

## 证据边界和仍未验证

- 真机 ARM64、OEM 文件系统/文档提供器、锁屏/重启/后台限制和存储空间耗尽尚无本轮设备证据。
- CI 的 adb SIGKILL 证明实际进程死亡与冷启动行为，不等于验证所有系统 LMK 或厂商后台策略。
- 共享存储实际毫秒 mtime、MediaStore 秒时间戳碰撞与“完整 stat 元组相等”须分别记录；强制完整身份
  碰撞的回归不能替代真实文件系统的完整元组碰撞。本轮实际 `fullFilesystemIdentityCollisionObserved=false`，
  新旧捕获身份的 ctime 纳秒不同，完整真实碰撞仍未验证。
- 未操作用户设备；跨 UID Root Binder 真实可用性仍未验证。API 28/29 权限和 Shizuku 的上一轮设备结果
  保留为历史，本轮全量 JVM 覆盖不等于重新执行这些设备路径。
- 原始 30022 的 Root-free 历史 bug 属于已冻结交付产物；直接迁移报告不能因随后安装 30023 就改写其结果。
- 非法容量保持空值和无法测量；不从扫描估算、目标残留、旧匿名记录或物理可用空间推造释放量。
