#!/usr/bin/env python3
"""A live APK scan must never be reclaimed as a stale cleanup lock."""
from pathlib import Path
import re
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='baize-lock-') as work:
    scan = Path(work) / 'apk-scanner.sh'
    scan.write_text('echo ready\nsleep 20\necho done\n')
    process = subprocess.Popen(['bash', str(scan)], stdout=subprocess.PIPE, text=True)
    assert process.stdout.readline().strip() == "ready"
    try:
        # Some containers expose host /proc while subprocess PIDs are namespaced.
        # The release CI has a matching procfs and executes the live-owner assertion.
        procfile = Path(f'/proc/{process.pid}/cmdline')
        if not procfile.exists() or str(scan).encode() not in procfile.read_bytes():
            print('SKIP live lock check: procfs does not match process PID namespace')
            raise SystemExit(0)
        checked = 0
        for file in [ROOT / 'cleaner.sh', *(ROOT / 'v2/module').glob('*.sh')]:
            source = file.read_text()
            for match in re.finditer(r'^(pid_is_baize_task|pid_is_task|is_baize_pid)\(\) \{\n.*?^\}', source, re.M | re.S):
                definition = match.group()
                if '/proc/' not in definition:
                    continue
                name = match.group(1)
                result = subprocess.run(['bash', '-c', 'MODULE_TAG=baize_v2\n' + definition + f'\n{name} {process.pid}'], capture_output=True, text=True)
                assert result.returncode == 0, (file.name, name, result.stderr)
                checked += 1
        assert checked >= 8, checked
        print(f'{checked} lock validators preserve a live APK scan')
    finally:
        process.terminate()
        process.wait()
