# 白泽 v2.6.2

本版本继续修复 KernelSU/MMRL metainstall 环境下的刷入兼容问题。

## 修复

- 修复 v2.6.1 已完成 ABI 匹配、APK 校验和 App 安装后，仍可能被 Root 管理器判定为“Failed to install module script / 错误代码 1”的问题。
- `installed-app.sha256` 只是辅助状态记录；写入失败现在只给提示，不再让整个模块安装失败。
- `customize.sh` 在所有必须项校验和安装流程完成后显式 `exit 0`，避免最后一条辅助命令污染整个安装结果。
- 保留关键文件、ABI、APK 完整性等硬校验；真正缺失关键组件时仍会立即 abort。
- 新增安装脚本最终退出码回归测试，并保留 v2.6.1 的原生引擎执行权限修复。

## 在线更新

在 Magisk、KernelSU 或 APatch 模块页面刷新后更新到 v2.6.2。在线更新继续使用 GitHub Raw 稳定镜像。
