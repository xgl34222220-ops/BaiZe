from pathlib import Path

root = Path(__file__).resolve().parents[1]
path = root / "tools/apply-v224-background-recovery.py"
text = path.read_text()

old = '''build = root / "v2/app/build.gradle.kts"
build_text = build.read_text().replace(
    'versionCode = 22603\\n        versionName = "2.2.3"',
    'versionCode = 22604\\n        versionName = "2.2.4"'
)
if 'versionName = "2.2.4"' not in build_text:
    raise SystemExit("v2.2.4 build version anchor missing")
build.write_text(build_text)
'''

new = '''build = root / "v2/app/build.gradle.kts"
build_text = build.read_text()
legacy_version = 'versionCode = 22603\\n        versionName = "2.2.3"'
v224_version = 'versionCode = 22604\\n        versionName = "2.2.4"'
if legacy_version in build_text:
    build_text = build_text.replace(legacy_version, v224_version, 1)
elif not any(
    marker in build_text
    for marker in (
        'versionName = "2.2.4"',
        'versionName = "2.2.5"',
        'versionName = "2.3.0"',
    )
):
    raise SystemExit("unsupported build version before v2.2.4 recovery patch")
build.write_text(build_text)
'''

if old in text:
    path.write_text(text.replace(old, new, 1))
elif "unsupported build version before v2.2.4 recovery patch" not in text:
    raise SystemExit("v2.2.4 version compatibility block not found")

print("v2.3.0 materialization compatibility prepared")
