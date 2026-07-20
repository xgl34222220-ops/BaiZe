from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work")

controller = root / "app/src/main/java/io/github/xgl34222220/bagua/BaguaController.kt"
s = controller.read_text()
old = '                exec sh "${\'$\'}SCRIPT" ${args.joinToString(" ") { shellQuote(it) }}'
new = '                sh "${\'$\'}SCRIPT" ${args.joinToString(" ") { shellQuote(it) }}'
if old not in s:
    raise SystemExit("missing exec bridge anchor")
s = s.replace(old, new)
controller.write_text(s)

screens = root / "app/src/main/java/io/github/xgl34222220/bagua/Screens.kt"
s = screens.read_text()
old = 'StatusCard("模块检测命令执行失败", "模块并非未安装。请重新检测；仍失败时前往诊断中心导出报告。", Icons.Outlined.Info, true)'
new = 'StatusCard("模块检测命令执行失败", "${s.raw[\"COMMAND_ERROR\"] ?: \"未知错误\"}。模块并非未安装，请重新检测。", Icons.Outlined.Info, true)'
if old not in s:
    raise SystemExit("missing command error card anchor")
screens.write_text(s.replace(old, new))

for path in root.rglob("*"):
    if not path.is_file() or path.suffix.lower() in {".apk", ".zip", ".png", ".jpg", ".jpeg", ".webp"}:
        continue
    try:
        text = path.read_text()
    except UnicodeDecodeError:
        continue
    changed = text.replace("0.6.1-alpha.6.1", "0.6.2-alpha.6.2")
    changed = changed.replace("versionCode = 108", "versionCode = 109")
    changed = changed.replace("versionCode=108", "versionCode=109")
    if changed != text:
        path.write_text(changed)

changelog = root / "module-src/CHANGELOG.md"
changelog.write_text(
    "# 更新日志\n\n"
    "## 0.6.2-alpha.6.2\n\n"
    "- 修复 Root 桥接命令使用 exec 导致 libsu 持久 Shell 被替换的问题\n"
    "- 模块命令改为普通 sh 调用并保留真实退出码\n"
    "- 检测失败时直接显示底层错误内容\n"
    "- 保留 Alpha 6.1 的多行脚本修复和 Alpha 6 全部功能\n\n"
    + changelog.read_text()
)

print("patched Alpha 6.2")
