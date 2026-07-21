from pathlib import Path

root = Path(__file__).resolve().parents[1]
activity = root / "v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt"
text = activity.read_text()
start = text.index("    private fun runModuleClean(service: IProfileRootService) {")
end = text.index("    private fun runNativeScan(cleanAfterScan: Boolean) {", start)
section = text[start:end]
old = """            updateRawLogFromResponse(json)
            val latest = json.optJSONObject(\"latest\") ?: JSONObject()
"""
new = """            if (json.optBoolean(\"accepted\")) {
                dashboardState.value = dashboardState.value.copy(
                    running = true,
                    scanCompleted = false,
                    serviceText = \"独立 Root Worker 已接管任务，关闭 App 也会继续\",
                    taskPhase = json.optString(\"message\", \"清理任务已在后台启动\") + \"\\n可返回桌面或划掉最近任务\"
                )
                startRecoveredTaskPoll()
                return@launch
            }
            updateRawLogFromResponse(json)
            val latest = json.optJSONObject(\"latest\") ?: JSONObject()
"""
if new not in section:
    if old not in section:
        raise SystemExit("runModuleClean accepted anchor missing")
    section = section.replace(old, new, 1)
text = text[:start] + section + text[end:]
text = text.replace('taskPhase = "正在调用模块完整清理引擎…"', 'taskPhase = "正在把清理任务交给独立 Root Worker…"')
activity.write_text(text)

build = root / "v2/app/build.gradle.kts"
build_text = build.read_text()
already_v230 = 'versionName = "2.3.0"' in build_text
if not already_v230:
    build_text = build_text.replace('versionCode = 22604', 'versionCode = 22605')
    build_text = build_text.replace('versionName = "2.2.4"', 'versionName = "2.2.5"')
    if 'versionName = "2.2.5"' not in build_text:
        raise SystemExit("unsupported build version before v2.2.5 app patch")
    build.write_text(build_text)

    (root / "v2/module/module.prop").write_text(
        "id=baize_v2\nname=白泽 v2\nversion=v2.2.5\nversionCode=22605\nauthor=惜故里丶\n"
        "description=白泽 v2.2.5 测试版：独立 Root Worker、后台持续清理、深度扫描预算与慢目录诊断。\n"
    )

    (root / "RELEASE_NOTES_V2.2.5.md").write_text(
        "# 白泽 v2.2.5 后台独立任务与深度扫描提速测试版\n\n"
        "- 一键智能清理交给脱离 Activity 生命周期的 Root Worker。\n"
        "- 返回桌面、锁屏或划掉最近任务后继续执行，重新进入自动接管进度。\n"
        "- 深度规则增加可靠超时、阶段预算、最慢目录与阶段耗时。\n"
    )

package = root / "v2/scripts/package-module.sh"
package_text = package.read_text()
if not already_v230:
    package_text = package_text.replace("v2.2.4", "v2.2.5").replace("22604", "22605")
    package_text = package_text.replace("v2.2.3", "v2.2.5").replace("22603", "22605")
if 'task-worker.sh' not in package_text:
    package_text = package_text.replace(
        'chmod 0755 "$STAGE/storage-index.sh"\n',
        'chmod 0755 "$STAGE/storage-index.sh" "$STAGE/task-worker.sh"\n',
        1,
    )
    package_text = package_text.replace(
        'unzip -l "$OUTPUT" | grep -q \'storage-index.sh\'\n',
        'unzip -l "$OUTPUT" | grep -q \'storage-index.sh\'\n'
        'unzip -l "$OUTPUT" | grep -q \'task-worker.sh\'\n'
        'unzip -p "$OUTPUT" task-worker.sh | grep -q \'detached-root-shell\'\n',
        1,
    )
package.write_text(package_text)

print("v2.2.5 app behavior patch applied without downgrading v2.3.0")
