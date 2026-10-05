# Legacy protection migration repair (2.0.0 test build 30026)

## Confirmed inherited defect

The exact signed 30024 and 30025 applications used unrestricted AndroidX 1.2.1 `SharedPreferencesMigration` from `baize_v2` to the appearance DataStore. The default migration can move non-appearance settings, then clear/delete their source even when older destination values already exist. Foreground protection readers still read `baize_v2`. A disposable-emulator upgrade reproduced the missing protection key after startup while unrelated trash data remained intact.

Build 30025 must not be treated as having complete legacy-protection upgrade validation. The defect predates its protection-detail/trash interface patch.

## Repair contract

- Appearance migration copies only explicit appearance keys, with no source cleanup.
- Existing local protection fields are authoritative, including explicit empty sets.
- Historical protection records without a current local field are ambiguous. They remain preserved for explicit in-app review instead of silently restoring possibly stale user choices.
- Recovery reads, mutations and foreground cleanup fail closed while protection is unreadable, unreviewed, or a recovery journal remains incomplete.
- Recovery uses a persistent write-ahead journal, checks the exact reviewed snapshot, commits and reads back only the selected known protection fields, and verifies completion. Unknown DataStore records and historical originals remain intact.
- Recovery is local and never rewrites Root protection rules. Normal Root checks still apply after review.

## Verification plan

The real DataStore tests reproduce AndroidX 1.2.1 all-key migration and repeated stale migration, then cover allowlisting, current/empty precedence, snapshot changes, corrupt storage, interrupted/failed writes and relaunch/removal behavior. UI tests cover explicit review, cancellation, save failures, rotation, long paths and current-rule preservation.

The signed smoke must install the exact previously delivered 30025 APK, seed a synthetic protection rule, relaunch 30025 to reproduce its production migration, replace-install 30026, show the historical rule as pending, cancel the first review without writing, explicitly confirm the selected rule, and verify semantic protection preservation across a cold launch. It also checks trash navigation and both clear-all cancellation paths without permanently deleting any fixture.

No real phone, HyperOS/ColorOS, user data deletion, main merge, official release or OTA publication is covered by this test-branch repair. Review CI evidence for the exact repaired commit before relying on these checks; this document does not assert pending checks passed.

## Startup integrity follow-up

Before delivering 30026, review found that ThemeManager startup could rewrite malformed
legacy XML using Android's empty fallback before the protection reader ran. The repair
now preflights legacy preferences in Application.attachBaseContext, before providers.
Corrupt XML, backup/temp files and the observation marker are retained in a verified,
fsynced no-backup archive. A durable quarantine blocks cleanup across restarts and later
source replacement; an incomplete or unverifiable archive stops startup. Identified
legacy writers, including editors captured before corruption, recheck integrity before
writing. Quarantine does not silently restore a backup or approve stale protection.

Ten startup regressions cover these boundaries. The signed emulator upgrade harness
also injects a synthetic malformed source after successful explicit recovery, checks
exact original/archive bytes and sealed manifest, force-stops/relaunches twice, and
requires the recovery UI to remain blocked. These are synthetic emulator fixtures only.
A sealed quarantine currently requires a separately reviewed support repair; ordinary
cleanup remains unavailable. Real HyperOS/ColorOS devices are not covered by this test.
