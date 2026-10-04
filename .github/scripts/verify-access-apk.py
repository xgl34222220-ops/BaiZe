"""Verify the exact signed paired input before access-device acceptance."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

candidate = Path("candidate")
apk, = candidate.glob("*.apk")
module, = candidate.glob("*-Module.zip")
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
expected = os.environ.get("BAIZE_EXPECTED_APK_SHA", "")
if expected:
    assert sha == expected, "Frozen signed APK hash differs"
with zipfile.ZipFile(module) as z:
    assert z.testzip() is None
    embedded, = [name for name in z.namelist() if name.endswith("/app/baize.apk") or name == "app/baize.apk"]
    assert hashlib.sha256(z.read(embedded)).hexdigest() == sha
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
cert = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
assert "SHA-256 digest: 9efa848001ccdc168ea96753d332b1713e2668ba6a2fa22d5bfd98de65db1f0f" in cert
badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
version = int(re.search(r"versionCode='(\d+)'", badging).group(1))
source = Path("v2/app/build.gradle.kts").read_text()
assert version == int(re.search(r"versionCode\s*=\s*(\d+)", source).group(1))
out = Path(os.environ["RUNNER_TEMP"]) / "access-input.json"
out.write_text(json.dumps({"sourceSha": os.environ["BAIZE_ACCESS_SOURCE"], "pairedRun": os.environ["BAIZE_PAIRED_RUN"],
    "versionCode": version, "apkSha256": sha, "embeddedApkIdentical": True,
    "signingCertificateSha256": "9efa848001ccdc168ea96753d332b1713e2668ba6a2fa22d5bfd98de65db1f0f"}, indent=2))
print(out.read_text())
