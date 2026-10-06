#!/usr/bin/env python3
import os,pathlib,shutil,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class InventoryProof(unittest.TestCase):
 def run_case(self,output,code):
  with tempfile.TemporaryDirectory(prefix='baize-inventory-') as d:
   r=pathlib.Path(d);mod=r/'module';state=r/'state';bin=r/'bin';data=r/'data';media=r/'media'
   for p in (mod/'config',state,bin,data/'user/0',media/'0'):p.mkdir(parents=True)
   shutil.copy2(ROOT/'module/scripts/one-pass-scan.sh',mod/'one-pass-scan.sh')
   (mod/'config/default.conf').touch();(mod/'config/whitelist.conf').touch()
   (r/'output').write_text(output)
   for name in ('cmd','pm'):
    p=bin/name;p.write_text('#!/bin/sh\ncat "$INVENTORY_FIXTURE"\nexit "$INVENTORY_EXIT"\n');p.chmod(0o755)
   engine=bin/'engine';engine.write_text('#!/bin/sh\ntouch "$INVENTORY_CALLED"\nexit 23\n');engine.chmod(0o755)
   env=os.environ|{'BAIZE_STATE_DIR':str(state),'BAIZE_DATA_ROOT':str(data),'BAIZE_MEDIA_ROOT':str(media),'BAIZE_NATIVE_ENGINE':str(engine),'INVENTORY_FIXTURE':str(r/'output'),'INVENTORY_EXIT':str(code),'INVENTORY_CALLED':str(r/'called'),'PATH':str(bin)+os.pathsep+os.environ['PATH']}
   env.pop('BAIZE_INSTALLED_ROOT',None)
   proc=subprocess.run(['bash',str(mod/'one-pass-scan.sh'),'corpse-scan','test'],env=env,text=True,capture_output=True,timeout=15)
   return proc,(r/'called').exists()
 def test_failed_command_with_partial_valid_output_never_reaches_scan(self):
  p,called=self.run_case('package:android\npackage:io.baize.partial\n',1);self.assertEqual(8,p.returncode,p.stdout+p.stderr);self.assertFalse(called)
 def test_successful_command_with_error_text_is_unknown(self):
  p,called=self.run_case('Error: user is stopped\n',0);self.assertEqual(8,p.returncode,p.stdout+p.stderr);self.assertFalse(called)
 def test_empty_success_is_unknown(self):
  p,called=self.run_case('',0);self.assertEqual(8,p.returncode,p.stdout+p.stderr);self.assertFalse(called)
 def test_list_without_system_marker_is_not_complete(self):
  p,called=self.run_case('package:io.baize.partial\n',0);self.assertEqual(8,p.returncode,p.stdout+p.stderr);self.assertFalse(called)
 def test_complete_valid_list_reaches_native_scan(self):
  p,called=self.run_case('package:android\npackage:io.baize.synthetic\n',0);self.assertTrue(called,p.stdout+p.stderr)
if __name__=='__main__':unittest.main(verbosity=2)
