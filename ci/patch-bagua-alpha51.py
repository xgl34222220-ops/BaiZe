from pathlib import Path
import sys

r = Path(sys.argv[1] if len(sys.argv) > 1 else "work")


def rw(path: Path, pairs):
    p = r / path
    s = p.read_text()
    for old, new in pairs:
        if old not in s:
            raise SystemExit(f"missing anchor {path}: {old[:60]}")
        s = s.replace(old, new)
    p.write_text(s)


rw(
    Path("app/build.gradle.kts"),
    [
        ("versionCode = 105", "versionCode = 106"),
        ('versionName = "0.5.0-alpha.5"', 'versionName = "0.5.1-alpha.5.1"'),
    ],
)
rw(
    Path("module-src/module.prop"),
    [
        ("version=0.5.0-alpha.5", "version=0.5.1-alpha.5.1"),
        ("versionCode=105", "versionCode=106"),
        (
            "description=原生 App-only 单包架构｜内置八卦 App｜Material / Miuix 双界面｜不再依赖 WebUI",
            "description=原生 App-only 单包架构｜修复 Root 授权与模块识别｜Material / Miuix 双界面｜不依赖 WebUI",
        ),
    ],
)
rw(
    Path("module-src/scripts/bagua.sh"),
    [('VERSION="0.4.0-alpha.4"', 'VERSION="0.5.1-alpha.5.1"')],
)
rw(
    Path("scripts/package-module.sh"),
    [
        (
            "BaGua-v0.5.0-alpha.5-AppOnly-Module.zip",
            "BaGua-v0.5.1-alpha.5.1-AppOnly-Module.zip",
        ),
        (
            "BaGua-v0.5.0-alpha.5-AppOnly-Source.zip",
            "BaGua-v0.5.1-alpha.5.1-AppOnly-Source.zip",
        ),
        (
            "BaGua-v0.5.0-alpha.5-SHA256.txt",
            "BaGua-v0.5.1-alpha.5.1-SHA256.txt",
        ),
    ],
)
rw(
    Path("app/src/main/java/io/github/xgl34222220/bagua/Models.kt"),
    [
        (
            "    val installed: Boolean = false,\n",
            "    val installed: Boolean = false,\n    val accessIssue: String = \"\",\n",
        )
    ],
)

p = r / "app/src/main/java/io/github/xgl34222220/bagua/BaguaController.kt"
s = p.read_text()
s = s.replace(
    ".setFlags(Shell.FLAG_REDIRECT_STDERR)",
    ".setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)",
)

anchor = '''    fun refreshStatus() {
        scope.launch {
            val result = runCommand(listOf("status"))
            status = parseStatus(result)
        }
    }
'''
replacement = anchor + '''
    fun retryRoot() {
        scope.launch {
            loading = true
            lastError = ""
            try { Shell.getCachedShell()?.close() } catch (_: Throwable) {}
            val result = runCommand(listOf("status"))
            status = parseStatus(result)
            if (!result.success) lastError = friendlyError(result)
            if (status.installed) {
                sources = parseSources(runCommand(listOf("source", "list", "all")).stdout)
                whitelist = parsePlainList(runCommand(listOf("whitelist", "list")).stdout)
                blacklist = parsePlainList(runCommand(listOf("blacklist", "list")).stdout)
            }
            loading = false
        }
    }
'''
if anchor not in s:
    raise SystemExit("refresh anchor")
s = s.replace(anchor, replacement)

