"""Reproduce 30025's real all-key migration, then verify the signed repair and explicit recovery.
Only a fresh disposable emulator and synthetic application-owned data are used.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import sys
import time
import traceback
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('trash_upgrade', Path(__file__).with_name('smoke-protection-trash-upgrade.py'))
assert spec and spec.loader
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
base.BASELINE_CODE = 30025
base.BASELINE_SHA256 = '2301d943bcf4a5524a1a8ec0569d14b6f353065318f0b55ebdf530d9043f2c24'
APP, DATA = base.APP, base.DATA
PREFS = f'{DATA}/shared_prefs/baize_v2.xml'
STORE = f'{DATA}/files/datastore/appearance.preferences_pb'

class RecoverySmoke(base.Smoke):
    def present(self, path: str) -> bool:
        return self.shell('if [ -f ' + shlex.quote(path) + ' ]; then echo present; fi') == 'present'

    def current_paths(self) -> set[str] | None:
        if not self.present(PREFS):
            return None
        root = self.preference_xml(PREFS)
        nodes = [n for n in root if n.get('name') == 'path_whitelist']
        if not nodes:
            return None
        base.require(len(nodes) == 1 and nodes[0].tag == 'set', 'Invalid current path protection field')
        return {n.text for n in nodes[0]}

    def verify_payloads(self, name: str) -> None:
        # Historical protection is deliberately pending until explicit review.
        # All unrelated fixture bytes and the independently stored budget must remain intact.
        expected = self.preference_expectations.pop(PREFS, None)
        try:
            super().verify_preserved(name)
        finally:
            if expected is not None:
                self.preference_expectations[PREFS] = expected

    def storage_evidence(self, name: str) -> None:
        for root in (DATA, f'/data/user_de/0/{APP}'):
            self.save(name + ('-ce' if root == DATA else '-de') + '-preferences-list.txt',
                      self.shell('ls -laZ ' + shlex.quote(root + '/shared_prefs'), check=False))
        if self.present(STORE):
            (self.out / (name + '-appearance.preferences_pb')).write_bytes(self.read_bytes(STORE))
        if self.present(PREFS):
            (self.out / (name + '-baize_v2.xml')).write_bytes(self.read_bytes(PREFS))

    def pending_screen(self, name: str) -> None:
        self.tap('设置', name + '-settings')
        self.tap('规则与保护', name + '-rules-center', scroll=True)
        self.tap('保护名单', name + '-whitelist', scroll=True)
        self.top('WhitelistActivity')
        self.tap('检查旧版保护', name + '-recovery')
        self.top('LegacyProtectionRecoveryActivity')
        self.find('旧版保护待确认', name + '-pending')
        self.find(self.historical_path, name + '-exact-rule', scroll=True)
        self.capture(name)
        base.require(self.current_paths() is None, 'Historical rules were silently restored before review')

    def verify_corrupt_startup(self) -> None:
        self.adb("shell", "am", "force-stop", APP)
        valid = self.read_bytes(PREFS)
        (self.out / "pre-corruption-valid-baize_v2.xml").write_bytes(valid)
        corrupt = b'<map><set name="path_whitelist"><string>synthetic-corrupt-rule'
        self.seed_file(PREFS, corrupt, "synthetic-corrupt-baize_v2.xml")
        self.shell("restorecon " + shlex.quote(PREFS))
        quarantine = f"{DATA}/no_backup/legacy-protection-integrity-v1"
        archive = quarantine + "/baize_v2.xml"
        manifest = quarantine + "/manifest.json"
        expected_manifest = None
        for attempt in ("corrupt-startup", "corrupt-cold-relaunch"):
            self.launch(attempt + "-home")
            base.require(self.read_bytes(PREFS) == corrupt, "Startup replaced the corrupt original with defaults")
            base.require(self.present(archive) and self.read_bytes(archive) == corrupt,
                         "Exact corrupt preference bytes were not preserved")
            raw_manifest = self.read_bytes(manifest)
            saved = json.loads(raw_manifest)
            base.require(saved.get("version") == 1 and saved.get("complete") is True,
                         "Quarantine archive was not durably completed")
            if expected_manifest is not None:
                base.require(raw_manifest == expected_manifest, "Relaunch rewrote the immutable quarantine record")
            expected_manifest = raw_manifest
            self.tap("设置", attempt + "-settings")
            self.tap("规则与保护", attempt + "-rules-center", scroll=True)
            self.tap("保护名单", attempt + "-whitelist", scroll=True)
            self.tap("检查旧版保护", attempt + "-review")
            self.top("LegacyProtectionRecoveryActivity")
            deadline = time.monotonic() + 30
            while True:
                root = self.tree(attempt + "-blocked")
                labels = self.texts(root)
                if "旧版保护暂不可确认" in labels and any("暂停清理" in label for label in labels):
                    break
                base.require(time.monotonic() < deadline, "Corrupt protection did not stay fail-closed")
                time.sleep(.5)
            parents = {child: parent for parent in root.iter("node") for child in parent}
            for node in root.iter("node"):
                if node.get("text") in ("核对并保存", "确认保存"):
                    current = node
                    disabled = False
                    while current is not None:
                        disabled = disabled or current.get("enabled") == "false"
                        current = parents.get(current)
                    base.require(disabled, "Unreadable protection incorrectly offered an enabled recovery save")
            self.verify_payloads(attempt + "-unchanged-fixtures")
            self.capture(attempt + "-blocked")
            base.require(self.read_bytes(PREFS) == corrupt and self.read_bytes(archive) == corrupt,
                         "Opening the recovery screen modified corrupt evidence")
            self.cases.append({"case": attempt, "canonical_corrupt_bytes_preserved": True,
                               "quarantine_exact_and_durable": True, "cleanup_still_blocked": True})
        (self.out / "quarantine-manifest.json").write_bytes(expected_manifest)

    def run(self) -> None:
        base.require(self.args.version >= 30026, 'Candidate must include the migration repair')
        baseline = self.inspect_apk(self.args.baseline.resolve(), 30025, 'baseline')
        candidate = self.inspect_apk(self.args.candidate.resolve(), self.args.version, 'candidate')
        base.require(candidate['signer_sha256'] == baseline['signer_sha256'], 'Signing certificate changed')
        self.result.update({'baseline': baseline, 'candidate': candidate})
        self.identify_emulator()
        base.require(self.shell('am get-current-user') == '0', 'Expected emulator primary user')
        installed = self.adb('shell', 'pm', 'list', 'packages', APP)
        base.require(f'package:{APP}' not in installed.splitlines(), 'Expected fresh emulator without BaiZe')
        self.settle_boot()
        self.adb('shell', 'input', 'keyevent', '82')
        self.save('baseline-install.txt', self.adb('install', str(self.args.baseline.resolve()), timeout=180))
        self.app_uid = self.package(30025, 'baseline')
        self.adb('logcat', '-c')
        self.launch('baseline-home')
        self.seed()
        self.historical_path = self.preference_expectations[PREFS][1]
        base.require(self.current_paths() == {self.historical_path}, 'Fixture protection was not seeded')
        self.storage_evidence('before-old-migration')
        # Launch the exact delivered buggy build again. Its production DataStore migration
        # must move the key before the repair is installed, rather than simulating that state.
        self.launch('reproduce-old-migration')
        deadline = time.monotonic() + 30
        while self.current_paths() is not None and time.monotonic() < deadline:
            time.sleep(.5)
        self.storage_evidence('after-old-migration')
        base.require(self.current_paths() is None, 'The old all-key migration did not reproduce')
        base.require(self.present(STORE) and self.historical_path.encode() in self.read_bytes(STORE),
                     'Historical path was not retained in the old appearance DataStore')
        self.verify_payloads('old-migration-non-protection-data')
        self.cases.append({'case': 'actual-30025-migration-reproduced', 'source_key_missing': True,
                           'historical_value_in_datastore': True})
        self.save('candidate-upgrade-install.txt', self.adb('install', '-r', str(self.args.candidate.resolve()), timeout=180))
        base.require(self.package(self.args.version, 'candidate') == self.app_uid, 'Upgrade changed application UID')
        self.launch('repair-upgrade-home')
        self.verify_payloads('repair-upgrade-before-review')
        self.pending_screen('recovery-pending')
        # Review cancellation must not restore a stale historical rule or remove any fixture.
        self.tap('核对并保存', 'review-before-cancel', scroll=True)
        self.find('确认旧版保护选择？', 'review-dialog')
        self.adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        base.require(self.current_paths() is None, 'Cancelling review wrote protection')
        self.capture('recovery-cancelled')
        self.tap('核对并保存', 'review-save', scroll=True)
        self.tap('确认保存', 'review-confirm')
        deadline = time.monotonic() + 30
        while True:
            root = self.tree('recovery-result')
            if '旧版保护已确认' in self.texts(root):
                break
            base.require(time.monotonic() < deadline, 'Recovery decision did not complete')
            time.sleep(.5)
        base.require(self.current_paths() == {self.historical_path}, 'Explicit recovery did not restore exact selected protection')
        self.verify_preserved('recovery-durable-data')
        self.storage_evidence('after-explicit-recovery')
        self.capture('recovery-confirmed')
        self.cases.append({'case': 'explicit-recovery', 'cancel_no_mutation': True,
                           'exact_selected_path_durable': True})
        self.launch('recovered-cold-launch')
        self.verify_preserved('recovered-cold-launch-data')
        base.require(self.current_paths() == {self.historical_path}, 'Startup stole protection again')
        self.open_trash('repaired-trash')
        self.cancel_clear_all('repaired-clear-keep')
        self.cancel_clear_all('repaired-clear-back', use_back=True)
        self.verify_corrupt_startup()
        self.result.update({'passed': True, 'actual_old_migration_reproduced': True,
                            'explicit_protection_recovery': True, 'protection_survives_relaunch': True,
                            'corrupt_startup_preserved': True, 'corrupt_relaunch_still_blocked': True,
                            'graphics_settings_modified': False, 'security_permissions_expanded': False,
                            'data_cleared': False, 'permanent_deletion_executed': False,
                            'ordinary_app_uid': self.app_uid, 'final_pid': self.alive()})

    def finish(self) -> None:
        if self.verified_emulator and self.root_requested:
            try:
                self.storage_evidence('final')
            except Exception as error:
                self.save('final-storage-capture-error.txt', str(error))
        super().finish()

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('version', type=int)
    parser.add_argument('--serial')
    parser.add_argument('--out', type=Path, default=Path('evidence/legacy-protection-upgrade'))
    smoke = RecoverySmoke(parser.parse_args())
    try:
        smoke.run()
    except Exception as error:
        smoke.result.update({'passed': False, 'error': str(error)})
        smoke.save('failure-traceback.txt', traceback.format_exc())
    finally:
        smoke.finish()
    print(json.dumps(smoke.result, ensure_ascii=False, indent=2))
    return 0 if smoke.result['passed'] else 1

if __name__ == '__main__':
    sys.exit(main())
