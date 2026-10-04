"""Separately signed, permission-scoped DocumentsProvider, only for disposable CI."""
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import sys
import zipfile

out = Path(sys.argv[1]).resolve(); out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ["ANDROID_HOME"])
build = sdk / "build-tools/36.0.0"; jar = sdk / "platforms/android-36/android.jar"
source = Path(__file__).parent.parent / "fixtures/photo-kill/PhotoDocuments.java"
manifest = out / "AndroidManifest.xml"
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.baize.photo.fixture" android:versionCode="1" android:versionName="CI">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:debuggable="true" android:allowBackup="false" android:label="BaiZe CI Photos">
<provider android:name="test.baize.photo.fixture.PhotoDocuments" android:authorities="test.baize.photo.fixture.documents" android:exported="true" android:grantUriPermissions="true" android:permission="android.permission.MANAGE_DOCUMENTS">
<intent-filter><action android:name="android.content.action.DOCUMENTS_PROVIDER"/></intent-filter>
</provider></application></manifest>''')
classes = out / "classes"; classes.mkdir()
subprocess.run(["javac", "-source", "8", "-target", "8", "-classpath", str(jar), "-d", str(classes), str(source)], check=True)
subprocess.run([str(build / "d8"), "--lib", str(jar), "--output", str(out), *map(str, classes.rglob("*.class"))], check=True)
unsigned = out / "unsigned.apk"
subprocess.run([str(build / "aapt2"), "link", "-o", str(unsigned), "--manifest", str(manifest), "-I", str(jar)], check=True)
with zipfile.ZipFile(unsigned, "a") as apk: apk.write(out / "classes.dex", "classes.dex")
aligned = out / "aligned.apk"
subprocess.run([str(build / "zipalign"), "-f", "4", str(unsigned), str(aligned)], check=True)
key = out / "fixture.jks"
subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storepass", "android", "-keypass", "android", "-alias", "fixture",
    "-dname", "CN=BaiZe Disposable Photo CI", "-keyalg", "RSA", "-validity", "30"], check=True, capture_output=True)
subprocess.run([str(build / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:android", "--key-pass", "pass:android",
    "--out", str(out / "fixture.apk"), str(aligned)], check=True)
spec = importlib.util.spec_from_file_location("owned_jpeg", Path(__file__).with_name("smoke-seven-improvements.py"))
seven = importlib.util.module_from_spec(spec); spec.loader.exec_module(seven)
for index in (1, 2):
    make = bytes([64 + index]) * 8192 + b"\0"
    tiff = b"II" + struct.pack("<HIH", 42, 8, 1) + struct.pack("<HHII", 0x10F, 2, len(make), 26) + struct.pack("<I", 0) + make
    exif = b"Exif\0\0" + tiff
    jpeg = seven.JPEG[:2] + b"\xff\xe1" + struct.pack(">H", len(exif) + 2) + exif + seven.JPEG[2:]
    (out / f"0{index}-source.jpg").write_bytes(jpeg)
print("Owned document provider and two original fixtures built")
