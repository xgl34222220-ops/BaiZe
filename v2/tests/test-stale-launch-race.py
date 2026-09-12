#!/usr/bin/env python3
"""Force two launchers to observe the same stale lock before either recovers it."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StaleLaunchRace(unittest.TestCase):
    def test_only_one_launcher_can_replace_a_stale_owner(self):
        self.run_stale_race()

    def test_missing_shadowed_applets_fall_through_to_working_busybox(self):
        self.run_stale_race(use_busybox_fallback=True)

    def run_stale_race(self, use_busybox_fallback=False):
        with tempfile.TemporaryDirectory(prefix='baize-stale-launch-') as tmp:
            root = Path(tmp)
            module, state, fake = [root / name for name in ('module', 'state', 'bin')]
            for directory in (module, state, fake):
                directory.mkdir()
            shutil.copy2(ROOT / 'v2/module/task-worker.sh', module)
            runner = module / 'worker-runner.sh'
            runner.write_text('''#!/bin/sh
mkdir -p "$BAIZE_STATE_DIR/task-results"
printf '%s\\n' "$3" >>"$BAIZE_STATE_DIR/executed"
sleep 0.8
printf 'exit_code=0\\n' >"$BAIZE_STATE_DIR/task-results/$3.env"
''')
            (state / 'task-launch.lock').write_text('999999999\n0\n')
            real_ln, real_rm = shutil.which('ln'), shutil.which('rm')
            (fake / 'ln').write_text(f'''#!/bin/sh
if [ "$#" -eq 2 ] && [ "$2" = "$BAIZE_STATE_DIR/task-launch.lock" ] && [ ! -e "$BAIZE_STATE_DIR/attempt.${{1##*.}}" ]; then
  : >"$BAIZE_STATE_DIR/attempt.${{1##*.}}"
  tries=0
  while [ "$(find "$BAIZE_STATE_DIR" -name 'attempt.*' | wc -l)" -lt 2 ] && [ "$tries" -lt 200 ]; do sleep 0.01; tries=$((tries+1)); done
fi
exec '{real_ln}' "$@"
''')
            # Before the fix, both stale recoverers enter here: the delayed second
            # unlink removes the new owner that the first has just published.
            (fake / 'rm').write_text(f'''#!/bin/sh
case "$*" in
  "-f $BAIZE_STATE_DIR/task-launch.lock")
    if mkdir "$BAIZE_STATE_DIR/recovery-first" 2>/dev/null; then sleep 0.10; else sleep 0.30; fi
    current=$(sed -n '1p' "$BAIZE_STATE_DIR/task-launch.lock" 2>/dev/null)
    if [ -n "$current" ] && [ "$current" != "$PPID" ] && kill -0 "$current" 2>/dev/null; then
      : >"$BAIZE_STATE_DIR/removed-live-owner"
    fi ;;
esac
exec '{real_rm}' "$@"
''')
            if use_busybox_fallback:
                # Model a root PATH whose flock/toybox entries exist but do not
                # implement flock. Capability failure must not end recovery.
                for name in ('flock', 'toybox'):
                    (fake / name).write_text('#!/bin/sh\nexit 127\n')
                real_flock = shutil.which('flock')
                self.assertIsNotNone(real_flock)
                (fake / 'busybox').write_text(f'''#!/bin/sh
[ "$1" = flock ] || exit 127
shift
: >"$BAIZE_STATE_DIR/busybox-flock-used"
exec '{real_flock}' "$@"
''')
            for executable in fake.iterdir():
                executable.chmod(0o755)
            env = dict(os.environ, BAIZE_STATE_DIR=str(state), BAIZE_SHELL_BIN='/bin/sh',
                       PATH=str(fake) + ':' + os.environ['PATH'])
            processes = [subprocess.Popen(['sh', str(module / 'task-worker.sh'), 'scan', 'app', name, 'wait'],
                          env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                         for name in ('one', 'two')]
            outputs = [process.communicate(timeout=12) for process in processes]
            self.assertEqual(sorted(process.returncode for process in processes), [0, 3], outputs)
            self.assertEqual(len((state / 'executed').read_text().splitlines()), 1)
            self.assertFalse((state / 'removed-live-owner').exists(), 'a stale contender removed another live launcher')
            self.assertFalse((state / 'task-launch.lock').exists())
            self.assertTrue((state / 'task-launch.lock.recovery').exists())
            if use_busybox_fallback:
                self.assertTrue((state / 'busybox-flock-used').exists())


if __name__ == '__main__':
    unittest.main()
