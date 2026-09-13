# 白泽：洛书同源底栏

参考固定为 `xgl34222220-ops/LuoShu@fe4df5f224dca9bc77517ffa539ba021ed4b1a8e` 的 `LuoShuAppShell.kt`（MiuixAppDock / AppDockLayout）和 `ui/glass/LiquidGlassLens.kt`。

## 接入而非独立样例

`BaiZeMiuixApp` 的 MIUIX 路由实际调用 `LuoShuLiquidDock`。页面背景与当前内容通过 `layerBackdrop` 取样；外壳单独录制给移动透镜使用；图标与标签处于两层取样及着色器之外。Material 保留旧底栏，不跟随替换。

常规字体大小采用 72dp 总高度、60dp 项目高度、20dp 左右外边距、系统导航 inset + 12dp 底部距离、6dp 内边距。外壳 31dp 连续圆角，透镜 23dp 连续圆角；图标 22dp，标签 12/17sp。大字体只增加必要高度，不压扁或裁切标签。

光学参数保留洛书：外壳 9dp blur、17dp refractionHeight、13dp refractionAmount、.045 色散；透镜 3dp blur、13→17dp 高度、14→19dp 折射、.08→.18 色散；最大拉伸 13dp，选中位移弹簧 .68/310，按压缩放 .92，玻璃选中缩放 1.035。修正非对称圆角着色器坐标并对 sqrt 输入作浮点保护。

## 降级和导航

仅在 API 33+、硬件加速、玻璃与模糊开启、且性能策略允许时初始化 runtime backdrop。API 31-32 可走真实 Haze；无硬件加速、低版本、关闭效果或 AMOLED 纯黑时使用不透明表面，不能透出后方文字。关闭取样时不保留每帧运行的折射动画。

设置的自动任务、连接诊断详情通过 `onDetailChanged` 隐藏底栏，返回恢复原选中项；详情不保留悬浮底栏的额外空白。进入/返回使用洛书同参数侧滑。退出设置组件时清理详情可见状态。

## 构建与测试边界

同源组件使用 miuix-blur-android / miuix-squircle-android 0.9.3，需要 compileSdk 37 和 Kotlin 2.4；构建使用 AGP 9.3.2、Gradle 9.5、Kotlin/Compose compiler 2.4.10。minSdk 26、targetSdk 36、签名检查与版本号不改。API 33 blur 的 manifest override 只配合实际能力门控使用。

状态模型从原界面文件原样移动到 DashboardUiModels.kt，JSON 序列化和清理回调不改。Root 服务、清理规则、调度执行代码不改。

自动测试新增：渲染能力策略、四标签导航不执行清理、详情进入/系统返回/标题返回、旧 Android 回退、深色回退，以及改变后方背景后底栏内部像素不变的真实位图断言。Robolectric 截图只证明布局和回退，不等于 K80 至尊版/一加 15 的 GPU 折射真机验证。

## 第三方声明

折射着色器由洛书改写自 compose-miuix-ui/miuix LiquidGlass Lens 示例，其原始来源为 Kyant0/AndroidLiquidGlass；上述上游实现使用 Apache License 2.0。保留源码 SPDX 标识，修改包括包名、合并有无色散分支、非对称圆角坐标修正和浮点保护；不包含字体文件。

来源：https://github.com/compose-miuix-ui/miuix ，https://github.com/Kyant0/AndroidLiquidGlass 。许可证：https://www.apache.org/licenses/LICENSE-2.0 。

此文是实现与验证说明，不表示已合并主分支或发布正式版。
