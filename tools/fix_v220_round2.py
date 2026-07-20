#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
script = root / "v2/module/storage-index.sh"
text = script.read_text()

old_case = "Android|DCIM|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|BaiZe归类|LOST.DIR) continue ;;"
new_case = "Android|Tencent|DCIM|Pictures|Movies|Music|Podcasts|Ringtones|Alarms|Notifications|Audiobooks|BaiZe归类|LOST.DIR) continue ;;"
if old_case in text:
    text = text.replace(old_case, new_case, 1)

anchor = '  add_root "内部存储根目录" 1 "$userdir"\n'
addition = '''  add_root "内部存储根目录" 1 "$userdir"\n  add_root "QQ接收:公共目录" 12 "$userdir/Tencent/QQfile_recv"\n  add_root "TIM接收:公共目录" 12 "$userdir/Tencent/Timfile_recv"\n'''
if 'QQ接收:公共目录' not in text:
    if anchor not in text:
        raise RuntimeError("storage index user-root anchor missing")
    text = text.replace(anchor, addition, 1)
script.write_text(text)

# Keep the deterministic generator aligned so later CI runs do not undo the fix.
generator = root / "tools/apply_v220_rework.py"
g = generator.read_text()
if old_case in g:
    g = g.replace(old_case, new_case, 1)
if 'QQ接收:公共目录' not in g:
    if anchor not in g:
        raise RuntimeError("generator user-root anchor missing")
    g = g.replace(anchor, addition, 1)
generator.write_text(g)

# AIDL requires an explicit import for a second interface in the same package.
aidl = root / "v2/app/src/main/aidl/io/github/xgl34222220/baize/root/IProfileRootService.aidl"
a = aidl.read_text()
callback_import = "import io.github.xgl34222220.baize.root.ITaskProgressCallback;\n\n"
if callback_import not in a:
    a = a.replace("package io.github.xgl34222220.baize.root;\n\n", "package io.github.xgl34222220.baize.root;\n\n" + callback_import, 1)
aidl.write_text(a)

# The generator can be executed repeatedly by push and pull_request runs. Collapse duplicate
# Compose stability annotations so every data class has exactly one @Immutable marker.
ui = root / "v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt"
u = ui.read_text()
u = re.sub(r'(?:@Immutable\s*\n){2,}(?=data class )', '@Immutable\n', u)
ui.write_text(u)

# Macrobenchmark Java and Kotlin compilers must target the same bytecode level.
macro = root / "v2/macrobenchmark/build.gradle.kts"
m = macro.read_text()
if "compileOptions" not in m:
    m = m.replace(
        "    defaultConfig {\n        minSdk = 28\n        targetSdk = 36\n        testInstrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\"\n    }\n",
        "    defaultConfig {\n        minSdk = 28\n        targetSdk = 36\n        testInstrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\"\n    }\n\n    compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n        targetCompatibility = JavaVersion.VERSION_17\n    }\n\n    kotlinOptions {\n        jvmTarget = \"17\"\n    }\n",
        1,
    )
macro.write_text(m)

# LargeTest is only a categorization annotation and is not needed by the benchmark runner.
benchmark = root / "v2/macrobenchmark/src/main/java/io/github/xgl34222220/baize/macrobenchmark/BaiZeBenchmark.kt"
b = benchmark.read_text()
b = b.replace("import androidx.test.filters.LargeTest\n", "")
b = b.replace("@LargeTest\n", "")
benchmark.write_text(b)

print("v2.2.0 round-2 index, AIDL, immutable and benchmark fixes applied")
