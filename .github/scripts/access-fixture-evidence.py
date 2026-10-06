"""Read-only acceptance evidence around a CI-owned private data sentinel.

Only an SDK emulator with rootable adbd is accepted. No pm clear, uninstall or
fixture deletion; these data remain until the disposable AVD is discarded.
"""
import hashlib
from pathlib import Path
import re
import uuid


def seed_private_data(m, apk):
    model = m.adb("shell", "getprop", "ro.product.model").lower()
    fingerprint = m.adb("shell", "getprop", "ro.build.fingerprint")
    assert "sdk" in model or "generic" in fingerprint, "Disposable emulator required"
    m.adb("root")
    m.adb("wait-for-device")
    assert m.adb("shell", "id", "-u") == "0"
    directory = f"/data/user/0/{m.APP}/files"
    uid = int(m.adb("shell", "stat", "-c", "%u", f"/data/user/0/{m.APP}"))
    assert uid >= 10000
    local = m.OUT / "private-sentinel.bin"
    local.write_bytes(b"BAIZE_ACCESS_DATA_MUST_SURVIVE\x00" + uuid.uuid4().bytes)
    remote = directory + "/ci-access-" + uuid.uuid4().hex + ".bin"
    m.adb("shell", "mkdir", "-p", directory)
    m.adb("shell", "chown", f"{uid}:{uid}", directory)
    m.adb("push", str(local), remote)
    m.adb("shell", "chown", f"{uid}:{uid}", remote)
    m.adb("shell", "chmod", "600", remote)
    apk_hash = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
    expected = hashlib.sha256(local.read_bytes()).hexdigest()
    assert m.adb("shell", "sha256sum", remote).split()[0] == expected
    return {"path": remote, "sha256": expected, "appUid": uid, "apkSha256": apk_hash,
            "fingerprint": fingerprint}


def verify_private_data(m, evidence):
    m.adb("root")
    m.adb("wait-for-device")
    assert m.adb("shell", "id", "-u") == "0"
    assert m.adb("shell", "sha256sum", evidence["path"]).split()[0] == evidence["sha256"]
    assert int(m.adb("shell", "stat", "-c", "%u", evidence["path"])) == evidence["appUid"]
    version = int(re.search(r"versionCode=(\d+)", m.adb("shell", "dumpsys", "package", m.APP)).group(1))
    return {**evidence, "versionCode": version, "appPrivateDataPreserved": True,
            "appDataCleared": False, "persistentFixtureDataDeleted": False,
            "crossUidRootBinderValidated": False}
