# 白泽 v2 Alpha 35：清理类别双皮肤第一步

- 新增共享 `CleanUiState`、`CleanUiActions` 和清理类别映射。
- 第二个底栏入口由“计划”改为“清理”。
- Material 使用独立的 Material 3 清理类别页。
- Miuix 使用独立的紧凑分组、SuperSwitch 清理类别页。
- 自动清理类别、周期、APK 设置继续复用原 `SchedulerUiState`。
- 垃圾扫描、安装包扫描、深度清理、卸载残留和明细继续复用原业务动作。
- RootService、cleaner.sh、历史记录和扫描状态机未修改。
