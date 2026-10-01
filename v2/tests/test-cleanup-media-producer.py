#!/usr/bin/env python3
"""Exercise persistent NUL producer under frozen task-user and failure conditions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
PRODUCER=ROOT/'v2/module/scripts/cleanup-media-queue.sh'
class Producer(unittest.TestCase):
    def test_cache_lane_records_live_in_root_state_and_user_is_captured_once(self):
        with tempfile.TemporaryDirectory(prefix='baize-media-producer-') as d:
            root=Path(d);main=root/'main';lane=root/'lane';lane.mkdir();calls=root/'calls'
            script='''set -eu
cmd() { printf 'called\\n' >>"$CALLS"; echo 10; }
. "$PRODUCER"
baize_cleanup_media_init
baize_cleanup_media_begin
printf '%s\\0' "/data/media/10/space ' quote
newline.apk" >"$BAIZE_CLEANUP_DELETED_NUL"
baize_cleanup_media_publish
cmd() { printf 'called-again\\n' >>"$CALLS"; echo 11; }
baize_cleanup_media_begin
printf '%s\\0' /mnt/media_rw/ABCD-0123/second.apk >"$BAIZE_CLEANUP_DELETED_NUL"
baize_cleanup_media_publish
'''
            subprocess.run(['bash','-c',script],check=True,env={**os.environ,'PRODUCER':str(PRODUCER),'CALLS':str(calls),
                           'BAIZE_STATE_DIR':str(lane),'BAIZE_ROOT_STATE_DIR':str(main)})
            batches=list((main/'cleanup-media').glob('pending-*'))
            self.assertEqual(2,len(batches));self.assertEqual('called\n',calls.read_text())
            self.assertFalse((lane/'cleanup-media').exists())
            self.assertTrue(all((b/'user').read_text()=='10\n' for b in batches))
            self.assertIn(b"/data/media/10/space ' quote\nnewline.apk\0",[(b/'paths.nul').read_bytes() for b in batches])
    def test_partial_import_is_not_published_and_source_is_retained(self):
        with tempfile.TemporaryDirectory(prefix='baize-media-import-') as d:
            root=Path(d);source=root/'source.nul';source.write_bytes(b'/data/media/0/a\0/data/media/0/b\0')
            script='''set -eu
cmd() { echo 0; }
. "$PRODUCER"
cat() { if [ "$1" = "$SOURCE" ]; then printf /data/media/0/partial; return 1; else command cat "$@"; fi; }
if baize_cleanup_media_import "$SOURCE"; then exit 99; fi
if baize_cleanup_media_publish; then exit 98; fi
'''
            subprocess.run(['bash','-c',script],check=True,env={**os.environ,'PRODUCER':str(PRODUCER),'SOURCE':str(source),'BAIZE_STATE_DIR':str(root/'state')})
            queue=root/'state/cleanup-media';self.assertEqual([],list(queue.glob('pending-*')))
            incomplete=list(queue.glob('.building-*'));self.assertEqual(1,len(incomplete))
            self.assertTrue((incomplete[0]/'importing').exists());self.assertTrue(source.exists())
    def test_non_numeric_current_user_stays_unknown(self):
        with tempfile.TemporaryDirectory(prefix='baize-media-user-') as d:
            script='''set -eu
cmd() { echo 'Error 10: service unavailable'; return 1; }
. "$PRODUCER"
baize_cleanup_media_begin
baize_cleanup_media_publish
'''
            subprocess.run(['bash','-c',script],check=True,env={**os.environ,'PRODUCER':str(PRODUCER),'BAIZE_STATE_DIR':d})
            user=next((Path(d)/'cleanup-media').glob('pending-*/user'))
            self.assertEqual('unknown\n',user.read_text())
if __name__=='__main__':unittest.main(verbosity=2)
