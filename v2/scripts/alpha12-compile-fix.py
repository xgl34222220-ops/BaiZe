from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt")
text = path.read_text()

replacements = {
    '"主题与取色\n${palette.label} · ${palette.description}"': '"主题与取色\\n${palette.label} · ${palette.description}"',
    '"任务完成通知\n${if (notification) "已开启" else "已关闭"}"': '"任务完成通知\\n${if (notification) "已开启" else "已关闭"}"',
    '"单文件保护上限\n${maxFileMb} MB"': '"单文件保护上限\\n${maxFileMb} MB"',
    '"白名单保护\n$packageCount 个应用 · $pathCount 条路径"': '"白名单保护\\n$packageCount 个应用 · $pathCount 条路径"',
    '"Root 清理服务\n$serviceState"': '"Root 清理服务\\n$serviceState"',
    '"崩溃诊断\n${CrashRecorder.summary(this)}"': '"崩溃诊断\\n${CrashRecorder.summary(this)}"',
    '"${palette.label}\n${palette.description}（Android 12+）"': '"${palette.label}\\n${palette.description}（Android 12+）"',
    '"${palette.label}\n${palette.description}"': '"${palette.label}\\n${palette.description}"',
}

for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"missing broken Kotlin string: {old!r}")
    text = text.replace(old, new)

if "import androidx.appcompat.app.AlertDialog" not in text:
    marker = "import android.widget.Toast\n"
    if marker not in text:
        raise SystemExit("missing Toast import marker")
    text = text.replace(marker, marker + "import androidx.appcompat.app.AlertDialog\n", 1)

path.write_text(text)
print("Alpha 12 Kotlin strings and AlertDialog import corrected")
