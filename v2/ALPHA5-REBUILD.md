# BaiZe v2 Alpha 5 full rebuild

Alpha 4 is a rejected visual and feature prototype. Alpha 5 must not ship until the native app exposes the complete v1 cleaning surface and the integrated module installs the app.

## Cleaning profiles to migrate

- app_cache: internal cache, code_cache, Android/data external cache
- empty: empty files and empty directories with placeholder protection
- rules: hidden trash, system logs, OEM logs, custom/app/external rules
- fragments: aged temporary files, rotated logs, crash dumps and interrupted downloads
- deep: all 4,746 rules with low/medium/high/critical risk classification, scan snapshot, SHA authorization and manual high-risk opt-in
- corpses: uninstalled app leftovers in Android/data and Android/obb, package recheck before delete

## Mandatory safety

- explicit selection before destructive action
- server-side snapshot validation
- symlink, mount point and cross-filesystem protection
- whitelist parent/child conflict protection
- large-file limit
- real post-delete verification and actual freed-byte accounting
- cancellable tasks and per-directory/whole-task time budgets

## Native product UI

- neutral MIUIx palette: light/dark neutral background; blue/cyan primary; green/violet/red only as semantic accents
- no full-screen purple tint and no outline around every component
- Home: storage, one-tap clean, safe scan, active task, recent result
- Clean: category cards with per-category size/count/risk, selection and details
- Deep: rule risk summary, scan authorization and audit preview
- Schedule: per-profile interval or daily time plus execution conditions
- Records: history, reports and cumulative real freed space
- Settings: whitelist, custom rules, limits, notification and module/service state

## Performance rule

Do not wrap the old shell scanner as the primary engine. RootService must use persistent native/Kotlin traversal and indexed rules. Shell is allowed only as a temporary compatibility fallback during development and must not be the default release path.
