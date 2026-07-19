#!/usr/bin/env python3
from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/MiuixDashboardActivity.kt")
text = path.read_text(encoding="utf-8")
old = '''            val json = response.getOrThrow()
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val bytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)'''
new = '''            val json = response.getOrThrow()
            updateRawLogFromResponse(json)
            val latest = json.optJSONObject("latest") ?: JSONObject()
            val success = json.optBoolean("success")
            val cancelled = json.optBoolean("cancelled")
            val bytes = latest.optLong("bytes", 0L).coerceAtLeast(0L)'''
if new not in text:
    if old not in text:
        raise SystemExit("runModuleClean raw log patch target not found")
    text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
