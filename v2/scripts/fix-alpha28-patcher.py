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
if old not in text and new not in text:
    raise SystemExit("renderTaskState patcher block not found")
if old in text:
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
