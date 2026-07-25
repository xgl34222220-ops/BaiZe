#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "release/v2.5.4-release.yml.template"
text = path.read_text()
text = text.replace("BAIZE_VERSION_NAME: 2.5.3", "BAIZE_VERSION_NAME: 2.5.4")
text = text.replace("versionName = \"2.5.3\"", "versionName = \"2.5.4\"")
if "2.5.3" in text or "25003" in text or "v2.5.3" in text:
    raise SystemExit("stale v2.5.3 metadata remains in v2.5.4 release template")
if text.count("__BAIZE_V254_TARGET_SHA__") != 1:
    raise SystemExit("release target placeholder mismatch")
path.write_text(text)
print("v2.5.4 release template corrected")
