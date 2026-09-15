# 白泽 UI：以洛书当前活动页面为准

## 固定参考来源

仓库 `xgl34222220-ops/LuoShu`，当前 `main` 基线提交 `f67ea0ca327151064acbf774952648247ad85fcb`。

实际首页调用链：`LuoShuAppShell -> HomeRoute -> HomeScreenCompact`。`ui/home/HomeScreenMiuix.kt` 是旧实现，不是当前首页；不能再按这个文件重做白泽。

实际设置调用链：`AppearanceSettingsRoute -> SettingsHubRoute -> SettingsHome`，定义在 `ui/settings/SettingsHubScreen.kt`。

参考及适配的源文件：
- `ui/home/HomeScreenCompact.kt`：状态胶囊、主卡渐变、单一主操作、双列工具卡。
- `ui/settings/SettingsHubScreen.kt`：状态概览、导航分组、独立详情页；不是整屏展开表单。
- `ui/theme/LuoShuCompactLayout.kt`：26/34sp 主标题、22/30sp 详情标题，64dp 最小头栏。
- `ui/theme/LuoShuIconSystem.kt`：48dp 触控槽、44dp 圆形表面、21dp 头栏图标。
- `ui/theme/LuoShuTheme.kt`：MIUIX 字号、生成色板和页面/卡片层级；保留 Monet、主题色及 AMOLED。
- `LuoShuAppShell.kt` 与 `ui/glass/LiquidGlassLens.kt`：真实背景取样、折射外壳、移动透镜及独立清晰标签层。

同一所有者项目的组件适配，保留白泽仓库原许可证；Apache-2.0 上游折射实现的声明与完整许可证位于 App assets/licenses/miuix-liquid-glass.txt。未复制字体文件、字体引擎、挂载或 Root 操作。

## 白泽接入

MIUIX 默认路径统一使用洛书设计体系：
- `HomeRoute -> LuoShuHomeScreen`
- `CleanRoute -> CleanScreenMiuix`
- `HistoryRoute -> HistoryScreenMiuix`
- `SettingsRoute -> LuoShuSettingsHub`
- `AppearanceRoute -> AppearanceScreenMiuix`

Material 页面分支继续作为独立兼容模式保留；MIUIX 路径不再借用 `Video*ScreenMiuix`、`VideoTopBar`、`VideoCard`、`VideoListRow` 或 `VideoSwitchRow` 作为主界面组件。

共用参考组件位于 `ui/miuix/LuoShuComponents.kt`。统一规则：
- 页面水平边距 20dp，主页面标题 26/34sp，详情标题 22/30sp，头栏最小高度 64dp。
- 头栏按钮采用 48dp 触控槽、44dp 圆形表面、21dp 图标。
- 主状态卡采用 28dp 圆角和 22dp 内边距；分组卡与工具卡采用 24dp 圆角。
- 导航/开关行采用 42dp 图标容器、18dp 图标、16/22sp 标题与 12/18sp 说明。
- MIUIX `headlineLarge` 与洛书保持 30/38sp，避免主数据字号缩水。

清理页保留白泽真实业务能力，但交互层级按洛书重组：手动扫描、安装包、即时缓存、文件归类、深度清理、卸载残留与规则审计集中为“手动工具”；定时模式、任务周期、安装包保留和保存操作使用统一分组卡。

记录页统一使用洛书页头、主统计卡、分组卡与记录卡，保留应用图标、垃圾分类、展开明细、历史任务和保护项回看。

设置页继续采用“概览 -> 分组导航 -> 独立详情页”，自动任务详情中的开关也统一改为 `LuoShuSwitchRow`。外观页不再使用 Video 风格组件，改为洛书页头、28dp 主题预览卡、分组开关和统一选择器。

## 底栏

`BaiZeMiuixApp` 的 MIUIX 分支接入 `LuoShuLiquidDock`，页面使用真实背景取样，标签置于折射层之外；设置详情通过 onDetailChanged 隐藏底栏，返回恢复。完整参数、门控和测试边界见 [UI-LUOSHU-DOCK.md](UI-LUOSHU-DOCK.md)。无真实模糊时使用不透明回退，不将背景透字当作玻璃效果。

## 验证要求

自动编译和交互测试不等于审美验收。CI 必须至少覆盖：
- 深浅色首页、清理页、记录页、设置页。
- 320dp 窄屏 + 1.3x 大字体。
- 首页扫描主操作、工具入口、自动清理入口。
- 设置详情返回、草稿保存、开关无障碍语义。
- 液态玻璃在无 GPU 的截图环境正确降级为不透明底栏。

Robolectric 图片使用模拟状态，不是用户手机容量或清理数据，也不证明 GPU 折射已经真机验收。合并前仍需检查 CI 产出的实际渲染图；真机后再重点看 HyperOS 深浅色、底栏折射、滚动遮挡和大字体。

UI 重构不修改清理引擎、Root 通信修复、正式版本或发布签名。发布工作流仅按目标源码选择匹配 Gradle 版本，不改发布请求文件、不自动发布。
