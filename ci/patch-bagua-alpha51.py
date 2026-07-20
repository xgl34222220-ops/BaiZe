from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work")

p = root / "app/build.gradle.kts"
s = p.read_text()
s = s.replace("versionCode = 105", "versionCode = 106")
s = s.replace('versionName = "0.5.0-alpha.5"', 'versionName = "0.5.1-alpha.5.1"')
p.write_text(s)

p = root / "module-src/module.prop"
s = p.read_text()
s = s.replace("version=0.5.0-alpha.5", "version=0.5.1-alpha.5.1")
s = s.replace("versionCode=105", "versionCode=106")
s = s.replace(
    "description=原生 App-only 单包架构｜内置八卦 App｜Material / Miuix 双界面｜不再依赖 WebUI",
    "description=原生 App-only 单包架构｜修复 Root 授权与模块识别｜Material / Miuix 双界面｜不依赖 WebUI",
)
p.write_text(s)

p = root / "module-src/scripts/bagua.sh"
s = p.read_text().replace('VERSION="0.4.0-alpha.4"', 'VERSION="0.5.1-alpha.5.1"')
p.write_text(s)

p = root / "scripts/package-module.sh"
s = p.read_text()
s = s.replace("BaGua-v0.5.0-alpha.5-AppOnly-Module.zip", "BaGua-v0.5.1-alpha.5.1-AppOnly-Module.zip")
s = s.replace("BaGua-v0.5.0-alpha.5-AppOnly-Source.zip", "BaGua-v0.5.1-alpha.5.1-AppOnly-Source.zip")
s = s.replace("BaGua-v0.5.0-alpha.5-SHA256.txt", "BaGua-v0.5.1-alpha.5.1-SHA256.txt")
p.write_text(s)

p = root / "app/src/main/java/io/github/xgl34222220/bagua/Models.kt"
s = p.read_text()
s = s.replace(
    "    val installed: Boolean = false,\n",
    "    val installed: Boolean = false,\n    val accessIssue: String = \"\",\n",
    1,
)
p.write_text(s)

p = root / "app/src/main/java/io/github/xgl34222220/bagua/BaguaController.kt"
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
replacement = '''    fun refreshStatus() {
        scope.launch {
            val result = runCommand(listOf("status"))
            status = parseStatus(result)
        }
    }

    fun retryRoot() {
        scope.launch {
            loading = true
            lastError = ""
            try {
                Shell.getCachedShell()?.close()
            } catch (_: Throwable) {
            }
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
    raise SystemExit("refreshStatus anchor missing")
s = s.replace(anchor, replacement)

start = s.index("    private suspend fun runCommand(args: List<String>): CommandResult = withContext(Dispatchers.IO) {")
end = s.index("\n    private fun shellQuote", start)
new_run = '''    private suspend fun runCommand(args: List<String>): CommandResult = withContext(Dispatchers.IO) {
        try {
            val rootShell = Shell.getShell()
            if (!rootShell.isRoot) {
                return@withContext CommandResult(
                    126,
                    "ERROR=root_required\\nROOT_STATUS=${rootShell.status}",
                    "八卦 App 未获得 Root 权限"
                )
            }

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
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            CommandResult(126, "ERROR=root_required", message)
        }
    }
'''
s = s[:start] + new_run + s[end:]

old_parse = '''        val map = parseKeyValues(result.stdout)
        if (result.code == 127 || map["ERROR"] == "module_not_installed") return ModuleStatus(installed = false)
        return ModuleStatus(
            installed = result.success && map["NAME"] == "八卦",
'''
new_parse = '''        val map = parseKeyValues(result.stdout)
        if (result.code == 126 || map["ERROR"] == "root_required") {
            return ModuleStatus(installed = false, accessIssue = "root_required", raw = map)
        }
        if (result.code == 127 || map["ERROR"] == "module_not_installed") {
            return ModuleStatus(installed = false, accessIssue = "module_not_installed", raw = map)
        }
        return ModuleStatus(
            installed = result.success && map["NAME"] == "八卦",
'''
if old_parse not in s:
    raise SystemExit("parse anchor missing")
s = s.replace(old_parse, new_parse)
s = s.replace(
    '            "module_not_installed" -> "没有检测到八卦模块，请先刷入内置 App 的模块包。"',
    '            "root_required" -> "八卦 App 没有 Root 权限，请在根管理器的超级用户页面允许八卦，然后重试。"\n            "module_not_installed" -> "已获得 Root 权限，但没有找到八卦模块，请重新刷入模块包并重启。"',
)
p.write_text(s)

p = root / "app/src/main/java/io/github/xgl34222220/bagua/Screens.kt"
s = p.read_text()
old = '''        if (!s.installed) {
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
new = '''        if (!s.installed) {
            item {
                if (s.accessIssue == "root_required") {
                    SectionCard("需要 Root 授权") {
                        StatusCard(
                            title = "模块已安装，但 App 没有 Root 权限",
                            subtitle = "请到根管理器的‘超级用户’页面允许八卦，再点下方按钮。",
                            icon = Icons.Outlined.AdminPanelSettings,
                            emphasized = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::retryRoot, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("重新请求 Root 并检测")
                        }
                    }
                } else {
                    SectionCard("模块连接") {
                        StatusCard(
                            title = "已获得 Root，但没有找到八卦模块",
                            subtitle = "请重新刷入八卦 App-only 模块并重启设备。",
                            icon = Icons.Outlined.Info,
                            emphasized = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::refreshAll, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("重新检测")
                        }
                    }
                }
            }
            return@LazyColumn
        }
'''
if old not in s:
    raise SystemExit("UI anchor missing")
s = s.replace(old, new)
p.write_text(s)

p = root / "module-src/CHANGELOG.md"
old = p.read_text()
p.write_text(
    "# 更新日志\n\n"
    "## 0.5.1-alpha.5.1\n\n"
    "- 修复未授予 Root 时被误报为‘没有检测到模块’\n"
    "- 明确区分‘未授权 Root’和‘模块确实不存在’\n"
    "- 增加重新请求 Root 与重新检测按钮\n"
    "- 增加 modules 与 modules_update 的模块 ID 回退扫描\n"
    "- Root Shell 使用 mount-master，提高不同挂载环境下的兼容性\n"
    "- 保留全部 Alpha 5 App-only 功能与用户配置\n\n"
    + old
)

p = root / "README.md"
s = p.read_text().replace("0.5.0-alpha.5", "0.5.1-alpha.5.1")
p.write_text(s)

print("patched")