start = s.index(
    "    private suspend fun runCommand(args: List<String>): CommandResult = withContext(Dispatchers.IO) {"
)
end = s.index("\n    private fun shellQuote", start)
new_run = '''    private suspend fun runCommand(args: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            val rootShell = Shell.getShell()
            if (!rootShell.isRoot) return@withContext CommandResult(126, "ERROR=root_required\\nROOT_STATUS=${rootShell.status}", "八卦 App 未获得 Root 权限")
            val script = """
                SCRIPT=
                for DIR in /data/adb/modules/bagua /data/adb/modules_update/bagua; do
                  [ -f "${'$'}DIR/scripts/bagua.sh" ] && { SCRIPT="${'$'}DIR/scripts/bagua.sh"; break; }
                done
                if [ -z "${'$'}SCRIPT" ]; then
                  for PROP in /data/adb/modules/*/module.prop /data/adb/modules_update/*/module.prop; do
                    [ -f "${'$'}PROP" ] || continue
                    grep -q '^id=bagua${'$'}' "${'$'}PROP" 2>/dev/null || continue
                    DIR=${'$'}{PROP%/module.prop}
                    [ -f "${'$'}DIR/scripts/bagua.sh" ] && { SCRIPT="${'$'}DIR/scripts/bagua.sh"; break; }
                  done
                fi
                [ -n "${'$'}SCRIPT" ] || { echo ERROR=module_not_installed; exit 127; }
                exec sh "${'$'}SCRIPT" ${args.joinToString(" ") { shellQuote(it) }}
            """.trimIndent().replace("\\n", "; ")
            val result = rootShell.newJob().add(script).exec()
            CommandResult(result.code, result.out.joinToString("\\n"), result.err.joinToString("\\n"))
        } catch (t: Throwable) { CommandResult(126, "ERROR=root_required", t.message ?: t.javaClass.simpleName) }
    }
'''
s = s[:start] + new_run + s[end:]

old_parse = '''        val map = parseKeyValues(result.stdout)
        if (result.code == 127 || map["ERROR"] == "module_not_installed") return ModuleStatus(installed = false)
'''
new_parse = '''        val map = parseKeyValues(result.stdout)
        if (result.code == 126 || map["ERROR"] == "root_required") return ModuleStatus(installed = false, accessIssue = "root_required", raw = map)
        if (result.code == 127 || map["ERROR"] == "module_not_installed") return ModuleStatus(installed = false, accessIssue = "module_not_installed", raw = map)
'''
if old_parse not in s:
    raise SystemExit("parse anchor")
s = s.replace(old_parse, new_parse).replace(
    '            "module_not_installed" -> "没有检测到八卦模块，请先刷入内置 App 的模块包。"',
    '            "root_required" -> "八卦 App 没有 Root 权限，请在根管理器的超级用户页面允许八卦，然后重试。"\n            "module_not_installed" -> "已获得 Root 权限，但没有找到八卦模块，请重新刷入模块包并重启。"',
)
p.write_text(s)

p = r / "app/src/main/java/io/github/xgl34222220/bagua/Screens.kt"
s = p.read_text()
old_screen = '''        if (!s.installed) {
            item {
                StatusCard(
                    title = "没有检测到模块",
                    subtitle = "请刷入八卦 App-only 模块。App 不再依赖 WebUI。",
                    icon = Icons.Outlined.Info,
                    emphasized = true
                )
            }
            return@LazyColumn
        }
'''
new_screen = '''        if (!s.installed) {
            item {
                if (s.accessIssue == "root_required") {
                    SectionCard("需要 Root 授权") {
                        StatusCard("模块已安装，但 App 没有 Root 权限", "请到根管理器的‘超级用户’页面允许八卦，再点下方按钮。", Icons.Outlined.Shield, true)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::retryRoot, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重新请求 Root 并检测")
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
            }
            return@LazyColumn
        }
'''
if old_screen not in s:
    raise SystemExit("screen anchor")
p.write_text(s.replace(old_screen, new_screen))

p = r / "module-src/CHANGELOG.md"
old = p.read_text()
p.write_text(
    "# 更新日志\n\n"
    "## 0.5.1-alpha.5.1\n\n"
    "- 修复未授予 Root 时被误报为‘没有检测到模块’\n"
    "- 明确区分 Root 未授权和模块不存在\n"
    "- 增加重新请求 Root、模块 ID 回退扫描和 mount-master 兼容\n"
    "- 保留 Alpha 5 的全部配置与功能\n\n"
    + old
)

p = r / "README.md"
p.write_text(p.read_text().replace("0.5.0-alpha.5", "0.5.1-alpha.5.1"))
print("patched alpha51")
