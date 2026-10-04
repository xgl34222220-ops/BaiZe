# 白泽 30024：旧权限重跑、内容身份与目录显示

从原测试分支 `codex/baize-safety-scan-20260930` 的 `61a4766` 续接。
开始时本地与远端一致，queued/in_progress 均为 0，没有重复实现或停止已有任务。
739 项 JVM、78 组 Cleaner 和上一轮设备结果只作基线。本报告只将本轮完成的运行列为通过。
所有设备操作均为一次性 CI 模拟器，未使用用户设备。未合并 main、发布 Release、更新 OTA 或部署。

## 第一批：精确签名 30023 的旧权限与 Shizuku

复用冻结源 `3331c47ca065972311eb91858d85de16f1eb3830` 的
[Paired 37168287013](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37168287013)，
不重新构建、不清空或卸载白泽数据。APK SHA-256：
`5f2046f89cc3415f95c4e637df9e86f7f8326582defd258a5559b44d1bbdae18`。
CI 检查精确源、正式证书、versionCode=30023 和模块内 APK 字节一致。
私有夹具文件按 App UID 创建，权限/设置/重启全过程后检查 SHA-256 和属主不变。

| 提交 | 修改 |
| --- | --- |
| [e0f876a](https://github.com/xgl34222220-ops/BaiZe/commit/e0f876ad3ea0eb33d563068337324b75b008cac4) | 复用原设备脚本和精确产物，四项新设备路径；取消脚本里的 pm clear 和测试包卸载；增加 App 私有数据保留、Shizuku 真实拒绝/再次授权、目录逐层查看和返回检查。并发组按提交隔离，cancel-in-progress=false。 |
| [57077da](https://github.com/xgl34222220-ops/BaiZe/commit/57077dab60a1d5c6b40b29ebdb763b9c2e34d6c9) | 按真实界面修正目录总数的检查位置：计数属于父列表中的目录行。 |
| [3316444](https://github.com/xgl34222220-ops/BaiZe/commit/3316444fea59056e97cc3ddc9f6f6cd6f8972a3b) | 逐个 XML node 检查文字，避免迭代已拼接字符串的字符；等待真实拒绝回调及对话框消失后才冷启动。 |

| 检查 | 本轮结果 | CI / job / artifact |
| --- | --- | --- |
| Android 9/API 28 | 通过：真实拒绝、再次授权、只有读权限不足、永久拒绝进入设置、设置授权后返回、再次撤销后运行时授权、未索引嵌套目录和同 Activity 返回；两份文件及 App 私有数据哈希保留 | [37173144573](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37173144573) / 111350064091 / 11291968315 |
| Android 10/API 29 | 同上，通过 | 同一运行 / 111350064157 / 11292511625 |
| Android 16/Shizuku shell | 通过：真实拒绝不绑定、不清缓存；再次授权，server/UserService UID=2000；只清缓存能力不足时批量按钮禁用，进入所选测试包的真实系统缓存设置；持久数据/未选缓存保留、一次返回、重复点击不叠页 | [37172322272](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37172322272) / 111347635560 / 11291858913 |
| Android 16/Shizuku root | 通过：真实拒绝和再次授权，server/UserService UID=0；仅清所选测试包生成缓存，持久数据和未选缓存保留；导航检查通过 | 同一运行 / 111347635443 / 11291349722 |

Shizuku 运行 `37172322272` 的整体结论仍为失败：其 API 28/29 脚本在进入目录后错误
寻找父列表计数。`37172698318` 也保留失败：API 29 将拼接字符串按字符迭代；API 28 在真实
永久拒绝对话框完全结束之前冷启动。最终续接的两项旧权限通过后才完成第一批验收，
未将这些脚本失败改写为产品通过。未再次运行已成功的 Shizuku job。

所有清缓存均仅针对生成的 CI 测试包缓存；应用持久数据及白泽私有夹具保留。
Shizuku root 成功不等于白泽 libsu 跨 UID Root Binder 验收，JSON 明确记录后者未验证。

## 第二批：内容身份与无进展读取

[ed0411a](https://github.com/xgl34222220-ops/BaiZe/commit/ed0411a2c42326194c501c7812b477e6047298a6)
先增加独立回归，在未修改的 30023 生产代码上运行。
[37172872037](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37172872037)
的完整 XML 独立解析为 2 套件、9 项、3 失败、0 错误/跳过（artifact 11292161798）。
其中 2 项为前缀/完整摘要的合成异常流：正长度读取连续返回 0，旧实现继续空转而不退出。
这证明故障注入下的代码缺口，不能声称某厂商提供器已实际复现此异常。

[61fe00f](https://github.com/xgl34222220-ops/BaiZe/commit/61fe00fff27112f51397090120529f0499ec6419)
增加连续无进展读取上限，返回不可核对、关闭流，取消优先，不产生空内容 SHA 或删除授权。
现有跨扫描重新读取和清理前新鲜内容证明继续保留。
合成回归覆盖非零纳秒、整秒、缺失纳秒下完整供应身份不变但尾部内容变化，以及旧成功后读取失败、
身份缺失不可选择、取消和资源关闭。内容修复后的 [37173553698](https://github.com/xgl34222220-ops/BaiZe/actions/runs/37173553698) / job 111351343744 / artifact 11292696484 已独立解析为 8 项、0 失败/错误/跳过。真实设备探针新增 API 26/36，详细结果在完成后补充。

## 第三批：目录统计与可操作索引

原 30023 的目录页面使用整个存储卷的汇总指标，未展示当前目录自身的计数；目录中有实际文件
却没有索引记录时，空列表提示也没有解释这一差别。真实 API 28 截图和新增 Compose 失败相互印证。
改动保留原界面组件、层级和导航，当前目录显示自身含子目录占用/文件数，缺失观察显示“尚未统计”；
统计中有文件而当前索引/筛选不提供操作项时，显示“目录文件尚不可操作”及原因。
停止扫描后的文字改为重新读取内容，和实际摘要缓存策略一致。构建号增加到 30024，显示版本仍为 2.0.0。
不新增生产权限、导出组件、hook 或文件删除入口。

## 最终构建与验收

待本轮新提交 CI 完成后补充精确源、签名产物、新全量 XML 和设备结果。
尚未完成的运行及未观察到的现象不计为通过。

## 每批开源参考、许可证与独立实现

本轮阅读以下固定来源。仅参考设计原则，没有复制源码、文字、图标或素材，没有增加第三方依赖。
白泽继续使用既有 GPLv3；Shizuku API 依赖版本仍为既有 13.1.5，设备管理器为原固定 13.6.0。

| 批次 / 参考 | 许可证与采用理由 | 独立实现和兼容说明 |
| --- | --- | --- |
| 旧权限/Shizuku：SD Maid SE [ShizukuWrapper.kt](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/app-common-adb/src/main/java/eu/darken/sdmse/common/adb/shizuku/ShizukuWrapper.kt)、[README](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/README.md)、[LICENSE](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/LICENSE)；Shizuku [官方指南](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/README.md)、[API 源码](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/api/src/main/java/rikka/shizuku/Shizuku.java)、[MIT LICENSE](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/LICENSE) | SD Maid 代码 GPLv3，README 的部分素材/文档/翻译不在该授权内；Shizuku API MIT。参考将许可、连接、UID 和能力分别核对，未知回答不作为可用能力。 | 复用白泽原有能力查询和标准 UserService，设备脚本通过真实管理器授权和系统设置验证；不采用自动注入或 hook，不添加系统签名权限。API 28/29 的读写授权和当前 shell/root 能力各自独立验收。 |
| 内容身份：SD Maid SE [ChecksumSleuth.kt](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/app-tool-deduplicator/src/main/java/eu/darken/sdmse/deduplicator/core/scanner/checksum/ChecksumSleuth.kt)、同一 [LICENSE](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/LICENSE)；[Java InputStream 文档](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/InputStream.html) | 参考按大小分组后读取完整 SHA-256；读取失败不发布重复组。标准正长度 InputStream 读取应读取至少一字节或报告结束/异常，连续 0 属于异常提供器行为。 | 在白泽既有前缀/完整摘要和 O_NOFOLLOW 文件描述符核验上独立限制空转；容忍短暂零进展，连续三次拒绝该文件。纳秒缺失或实际完整元组未复现时，继续依赖新鲜内容读取；失败保留文件。正常读取行为和 minSdk 26 不变。 |
| 目录界面：SD Maid SE [ContentScreen.kt](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/app-tool-analyzer/src/main/java/eu/darken/sdmse/analyzer/ui/storage/content/ContentScreen.kt)、[ContentInfoBanner.kt](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/app-tool-analyzer/src/main/java/eu/darken/sdmse/analyzer/ui/storage/content/ContentInfoBanner.kt)、同一 [LICENSE](https://github.com/d4rken-org/sdmaid-se/blob/21642bab7fff3dccc02fe1e2e69fa3056e205092/LICENSE) | 参考当前内容的计数及只读/受限说明，让统计覆盖与操作能力可以分别判断。 | 使用白泽原有统计和界面组件，当前目录汇总来自已经读到的扫描结果；不复制上游布局、文字或图标，不把未索引目录变成可删除文件。 |

## 证据边界

- 本轮设备都为隔离 x86_64 AVD，ARM64 真机、OEM 权限界面、厂商强杀仍未验证。
- 真实同大小/可控 mtime/MediaStore 秒时间戳碰撞，供应 stat 身份的合成碰撞和完整真实 stat 元组碰撞分开记录。
  API 26 的缺失纳秒（-1）不当作观测到相等纳秒；没有真实完整元组观察时继续明确未验证。
- 真机跨 UID Root Binder 仍未验证，Shizuku root job 不替代这项证明。
- 无进展流仅有合成回归，阻塞在底层 read 内部的厂商 I/O 并未由本轮证据证明可立即中断。
- 所有字节数为夹具/扫描逻辑占用，不作为用户设备实际释放量。继续区分无法测量、回收站未释放、
  可信真实 0 与保护/跳过/失败；不推造容量，不为验收执行用户数据永久删除。
