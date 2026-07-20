from pathlib import Path

replacements = {
    "v2/app/build.gradle.kts": [
        ('versionCode = 22405', 'versionCode = 22406'),
        ('versionName = "2.1.0-alpha5"', 'versionName = "2.1.0-alpha6"'),
    ],
    "v2/module/module.prop": [
        ('version=v2.1.0-alpha5', 'version=v2.1.0-alpha6'),
        ('versionCode=22405', 'versionCode=22406'),
        (
            'description=白泽 v2.1.0 Alpha 5：按本机历史吞吐自适应选择串行/双工作进程，保留原子快照与路径索引 One-pass。',
            'description=白泽 v2.1.0 Alpha 6：原生 App 展示并控制本机扫描策略，可重置基准重新学习。',
        ),
    ],
    "v2/module/service.sh": [('module_version=2.1.0-alpha5', 'module_version=2.1.0-alpha6')],
    "v2/module/one-pass-scan.sh": [('43.4-alpha5-adaptive-workers', '43.5-alpha6-performance-panel')],
    "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeRootService.kt": [
        ('v2.1.0 Alpha 5 adaptive', 'v2.1.0 Alpha 6 performance-panel adaptive'),
        ('native-c-arm64-cache-v43.4-alpha5-adaptive-workers', 'native-c-arm64-cache-v43.5-alpha6-performance-panel'),
    ],
    "v2/scripts/package-module.sh": [
        ('BaiZe-v2.1.0-alpha5-Module.zip', 'BaiZe-v2.1.0-alpha6-Module.zip'),
        ('^version=v2.1.0-alpha5$', '^version=v2.1.0-alpha6$'),
        ('^versionCode=22405$', '^versionCode=22406$'),
        ('已生成白泽 v2.1.0 Alpha 4 有限并发性能预览模块', '已生成白泽 v2.1.0 Alpha 6 原生性能策略面板模块'),
    ],
}

for filename, pairs in replacements.items():
    path = Path(filename)
    text = path.read_text()
    for old, new in pairs:
        if old not in text:
            raise SystemExit(f"missing replacement in {filename}: {old}")
        text = text.replace(old, new)
    path.write_text(text)

Path("RELEASE_NOTES_V2.md").write_text(
    """# 白泽 v2.1.0 Alpha 6

## 原生性能策略面板

- 清理页新增扫描性能策略面板，MIUIx 与 Material 两套界面同步。
- 显示当前策略、实际工作进程、本机推荐、串行/双进程吞吐、提升比例、学习次数和下次复测。
- 可选择自动推荐、固定串行或固定双工作进程；保存后写入模块配置。
- 可只清除 `root-worker-profile.env` 重新学习，不删除扫描快照、历史记录或白名单。
- 自动模式继续首次串行、工作量门槛、15% 提升阈值、周期复测和失败冷却。
- 扫描与清理继续完全分离；固定双进程只影响扫描阶段。
- 保留路径索引、Android/data One-pass、原子快照和扫描后变更保护。

## 版本

- 模块：`v2.1.0-alpha6`
- App：`2.1.0-alpha6`
- versionCode：`22406`
- 扫描策略封装：`43.5-alpha6-performance-panel`
"""
)
