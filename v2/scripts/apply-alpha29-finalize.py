#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Alpha 29 final target not found: {label} ({path})")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "v2/app/build.gradle.kts",
    '        versionCode = 20800\n        versionName = "2.0.0-alpha28"',
    '        versionCode = 20900\n        versionName = "2.0.0-alpha29"',
    "App version",
)
replace_once(
    "v2/module/module.prop",
    'version=v2.0.0-alpha28\nversionCode=20800',
    'version=v2.0.0-alpha29\nversionCode=20900',
    "module version",
)
replace_once(
    "v2/module/customize.sh",
    'ui_print "- 安装白泽 v2 Alpha 28 原生快照清理版"',
    'ui_print "- 安装白泽 v2 Alpha 29 应用图标与清理明细版"',
    "installer title",
)
replace_once(
    "v2/scripts/package-module.sh",
    'OUTPUT="$OUT/BaiZe-v2-Alpha28-Module.zip"',
    'OUTPUT="$OUT/BaiZe-v2-Alpha29-Module.zip"',
    "package filename",
)
replace_once(
    "v2/scripts/package-module.sh",
    'echo "已生成 Alpha 28 原生扫描快照、单遍历安全扫描与完整规则库模块：$OUTPUT"',
    'echo "已生成 Alpha 29 应用图标、分类清理明细与完整清理引擎模块：$OUTPUT"',
    "package message",
)
replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt",
    'item { PageHeader("SMART CLEAN", "白泽", "原生清理引擎 · Alpha 28", actions.refresh) }',
    'item { PageHeader("SMART CLEAN", "白泽", "原生清理引擎 · Alpha 29", actions.refresh) }',
    "home subtitle",
)

readme = Path("v2/README.md")
text = readme.read_text(encoding="utf-8")
old_header = '''# 白泽 v2 Alpha 25

白泽是面向 Magisk / KernelSU / APatch 的原生 Android 清理模块。App 通过 libsu RootService 调用模块清理引擎，不依赖 WebUI。

当前开发分支：`v2-alpha25`。

## Alpha 25
'''
new_header = '''# 白泽 v2 Alpha 29

白泽是面向 Magisk / KernelSU / APatch 的原生 Android 清理模块。App 通过 libsu RootService 调用模块清理引擎，不依赖 WebUI。

当前开发分支：`v2-alpha29`。

## Alpha 29

- 清理结果按应用与垃圾分类记录实际删除文件、实际释放空间、未清理数量和示例路径。
- 重叠缓存与扩展规则使用规范化路径去重，避免重复删除和重复统计。
- 记录页读取应用真实图标；图标在后台线程加载并使用有界内存缓存，读取失败自动显示占位图。
- 应用结果卡片使用简洁分类标签，点击后展开内部缓存、外部缓存、WebView、空文件等分类明细。
- 增加本次清理汇总、最大垃圾来源，并修复悬浮底栏遮挡最后一张结果卡片的问题。
- 保持 Alpha 28 已验证的一键 Root 清理主链路，不重做首页连接逻辑。

## Alpha 25
'''
if new_header not in text:
    if old_header not in text:
        raise SystemExit("README Alpha 25 header not found")
    readme.write_text(text.replace(old_header, new_header, 1), encoding="utf-8")

changes = Path("v2/ALPHA29-CHANGES.md")
if not changes.exists():
    changes.write_text(
        '''# Alpha 29 改动摘要

## 清理数据

- 新增 `reports/app-items-latest.tsv`，按应用和分类保存实际文件数、实际字节、失败数和示例路径。
- 对应用缓存、扩展缓存和空项目候选执行规范化路径去重，避免重复统计。
- RootService 返回兼容旧界面的应用汇总，并新增结构化 `categories` 明细。

## App 与 UI

- 读取已安装应用真实图标，使用后台线程和 96 项 LRU 内存缓存。
- 图标读取失败、应用卸载或包不可见时安全回退到白泽占位图。
- 应用垃圾卡片增加分类标签、未清理数量和可展开分类明细。
- 记录页增加本次清理汇总和最大垃圾来源。
- 记录列表底部改用系统导航栏动态安全间距，避免悬浮底栏遮挡内容。

## 稳定性

- 保持 Alpha 28 的单 RootService 一键清理主链路。
- 不修改首页按钮行为，不重新引入双快照引擎依赖。
''',
        encoding="utf-8",
    )

print("Alpha 29 final version migration applied")
