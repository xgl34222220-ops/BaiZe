#!/usr/bin/env python3
"""Boot-safety regression.

A previous build made boot take ~3 minutes: init's `restorecon --recursive /data` walked
1.29M files the module had left under /data/adb/baize-v2. These checks keep that from
coming back:
  * no early-boot hook (post-fs-data / post-mount / boot-completed) exists;
  * service.sh does only O(1) work before sys.boot_completed, then lowers its priority
    before anything heavy, and backgrounds every helper except the app installer;
  * scheduler and maintenance never start work right after boot;
  * no boot-path script scans directories;
  * every dynamically named file the module writes under its state directory belongs
    to a family that has an explicit cap (see state-retention.sh).
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'v2/module'
SCRIPTS = MODULE / 'scripts'

SCAN = re.compile(r'(^|[\s;|&(`])(find|du|restorecon|chcon|tar|sha256sum|md5sum)\b|ls\s+-[a-zA-Z]*R|ch(mod|own)\s+-R|cp\s+-[a-zA-Z]*[ar]')
SPAWN = re.compile(r'(^|[\s;&(])(sh|"\$SHELL_BIN")\s+"\$SCRIPTDIR/')


def code_lines(path):
    out = []
    for number, raw in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        line = raw.split(' #', 1)[0].rstrip() if not raw.lstrip().startswith('#') else ''
        out.append((number, line))
    return out


class EarlyBootHooks(unittest.TestCase):
    def test_no_blocking_boot_stage_hooks(self):
        for hook in ('post-fs-data.sh', 'post-mount.sh', 'boot-completed.sh', 'system.prop', 'sepolicy.rule'):
            self.assertFalse((MODULE / hook).exists(), f'{hook} runs in a blocking boot stage; keep boot work in service.sh after boot_completed')


class ServiceScript(unittest.TestCase):
    def setUp(self):
        self.lines = code_lines(MODULE / 'service.sh')
        self.wait = next(i for i, (_, l) in enumerate(self.lines) if 'getprop sys.boot_completed' in l and 'while' in l)

    def test_before_boot_completed_is_constant_time(self):
        for number, line in self.lines[:self.wait]:
            self.assertIsNone(SCAN.search(line), f'service.sh:{number} scans before boot_completed: {line}')
            self.assertIsNone(SPAWN.search(line), f'service.sh:{number} starts a helper before boot_completed: {line}')
            for heavy in ('pm ', 'dumpsys', 'app_process', 'cmd package', 'for '):
                self.assertNotIn(heavy, line, f'service.sh:{number} does {heavy.strip()} before boot_completed')

    def test_wait_is_long_enough_and_followed_by_settle_and_low_priority(self):
        wait_line = self.lines[self.wait][1]
        cap = re.search(r'-lt\s+(\d+)', wait_line)
        sleep = re.search(r'sleep\s+(\d+)', wait_line)
        self.assertTrue(cap and sleep, wait_line)
        self.assertGreaterEqual(int(cap.group(1)) * int(sleep.group(1)), 300, 'boot_completed wait must cover a slow first boot')
        rest = '\n'.join(l for _, l in self.lines[self.wait + 1:])
        first_helper = min(rest.find('app-installer.sh'), rest.find('dumpsys'))
        for needle in ('BAIZE_BOOT_SETTLE_SECONDS', 'renice -n', 'ionice -c'):
            position = rest.find(needle)
            self.assertGreaterEqual(position, 0, f'{needle} missing after boot wait')
            self.assertLess(position, first_helper, f'{needle} must precede the first helper')

    def test_helpers_after_boot_are_backgrounded(self):
        allowed_foreground = ('app-installer.sh', 'exec sh "$SCRIPTDIR/supervisor.sh"')
        joined = []
        for number, line in self.lines[self.wait + 1:]:
            if joined and joined[-1][1].endswith('\\'):
                joined[-1] = (joined[-1][0], joined[-1][1][:-1] + ' ' + line.strip())
            else:
                joined.append((number, line))
        for number, line in joined:
            if not SPAWN.search(line):
                continue
            if any(a in line for a in allowed_foreground):
                continue
            self.assertRegex(line, r'&\s*$', f'service.sh:{number} runs a helper in the foreground: {line}')
            self.assertIsNone(SCAN.search(line), f'service.sh:{number}')


class DeferredWork(unittest.TestCase):
    def test_scheduler_waits_for_boot_and_settles(self):
        text = (SCRIPTS / 'scheduler.sh').read_text(encoding='utf-8')
        self.assertLess(text.index('getprop sys.boot_completed'), text.index('mkdir -p "$LOG_DIR"'))
        settle = re.search(r'BAIZE_BOOT_SETTLE_UPTIME_SECONDS:-(\d+)', text)
        self.assertTrue(settle and int(settle.group(1)) >= 120)
        gate = text[text.index('conditions_allow_task() {'):]
        self.assertLess(gate.index('boot_settled'), gate.index('is_screen_off'), 'settle gate must come first and stay cheap')

    def test_maintenance_gates_before_any_system_query(self):
        text = (SCRIPTS / 'storage-maintenance.sh').read_text(encoding='utf-8')
        first_query = text.index('is_charging || exit 0')
        for gate in ('MIN_HOURS * 3600', 'BOOT_SETTLE_SECONDS', 'run.lock'):
            self.assertLess(text.index(gate), first_query, f'{gate} must be checked before dumpsys')
        self.assertIn('ionice -c 3', text)
        self.assertIn('trap finish EXIT', text)
        settle = re.search(r'BAIZE_MAINT_BOOT_SETTLE_SECONDS:-(\d+)', text)
        self.assertTrue(settle and int(settle.group(1)) >= 600)

    def test_boot_migration_is_low_priority_and_backgrounded(self):
        text = (SCRIPTS / 'state-migrate.sh').read_text(encoding='utf-8')
        self.assertLess(text.index('ionice -c 3'), text.index('baize_prune_task_results'))
        service = (MODULE / 'service.sh').read_text(encoding='utf-8')
        self.assertRegex(service, r'state-migrate\.sh" \\\n\s+</dev/null >>"\$STATE_DIR/logs/state-migrate\.log" 2>&1 &')

    def test_supervisor_never_scans_inline(self):
        for number, line in code_lines(SCRIPTS / 'supervisor.sh'):
            self.assertIsNone(SCAN.search(line), f'supervisor.sh:{number}: {line}')
        text = (SCRIPTS / 'supervisor.sh').read_text(encoding='utf-8')
        self.assertRegex(text, r'state-retention\.sh" budget "\$STATE_DIR" </dev/null >/dev/null 2>&1 & budget_pid=\$!')
        self.assertRegex(text, r'BAIZE_STATE_BUDGET_SECONDS:-(\d+)')


class BootStatusHelpers(unittest.TestCase):
    def test_module_status_is_constant_time_and_post_boot(self):
        for number, line in code_lines(SCRIPTS / 'module-status.sh'):
            self.assertIsNone(SCAN.search(line), f'module-status.sh:{number}: {line}')
            self.assertNotIn('dumpsys', line)
            self.assertNotIn('pm ', line)
        lines = code_lines(MODULE / 'service.sh')
        wait = next(i for i, (_, l) in enumerate(lines) if 'getprop sys.boot_completed' in l and 'while' in l)
        calls = [i for i, (_, l) in enumerate(lines) if 'module-status.sh' in l]
        self.assertTrue(calls)
        self.assertTrue(all(i > wait for i in calls), 'module status must wait for boot_completed')


# Generated-name families written under the state directory and the cap that bounds each.
FAMILIES = [
    (r'\$(REPORT_DIR|STATE_DIR/reports)/\$STAMP-', "reports '20[0-9][0-9]-*.tsv'"),
    (r'\$(LOG_DIR|STATE_DIR/logs)/\$STAMP-', "logs '20[0-9][0-9]-*.log'"),
    (r'/logs/cache-lane-\$TASK_ID-', "logs/cache-lane-* via baize_prune_task_logs"),
    (r'/reports/cache-lane-\$TASK_ID-', "reports 'cache-lane-*'"),
    (r'/logs/organizer-\$TASK_ID\.log', "logs 'organizer-*.log'"),
    (r'/logs/worker-\$TASK_ID\.log', "logs/worker-*.log via baize_prune_task_logs"),
    (r'task-results/\$TASK_ID\.(env|started)|\$RESULT_DIR/\$TASK_ID\.(env|started)', 'baize_prune_task_results'),
    (r'\$ledger/\$\(date \+%s\)-\$\$-\$ledger_seq\.env', 'scheduler ledger keeps 300'),
    (r'\$STATE_DIR/\$CACHE_PREFIX\.', 'fixed snapshot names'),
    (r'\$STATE_DIR/(autopilot-\$[a-z_]+\.env|last_\$\{[a-z_0-9]+\}_(run\.epoch|daily\.date)|scheduler-deferred-\$[a-z_0-9]+\.until)', 'one per task group (7)'),
    (r'\$LOG_DIR/scheduler-\$\{group\}\.log', 'one per task group (7), rotated'),
    (r'\$(STATE_DIR|ROOT_STATE_DIR|TASK_STATE)/\$(name|f|shared)"', 'fixed names from a literal list'),
    (r'\$STATE_DIR/media-scan-\$TASK_ID\.nul|organizer-media-scan\.spool\.', 'transient, consumed by RootService'),
    (r'\$QUAR/files/\$id', 'quarantine, bounded by quarantine_retention_days'),
]
DYNAMIC = re.compile(r'"\$\{?(STATE_DIR|LOG_DIR|REPORT_DIR|ROOT_STATE_DIR|RESULT_DIR|QUAR|REQUEST_DIR|SKIP_DIR|ledger|TASK_STATE)\}?/[^"]*\$[^"]*"')
TEMP = re.compile(r'\.tmp|tmp\.\$\$|\.\$\$"|\.rot\.\$\$')


class GeneratedFileCaps(unittest.TestCase):
    def test_every_generated_name_has_a_cap(self):
        unknown = []
        for script in sorted(MODULE.rglob('*.sh')):
            for number, line in code_lines(script):
                for match in DYNAMIC.finditer(line):
                    literal = match.group(0)
                    if TEMP.search(literal):
                        continue
                    if not any(re.search(pattern, literal) for pattern, _ in FAMILIES):
                        unknown.append(f'{script.relative_to(ROOT)}:{number}: {literal}')
        self.assertEqual([], unknown, 'New generated-name state files need a cap in state-retention.sh and an entry here')

    def test_caps_exist(self):
        retention = (SCRIPTS / 'state-retention.sh').read_text(encoding='utf-8')
        for needle in ("'20[0-9][0-9]-*.tsv'", "'20[0-9][0-9]-*.log'", "'organizer-*.log'", "'cache-lane-*'",
                       "'worker-*.log'", 'baize_prune_task_results', 'baize_rotate_log', 'BAIZE_STATE_FILE_BUDGET'):
            self.assertIn(needle, retention)
        caps = {name: int(value) for name, value in re.findall(r'(BAIZE_[A-Z_]+_KEEP)=\$\{BAIZE_[A-Z_]+_KEEP:-(\d+)\}', retention)}
        self.assertTrue(caps and all(0 < v <= 200 for v in caps.values()), caps)
        self.assertIn('tail -n +301', (SCRIPTS / 'scheduler.sh').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
