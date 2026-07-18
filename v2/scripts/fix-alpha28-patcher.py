#!/usr/bin/env python3
from pathlib import Path

path = Path("v2/scripts/apply-alpha28-app-details.py")
text = path.read_text(encoding="utf-8")

old = '''if old_render not in activity:
    raise SystemExit("renderTaskState target not found")
activity = activity.replace(old_render, new_render, 1)
'''
new = '''activity = replace_kotlin_method(activity, "renderTaskState", new_render)
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("renderTaskState patcher block not found")

# Keep Kotlin's \n escapes literal inside the generated method instead of turning
# them into physical newlines in the middle of quoted strings.
text = text.replace("new_render = '''    private fun renderTaskState", "new_render = r'''    private fun renderTaskState", 1)

# Android 13+ flag factories take a Long.
text = text.replace("PackageManager.ApplicationInfoFlags.of(0)", "PackageManager.ApplicationInfoFlags.of(0L)")

path.write_text(text, encoding="utf-8")
