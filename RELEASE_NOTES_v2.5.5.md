# 白泽 v2.5.5

v2.5.5 是针对模块卸载后 `/data/adb` 残留问题的正式热修版本。

## 修复

- 修复卸载脚本使用 `pkill -f /data/adb/modules/baize_v2` 时可能把自身终止，导致后续目录清理没有执行的问题。
- 改为枚举 `/proc` 精确终止白泽后台进程，并明确跳过卸载脚本自身和 Root 管理器父进程。
- 卸载时停止调度器、守护进程、扫描器、清理器和文件归类 Worker。
- 首轮停止后再次终止并清理，防止旧后台进程重新创建 `/data/adb/baize-v2`。
- 删除 `/data/adb/baize-v2`、旧 `/data/adb/safesweep`、新旧 `modules_update` 暂存目录以及旧 `safesweep` 模块目录。
- 同时卸载白泽 App。
- 卸载前自动恢复隔离区中的用户文件；原路径冲突时转存至 `内部存储/Download/BaiZe恢复`。
- 当前 `/data/adb/modules/baize_v2` 模块本体仍按 Magisk、KernelSU、APatch 标准流程在重启后删除。

## 验证

- 动态测试从模拟的 `/data/adb/modules/baize_v2/uninstall.sh` 自身执行卸载，确认脚本不会自杀。
- 模拟后台进程在收到停止信号后重新创建状态目录，确认第二轮清理仍能彻底删除。
- 验证隔离区文件恢复、新旧目录清理、App 卸载调用、非白泽目录保护和标准 `remove` 标记。
- 正式封包强制检查 `uninstall.sh` 可执行权限与卸载清理合同。

安装 v2.5.5 后再执行卸载，并在 Root 管理器中完成重启，模块本体目录才会由管理器最终移除。
