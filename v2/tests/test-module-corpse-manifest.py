#!/usr/bin/env python3
"""Owned fixtures: shell refuses old plans and preserves partial outcomes. Android tests exercise the real helper."""
import hashlib, os, pathlib, shutil, subprocess, tempfile, time, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
class CorpseManifestContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='baize-corpse-contract-')
        self.root = pathlib.Path(self.temp.name); self.module = self.root/'module'; self.state=self.root/'state'; self.bin=self.root/'bin'
        for p in (self.module/'scripts',self.module/'app',self.module/'config',self.state,self.bin):p.mkdir(parents=True)
        for name in ('profile-cleaner.sh','cleanup-media-queue.sh'):shutil.copy2(ROOT/'module/scripts'/name,self.module/'scripts'/name)
        (self.module/'app/baize.apk').touch();(self.state/'whitelist.conf').touch()
        self.target=self.root/'media/0/Android/data/io.baize.ownedfixture';self.target.mkdir(parents=True)
        self.old=self.target/'reviewed.bin';self.old.write_bytes(b'old')
        self.new=self.target/'added.bin';self.new.write_bytes(b'new content')
        (self.state/'corpse_scan.targets').write_text(str(self.target)+'\n')
        (self.state/'corpse_scan.frozen.json').write_text('{"synthetic":true}')
        installed=self.root/'installed';installed.mkdir();(installed/'0.txt').write_text('android\n')
        self.env=os.environ|{'BAIZE_STATE_DIR':str(self.state),'BAIZE_MEDIA_ROOT':str(self.root/'media'),'BAIZE_INSTALLED_ROOT':str(installed),'PATH':str(self.bin)+os.pathsep+os.environ['PATH']}
        self.plan()
    def tearDown(self):self.temp.cleanup()
    def sha(self,p):return hashlib.sha256(p.read_bytes()).hexdigest()
    def plan(self,frozen=True):
        text=f'epoch={int(time.time())}\nsnapshot_id=owned\ntargets_sha={self.sha(self.state/"corpse_scan.targets")}\nwhitelist_sha={self.sha(self.state/"whitelist.conf")}\nfiles=1\nbytes=3\n'
        if frozen:text+='frozen_sha='+self.sha(self.state/'corpse_scan.frozen.json')+'\n'
        (self.state/'corpse_scan.env').write_text(text)
    def run_clean(self,mode='corpse-clean'):
        return subprocess.run(['bash',str(self.module/'scripts/profile-cleaner.sh'),mode,'test'],env=self.env,text=True,capture_output=True,timeout=15)
    def fake_helper(self,exit_code=8,summary=True,delete=True,complete=0):
        script=self.bin/'app_process'
        script.write_text(f'''#!/usr/bin/env python3
import pathlib,sys
state=pathlib.Path(sys.argv[4]);target=pathlib.Path(sys.argv[6]);summary=pathlib.Path(sys.argv[7]);out=pathlib.Path(sys.argv[10])
(state/'helper-called').touch()
old=target/'reviewed.bin'
if {delete!r}:old.unlink();out.write_bytes(str(old).encode()+b'\\0')
if {summary!r}:summary.write_text('files={int(delete)}\\nbytes={3 if delete else 0}\\ndirectories=0\\nfailures=0\\ncomplete={complete}\\nreason=new_or_protected_contents\\n')
raise SystemExit({exit_code})
''');script.chmod(0o755)
    def latest(self):return dict(x.split('=',1) for x in (self.state/'latest.env').read_text().splitlines() if '=' in x)
    def test_old_directory_plan_cannot_authorize_new_traversal(self):
        self.plan(False);self.fake_helper();r=self.run_clean();self.assertEqual(7,r.returncode,r.stdout+r.stderr);self.assertTrue(self.old.exists());self.assertFalse((self.state/'helper-called').exists())
    def test_changed_manifest_cannot_authorize_deletion(self):
        (self.state/'corpse_scan.frozen.json').write_text('changed');self.fake_helper();r=self.run_clean();self.assertEqual(7,r.returncode,r.stdout+r.stderr);self.assertTrue(self.old.exists())
    def test_missing_helper_preserves_owned_files(self):
        (self.module/'app/baize.apk').unlink();r=self.run_clean();self.assertEqual(8,r.returncode,r.stdout+r.stderr);self.assertTrue(self.old.exists())
    def test_missing_protection_list_is_not_recreated_empty(self):
        (self.state/'whitelist.conf').unlink();r=self.run_clean();self.assertEqual(7,r.returncode,r.stdout+r.stderr);self.assertFalse((self.state/'whitelist.conf').exists());self.assertTrue(self.old.exists())
    def test_partial_result_retains_original_plan_and_actual_counts(self):
        self.fake_helper();r=self.run_clean();self.assertEqual(8,r.returncode,r.stdout+r.stderr);s=self.latest();self.assertEqual('1',s['files']);self.assertEqual('3',s['bytes']);self.assertEqual('0',s['errors']);self.assertEqual('1',s['deep_remaining_targets']);self.assertTrue(self.new.exists());self.assertFalse(self.old.exists());self.assertTrue((self.state/'corpse_scan.env').exists());self.assertIn('partial\t',(self.state/'reports/latest.tsv').read_text())
        streams=list((self.state/'cleanup-media').glob('pending-*/paths.nul'));self.assertEqual(1,len(streams));self.assertEqual(str(self.old).encode()+b'\0',streams[0].read_bytes())
    def test_cancelled_result_keeps_counts_and_original_review(self):
        self.fake_helper(exit_code=9);r=self.run_clean();self.assertEqual(9,r.returncode,r.stdout+r.stderr);s=self.latest();self.assertEqual('1',s['files']);self.assertEqual('1',s['deep_stopped']);self.assertTrue(self.new.exists());self.assertTrue((self.state/'corpse_scan.env').exists())
    def test_interrupted_result_is_never_claimed_complete(self):
        self.fake_helper(exit_code=137,summary=False);r=self.run_clean();self.assertEqual(8,r.returncode,r.stdout+r.stderr);self.assertIn('未确认',r.stdout);self.assertNotIn('清理完成',r.stdout);self.assertTrue(self.new.exists());self.assertTrue((self.state/'corpse_scan.env').exists())
    def test_already_absent_does_not_invent_deleted_files(self):
        self.old.unlink();self.new.unlink();self.target.rmdir();self.fake_helper(exit_code=0,delete=False,complete=1);r=self.run_clean();self.assertEqual(0,r.returncode,r.stdout+r.stderr);s=self.latest();self.assertEqual('0',s['files']);self.assertEqual('0',s['bytes']);self.assertEqual('0',s['skipped']);self.assertFalse((self.state/'corpse_scan.env').exists())
    def test_legacy_deep_entry_delegates_only_to_original_manifest_engine(self):
        script=self.module/'scripts/deep-manifest-clean.sh';script.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >"$BAIZE_STATE_DIR/deep-args"\nexit 6\n')
        r=self.run_clean('deep-clean');self.assertEqual(6,r.returncode,r.stdout+r.stderr);self.assertEqual('deep-clean\ntest\n',(self.state/'deep-args').read_text());self.assertTrue(self.old.exists());self.assertTrue(self.new.exists())
    def test_signal_keeps_ownership_until_the_actual_writer_stops(self):
        script=self.bin/'app_process'
        script.write_text('''#!/usr/bin/env python3
import os,pathlib,signal,sys,time
signal.signal(signal.SIGTERM,signal.SIG_IGN)
state=pathlib.Path(sys.argv[4]);(state/'writer-pid').write_text(str(os.getpid()))
time.sleep(12)
(pathlib.Path(sys.argv[6])/'reviewed.bin').unlink()
''');script.chmod(0o755)
        process=subprocess.Popen(['bash',str(self.module/'scripts/profile-cleaner.sh'),'corpse-clean','test'],env=self.env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            end=time.monotonic()+5
            while not (self.state/'writer-pid').exists() and time.monotonic()<end:time.sleep(.02)
            self.assertTrue((self.state/'writer-pid').exists())
            child=int((self.state/'writer-pid').read_text());process.terminate();time.sleep(.1)
            self.assertTrue((self.state/'run.lock').is_dir(),'Ownership disappeared before the writer stopped')
            stdout,stderr=process.communicate(timeout=5);self.assertEqual(9,process.returncode,stdout+stderr)
            with self.assertRaises(ProcessLookupError):os.kill(child,0)
            self.assertTrue(self.old.exists());self.assertTrue(self.new.exists())
        finally:
            if process.poll() is None:process.kill();process.communicate()
if __name__=='__main__':unittest.main(verbosity=2)
