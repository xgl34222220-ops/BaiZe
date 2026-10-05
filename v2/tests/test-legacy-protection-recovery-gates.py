#!/usr/bin/env python3
"""Static call-site guardrail, complementing real DataStore/Robolectric recovery tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'v2/app/src/main/java/io/github/xgl34222220/baize'


def source(name):
    return (SRC / name).read_text()


class RecoveryCallSiteContract(unittest.TestCase):
    def test_startup_and_all_known_ui_writers_use_integrity_barrier(self):
        application = source('BaiZeApplication.kt')
        attach = application.split('override fun attachBaseContext', 1)[1].split('override fun onCreate', 1)[0]
        self.assertIn('LegacyPreferencesAccess.initialize(this)', attach)
        for name in ['CacheActivity.kt', 'ProfileActivity.kt', 'PersistentSmartScanActivity.kt',
                     'ProtectedReviewActivity.kt', 'MiuixDashboardActivity.kt', 'ResumableSmartScanActivity.kt', 'ThemeManager.kt']:
            self.assertIn('LegacyPreferencesAccess.preferences(', source(name), name)
        self.assertNotIn('getSharedPreferences(', source('ui/appearance/AppearanceRepository.kt'))
        self.assertIn('CheckedLegacyPreferences.read(context, sourceName)', source('ui/appearance/AppearanceRepository.kt'))

    def test_no_new_raw_legacy_preference_access_can_bypass_guard(self):
        allowed = {'ApkProtection.kt'}  # One read only, immediately after the synchronous recovery gate.
        for path in SRC.rglob('*.kt'):
            if re.search(r'getSharedPreferences\(\s*"baize_v2"', path.read_text()):
                self.assertIn(path.name, allowed, str(path))
        text = source('ApkProtection.kt')
        self.assertEqual(1, text.count('getSharedPreferences("baize_v2"'))
        mutation = text.split('fun removeLegacyRules', 1)[1].split('fun readRoot', 1)[0]
        self.assertIn('LegacyPreferencesAccess.preferences(context)', mutation)

    def test_appearance_migration_is_allowlisted_copy_only(self):
        text = source('ui/appearance/AppearanceRepository.kt')
        self.assertNotIn('SharedPreferencesMigration(', text)
        self.assertIn('override suspend fun cleanUp() = Unit', text)
        self.assertIn('if (name !in present)', text)
        self.assertNotIn('source.edit()', text)

    def test_apk_legacy_read_has_synchronous_gate_before_preferences(self):
        text = source('ApkProtection.kt').split('fun legacyRules(context: Context)', 1)[1].split('\n    }', 1)[0]
        self.assertLess(text.index('LegacyProtectionRecovery.requireReviewed(context)'), text.index('getSharedPreferences'))

    def test_simple_foreground_options_never_read_ungated_legacy_fields(self):
        for name in ['CacheActivity.kt', 'ProfileActivity.kt', 'ProtectedReviewActivity.kt', 'MiuixDashboardActivity.kt']:
            text = source(name)
            self.assertNotRegex(text, r'getStringSet\("(?:path|package)_whitelist"', name)
            self.assertIn('ApkProtectionStore.legacyRules(applicationContext)', text)

    def test_foreground_module_clean_calls_gate_inside_io_error_handling(self):
        for name, expression in [('CacheActivity.kt', '"cache-clean"'), ('ProfileActivity.kt', 'cleanMode(profile)'),
                                 ('MiuixDashboardActivity.kt', '"clean"'), ('MiuixDashboardActivity.kt', 'mode')]:
            text = source(name)
            call = text.index('runModuleTask(' + expression + ')')
            preceding = text[max(0, call - 240):call]
            self.assertIn('LegacyProtectionRecovery.requireReviewed(applicationContext)', preceding)
            self.assertIn('withContext(Dispatchers.IO)', preceding)

    def test_plan_metadata_raw_reads_cannot_be_passed_to_execution(self):
        for name in ['PersistentSmartScanActivity.kt', 'ResumableSmartScanActivity.kt']:
            text = source(name)
            # The only unchecked reads are explicitly isolated to the nonblocking checksum serializer.
            start = text.index('private fun planFingerprintOptionsJson()')
            end = text.index('/** Called on IO before execution;', start)
            outside_metadata = text[:start] + text[end:]
            self.assertNotRegex(outside_metadata, r'getStringSet\("(?:path|package)_whitelist"', name)
            for line in outside_metadata.splitlines():
                if 'planFingerprintOptionsJson()' in line:
                    self.assertIn('sha256(planFingerprintOptionsJson())', line)
            self.assertIn('private fun optionsJson(protection: ApkProtectionRules = ApkProtectionStore.legacyRules(applicationContext))', text)
            self.assertRegex(text, r'cache\.cleanSelected\([\s\S]{0,220}ApkProtectionStore\.legacyRules\(applicationContext\)')
            self.assertIn('plans.cleanSafe(safeSnapshotId, selection, optionsJson())', text)

    def test_resume_transaction_and_apk_clean_have_gates(self):
        text = source('ResumableSmartScanActivity.kt')
        call = text.index('transactions.begin(')
        self.assertIn('LegacyProtectionRecovery.requireReviewed(applicationContext)', text[call - 130:call])
        self.assertIn('ApkProtectionStore.refresh(applicationContext, ApkProtectionStore.source(applicationContext, apkProtectionService))', text)


if __name__ == '__main__':
    unittest.main()
