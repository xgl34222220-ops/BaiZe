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
