# 白泽 UI：以洛书当前活动页面为准

## 固定参考来源

仓库 `xgl34222220-ops/LuoShu`，提交 `fe4df5f224dca9bc77517ffa539ba021ed4b1a8e`。

实际首页调用链：`LuoShuAppShell -> HomeRoute -> HomeScreenCompact`。`ui/home/HomeScreenMiuix.kt` 是旧实现，不是当前首页；不能再按这个文件重做白泽。

实际设置调用链：`AppearanceSettingsRoute -> SettingsHubRoute -> SettingsHome`，定义在 `ui/settings/SettingsHubScreen.kt`。

参考及适配的源文件：
- `ui/home/HomeScreenCompact.kt`：状态胶囊、主卡渐变、单一主操作、双列工具卡。
- `ui/settings/SettingsHubScreen.kt`：状态概览、导航分组、独立详情页；不是整屏展开表单。
- `ui/theme/LuoShuCompactLayout.kt`：26/34sp 主标题、22/30sp 详情标题，64dp 最小头栏。
- `ui/theme/LuoShuIconSystem.kt`：48dp 触控槽、44dp 圆形表面、21dp 头栏图标。
- `ui/theme/LuoShuTheme.kt`：生成色板和页面/卡片层级；保留 Monet、主题色及 AMOLED。
- `LuoShuAppShell.kt` 与 `ui/glass/LiquidGlassLens.kt`：真实背景取样、折射外壳、移动透镜及独立清晰标签层。

同一所有者项目的组件适配，保留白泽仓库原许可证；Apache-2.0 上游折射实现的声明与完整许可证位于 App assets/licenses/miuix-liquid-glass.txt。未复制字体文件、字体引擎、挂载或 Root 操作。

## 白泽接入

`HomeRoute` 在 MIUIX 下调用 `LuoShuHomeScreen`；`SettingsRoute` 在 MIUIX 下调用 `LuoShuSettingsHub`。Material 页面分支保留旧实现。不是增加文件但不接入路由。

共用参考组件位于 `ui/miuix/LuoShuComponents.kt`。首页主卡为 28dp 圆角、22dp 内边距、18dp 内部节奏；工具卡为 24dp 圆角、18dp 内边距及 44dp 图标容器。设置导航行采用 42dp 图标容器、18dp 图标、16/22sp 标题及 12/18sp 说明。

清理执行条件、归类条件、通知及两个数值编辑器移入独立任务设置页。原有保存草稿、轮询状态合并和回调继续由 SettingsRoute 管理；开关不会隐式保存。

## 底栏

`BaiZeMiuixApp` 的 MIUIX 分支已接入 `LuoShuLiquidDock`，页面使用真实背景取样，标签置于折射层之外；设置详情通过 onDetailChanged 隐藏底栏，返回恢复。完整参数、门控和测试边界见 [UI-LUOSHU-DOCK.md](UI-LUOSHU-DOCK.md)。无真实模糊时使用不透明回退，不将背景透字当作玻璃效果。

## 不得混同的验证

自动编译和交互测试不代表审美验收；Robolectric 图片使用模拟状态，不是用户手机容量或清理数据，也不证明 GPU 折射已经真机验收。须检查实际入口渲染，并回归窄屏大字、深浅色、断线重连、工具回调、设置返回和草稿保存。

UI 重构不修改清理引擎、Root 通信修复、正式版本或发布签名。发布工作流仅按目标源码选择匹配 Gradle 版本，不改发布请求文件、不自动发布。
