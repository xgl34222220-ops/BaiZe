from pathlib import Path

root = Path(__file__).resolve().parents[1]

build = root / "v2/app/build.gradle.kts"
text = build.read_text()
for old in (
    'versionCode = 22601\n        versionName = "2.2.1"',
    'versionCode = 22602\n        versionName = "2.2.2"',
    'versionCode = 22600\n        versionName = "2.2.0"',
):
    text = text.replace(old, 'versionCode = 22603\n        versionName = "2.2.3"')
text = text.replace(
    '''\nval applyV221SourceHotfix = tasks.register<Exec>("applyV221SourceHotfix") {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "tools/apply-v221-source-only.py")
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(applyV221SourceHotfix)
}
''',
    "\n"
)
text = text.replace("import org.gradle.api.tasks.Exec\n\n", "")
build.write_text(text)

(root / "v2/module/module.prop").write_text(
    "id=baize_v2\n"
    "name=白泽 v2\n"
    "version=v2.2.3\n"
    "versionCode=22603\n"
    "author=惜故里丶\n"
    "description=白泽 v2.2.3 测试版：记录时间、持久应用图标、受保护路径复查与手动选择清理。\n"
)

package = root / "v2/scripts/package-module.sh"
value = package.read_text()
for version in ("v2.2.0", "v2.2.1", "v2.2.2"):
    value = value.replace(version, "v2.2.3")
for code in ("22600", "22601", "22602"):
    value = value.replace(code, "22603")
package.write_text(value)

(root / "RELEASE_NOTES_V2.2.3.md").write_text(
    "# 白泽 v2.2.3 测试版\n\n"
    "- 删除无效的运行日志底栏入口，扫描和清理时间直接显示在记录页。\n"
    "- 应用图标改用完整包可见性、持久文件缓存和进入页面预加载。\n"
    "- 记录页展示异常与受保护项目的准确路径、风险与保护原因。\n"
    "- 新增受保护项目复查页，用户可逐项勾选；关键风险和硬保护不可绕过。\n"
)

print("v2.2.3 version applied")
