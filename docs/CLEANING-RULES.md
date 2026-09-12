# 手动清理规则与扫描体验

调研日期：2026-09-07。

参考 SD Maid SE 的 SystemCleaner 与 AppCleaner 区分：普通存储垃圾按路径/属性识别，应用日志结合应用包名与相对目录识别，结果需要展示来源并允许选择。

来源：
- https://github.com/d4rken-org/sdmaid-se/wiki/SystemCleaner
- https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/forensics/filter/BugReportingFilter.kt （审查版本 blob `efd9e709118d89bf253c6b49e890dc01aed62662`）
- https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/forensics/filter/AnalyticsFilter.kt
- 对照查看了 https://github.com/FLYCOM-E/ClearBox 和 https://github.com/S123123sd/SmartClear 的项目说明；未整库导入其规则。

本次独立整理 `config/review.rules`，覆盖 Crashlytics、Bugly、应用运行/推送诊断和 QQ 诊断日志。它用于手动扫描；没有把整个 files、Download、聊天附件或离线下载目录作为新增自动删除规则。规则中的应用目录名是匹配事实，没有复制第三方引擎实现。

交互调整：主页手动扫描与深度扫描进入同一选择页，展示应用图标、分类、完整路径、风险与不清理原因；高风险默认不选，允许明确逐项选择，关键数据与白名单继续解释原因。结果在清理后和重进 App 后保留；过期结果仍可查看，重新扫描后才可执行清理。

性能调整：同一轮规则展开复用目录列表；大小测量采用整份快照共用的预算，避免逐页重复等待；直接读取普通文件的大小；不再静默截断前 2,000 条结果。未完成大小测量的目录标记为待测，不能把未知大小伪装为零垃圾。速度变化仍需以用户设备和实际文件量验证。

## 2026-09-12：手动扫描规则接入修复

重新审查发现，旧手动规则读取器只接受以 `/` 开头的行，实际应用库却是 `包名|相对路径|保留天数`，隐藏库是 `dir/file|名称|保留天数`。因此，后台能识别的数百条扩展规则没有接入手动扫描；增加深度规则条数并不能解决这个问题。

现在按各文件的真实格式分别解析，并通过已有扫描候选、风险选择和服务端快照清理链执行：

| 规则来源 | 校验后的有效条数 | 本次处理 |
| --- | ---: | --- |
| 应用扩展 | 299 | 接入手动扫描，覆盖多用户内部与设备保护存储 |
| 外部应用扩展 | 159 | 接入手动扫描，覆盖各用户 `Android/data` |
| 隐藏目录/文件 | 21 | 替换写死的 6 个目录名，恢复元数据文件及明确相册缩略图入口 |
| 深度规则 | 4,714 | 保留现有分级库，无机械扩容 |
| 风险覆盖 | 232 | 保留现有策略 |
| 手动诊断 | 35 | 原 10 条补齐设备保护存储、`MiPushLog` 大小写及明确日志目录 |

以上是去掉注释、空行、重复项并完成格式校验后的条数，不能相加当作互不重叠的可删除目标数；多个规则及存储别名可能指向同一路径，扫描候选按规范路径去重。

手动规则扫描同时使用多进程 WebView 的 HTTP/GPU/代码缓存与已完成崩溃报告路径；不匹配 Cookies、Local Storage、IndexedDB 或整个 WebView 数据。包名相对规则保留所属应用边界，中间软链接不会被规则展开穿透。主用户的 `/data/data` 同时作为明确兼容入口，覆盖 `/data/user/0` 使用系统软链接的 Android 布局，不因此放行应用内部软链接。

隐藏垃圾按文件年龄收集，嵌套目录使用祖先中最长保留期，不把零天 `.cache` 整目录加入隐藏删除快照；`.Trash/.cache` 与 `.cache/.Trash` 中未满 30 天的内容均保留。候选另存 `retentionDays`，清理前复核修改时间，展示文案不参与授权。即使单独选择更宽的目录规则，执行器也对每个文件复核隐藏目录保留期及标记文件保护；规则只在任务开始时解析一次。下载、媒体和文档区域仍受现有遍历边界限制，缩略图只增加明确的 `DCIM/.thumbnails` 与 `Pictures/.thumbnails`。

参考复核：[SD Maid SE AppCleaner](https://github.com/d4rken-org/sdmaid-se/wiki/AppCleaner) 的应用归属与可重建文件定义，以及其 [BugReportingFilter](https://github.com/d4rken-org/sdmaid-se/blob/main/app-tool-appcleaner/src/main/java/eu/darken/sdmse/appcleaner/core/forensics/filter/BugReportingFilter.kt) 中按路径分段识别诊断日志、Crashlytics 和推送日志的做法。这里独立编写解析与匹配逻辑，只核对目录语义，没有复制第三方过滤器实现或整库导入规则。

回归覆盖位于 `NativeProfileRuleCoverageTest`：使用项目实际规则文件生成应用、多用户、设备保护与外部目录夹具，验证 scan/page 的真实命中；验证中间软链接/非法相对路径拒绝、WebView 会话数据保留、元数据与缩略图覆盖、按文件年龄筛选、近期回收站文件不被普通碎片匹配绕过。规则校验脚本也加入手动诊断库，避免这份数据再次游离在构建检查之外。验证结果以对应构建日志为准，未连接真机评估可清理字节数或扫描耗时。
