from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work")

controller = root / "app/src/main/java/io/github/xgl34222220/bagua/BaguaController.kt"
s = controller.read_text()
broken = '""".trimIndent().replace("\\n", "; ")'
if broken not in s:
    raise SystemExit("missing broken Root script join anchor")
s = s.replace(broken, '""".trimIndent()')

module_missing = 'if (result.code == 127 || map["ERROR"] == "module_not_installed") return ModuleStatus(installed = false, accessIssue = "module_not_installed", raw = map)'
command_failed = module_missing + '\n        if (!result.success && map["ERROR"].isNullOrBlank()) return ModuleStatus(installed = false, accessIssue = "command_failed", raw = map + ("COMMAND_ERROR" to result.stderr.ifBlank { "exit=${result.code}" }))'
if module_missing not in s:
    raise SystemExit("missing parse status anchor")
s = s.replace(module_missing, command_failed)
controller.write_text(s)

screens = root / "app/src/main/java/io/github/xgl34222220/bagua/Screens.kt"
s = screens.read_text()
old = '''                } else {
                    SectionCard("模块连接") {
                        StatusCard("已获得 Root，但没有找到八卦模块", "请重新刷入八卦 App-only 模块并重启设备。", Icons.Outlined.Info, true)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::refreshAll, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重新检测")
                        }
                    }
                }
'''
new = '''                } else if (s.accessIssue == "command_failed") {
                    SectionCard("检测异常") {
                        StatusCard("模块检测命令执行失败", "模块并非未安装。请重新检测；仍失败时前往诊断中心导出报告。", Icons.Outlined.Warning, true)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::refreshAll, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重新检测")
                        }
                    }
                } else {
                    SectionCard("模块连接") {
                        StatusCard("已获得 Root，但没有找到八卦模块", "请重新刷入八卦 App-only 模块并重启设备。", Icons.Outlined.Info, true)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::refreshAll, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重新检测")
                        }
                    }
                }
'''
if old not in s:
    raise SystemExit("missing module connection UI anchor")
screens.write_text(s.replace(old, new))

for path in root.rglob("*"):
    if not path.is_file() or path.suffix.lower() in {".apk", ".zip", ".png", ".jpg", ".jpeg", ".webp"}:
        continue
    try:
        text = path.read_text()
    except UnicodeDecodeError:
        continue
    changed = text.replace("0.6.0-alpha.6", "0.6.1-alpha.6.1")
    changed = changed.replace("versionCode = 107", "versionCode = 108")
    changed = changed.replace("versionCode=107", "versionCode=108")
    if changed != text:
        path.write_text(changed)

changelog = root / "module-src/CHANGELOG.md"
changelog.write_text(
    "# 更新日志\n\n"
    "## 0.6.1-alpha.6.1\n\n"
    "- 修复 App 将多行 Root 检测脚本压成非法单行命令的问题\n"
    "- 修复模块已安装启用却被误报为未找到\n"
    "- 新增检测命令执行失败的独立状态，不再冒充模块未安装\n"
    "- 保留 Alpha 6 的诊断中心与全部配置\n\n"
    + changelog.read_text()
)

print("patched Alpha 6.1")
