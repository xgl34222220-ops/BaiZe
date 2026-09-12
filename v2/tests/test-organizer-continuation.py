#!/usr/bin/env python3
"""Automatic queue fairness, source parity, stale indexes and move boundaries."""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]


class OrganizerContinuation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='baize-organizer-continue-')
        self.work = Path(self.temp.name)
        self.module = self.work / 'module'
        (self.module / 'config').mkdir(parents=True)
        shutil.copy(ROOT / 'v2/module/organizer-worker.sh', self.module)
        shutil.copy(ROOT / 'config/organizer-categories.conf', self.module / 'config')
        (self.module / 'storage-index.sh').write_text('#!/bin/sh\nexit 5\n')
        self.state = self.work / 'state'
        (self.state / 'index').mkdir(parents=True)
        self.media = self.work / 'media'
        self.config = self.state / 'config.conf'
        self.config.write_text('organizer_conflict_policy=0\norganizer_media_scan=0\n')
        self.env = dict(os.environ, BAIZE_STATE_DIR=str(self.state), BAIZE_MEDIA_ROOT=str(self.media),
                        BAIZE_SHELL_BIN='/bin/bash', BAIZE_ORGANIZER_AUTO_MAX_FILES='1')

    def tearDown(self):
        self.temp.cleanup()

    def make(self, path, data=b'file'):
        target = self.media / '0' / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def command(self, task, trigger='scheduler:interval'):
        return ['bash', str(self.module / 'organizer-worker.sh'), 'organize', trigger, task]

    def run_task(self, task, trigger='scheduler:interval', success=True):
        result = subprocess.run(self.command(task, trigger), env=self.env, capture_output=True, text=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_pending_queue_passes_unsupported_and_conflicting_files(self):
        blocked = self.make('Download/first.unsupported')
        conflict = self.make('Download/conflict.pdf')
        self.make('BaiZe归类/文档/conflict.pdf', b'keep existing')
        last = self.make('Download/last.pdf')
        # Seed a deterministic complete queue as produced by an earlier batch.
        pending = self.state / 'index/organizer-auto-pending.nul'
        pending.write_bytes(b''.join(os.fsencode(p) + b'\0' for p in (blocked, conflict, last)))
        self.run_task('first')
        self.assertTrue(last.exists())
        self.run_task('second')
        self.assertTrue(last.exists())
        self.run_task('third')
        self.assertFalse(last.exists())
        self.assertTrue((self.media / '0/BaiZe归类/文档/last.pdf').exists())
        self.assertTrue(blocked.exists() and conflict.exists())
        self.assertFalse(pending.exists())

    def test_failed_shared_refresh_uses_new_fallback_not_stale_index(self):
        stale = self.make('Pictures/album.pdf')
        fresh = self.make('Download/new.pdf')
        (self.state / 'index/organizer-files.nul').write_bytes(os.fsencode(stale) + b'\0')
        self.run_task('failed-index', 'manual')
        self.assertFalse(fresh.exists())
        self.assertTrue(stale.exists())

    def test_automatic_sources_match_uc_quark_and_telegram(self):
        self.env['BAIZE_ORGANIZER_AUTO_MAX_FILES'] = '100'
        sources = [self.make(p) for p in ('UCDownloads/uc.pdf', 'Quark/Download/quark.pdf',
                   'BaiduNetdisk/cloud.pdf', 'Telegram/Telegram Documents/chat.pdf',
                   'Android/media/org.telegram.messenger/Telegram/Telegram Documents/media.pdf')]
        self.run_task('sources')
        self.assertTrue(all(not p.exists() for p in sources))
        self.assertEqual(len(list((self.media / '0/BaiZe归类/文档').glob('*.pdf'))), 5)

    def test_symlink_destination_does_not_move_or_change_external_permissions(self):
        source = self.make('Download/keep.pdf')
        outside = self.work / 'outside'
        (outside / '文档').mkdir(parents=True)
        (outside / '文档').chmod(0o751)
        (self.media / '0/BaiZe归类').symlink_to(outside, target_is_directory=True)
        result = self.run_task('symlink', success=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(source.exists())
        self.assertEqual((outside / '文档').stat().st_mode & 0o777, 0o751)
        self.assertFalse((outside / '文档/keep.pdf').exists())

    def test_signal_saves_undo_for_completed_moves_and_stops(self):
        self.env['BAIZE_ORGANIZER_AUTO_MAX_FILES'] = '100'
        for n in range(20):
            self.make(f'Download/{n}.pdf')
        fake = self.work / 'fake'
        fake.mkdir()
        real_mv = shutil.which('mv')
        marker = self.work / 'move-marker'
        wrapper = fake / 'mv'
        wrapper.write_text(f'#!/bin/bash\n{real_mv} "$@"\ncode=$?\ncase "$*" in *Download/*.pdf*) touch "{marker}"; sleep 0.08 ;; esac\nexit "$code"\n')
        wrapper.chmod(0o755)
        self.env['PATH'] = str(fake) + ':' + os.environ['PATH']
        process = subprocess.Popen(self.command('signal'), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + 10
        while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(marker.exists())
        process.send_signal(signal.SIGTERM)
        output, error = process.communicate(timeout=10)
        self.assertEqual(process.returncode, 9, output + error)
        moved = list((self.media / '0/BaiZe归类/文档').glob('*.pdf'))
        undo = json.loads((self.state / 'organizer-last.json').read_text())
        self.assertGreater(len(moved), 0)
        self.assertLess(len(moved), 20)
        self.assertEqual(len(undo['moves']), len(moved))
        self.assertFalse((self.state / 'run.lock').exists())


if __name__ == '__main__':
    unittest.main()
