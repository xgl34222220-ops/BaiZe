# Alpha 33 双皮肤第一阶段

- 新增 `UiStyle.MATERIAL / UiStyle.MIUIX`，使用 Preferences DataStore 持久化并由 `AppearanceViewModel` 全局暴露。
- 增加 `LocalAppearanceSettings` 与 `BaiZeTheme`，Material、Miuix 共用种子色、三态明暗、纯黑、玻璃和取色风格。
- Material 主题通过 MaterialKolor 2.0.0 生成柔和、鲜艳、中性三套算法色板。
- 首页在 `HomeRoute` 按界面风格分流：原有首页作为 Miuix 实现，新建 Material 3 玻璃拟态首页。
- 主题设置新增“界面风格”选项，切换后主界面即时换皮。
- RootService、扫描、清理、历史数据和脚本业务逻辑均未修改。
