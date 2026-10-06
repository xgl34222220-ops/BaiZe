"""Two disposable, separately signed packages for actual UID 2000 cache-only checks."""
import os
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ["ANDROID_HOME"])
tools = sdk / "build-tools/36.0.0"
jar = sdk / "platforms/android-36/android.jar"
key = out / "fixture.jks"
subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storepass", "android", "-keypass", "android",
                "-alias", "fixture", "-dname", "CN=BaiZe Disposable CI", "-keyalg", "RSA", "-validity", "30"], check=True, capture_output=True)
for kind in ("selected", "retained"):
    directory = out / kind
    (directory / "res/values").mkdir(parents=True)
    (directory / "res/values/strings.xml").write_text(f'<resources><string name="app_name">BaiZe CI {kind.title()} Cache</string></resources>')
    manifest = directory / "AndroidManifest.xml"
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="test.baize.cache.{kind}" android:versionCode="1" android:versionName="fixture">
        <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
        <application android:hasCode="false" android:debuggable="true" android:allowBackup="false" android:label="@string/app_name" />
    </manifest>''')
    compiled = directory / "resources.zip"
    unsigned = directory / "unsigned.apk"
    aligned = directory / "aligned.apk"
    subprocess.run([str(tools / "aapt2"), "compile", "--dir", str(directory / "res"), "-o", str(compiled)], check=True)
    subprocess.run([str(tools / "aapt2"), "link", "-o", str(unsigned), "--manifest", str(manifest), "-I", str(jar), str(compiled)], check=True)
    subprocess.run([str(tools / "zipalign"), "-f", "4", str(unsigned), str(aligned)], check=True)
    subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:android", "--key-pass", "pass:android",
                    "--out", str(out / f"{kind}.apk"), str(aligned)], check=True)
print("Disposable cache-only fixtures built")
