#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/state"
printf apk > "$work/app.apk"
sha256sum "$work/app.apk" | awk '{print $1}' > "$work/app.sha256"
cat > "$work/bin/pm" <<'MOCK'
#!/bin/sh
case "$1" in
 path) echo package:/data/app/example/base.apk; exit 0 ;;
 install)
  printf '%s\n' "$*" >> "$PM_CALLS"
  case " $* " in *' -d '*) exit 99;; esac
  case "$PM_RESULT" in
   success) echo Success; exit 0;;
   *) echo "Failure [$PM_RESULT]" >&2; exit 1;;
  esac ;;
esac
exit 1
MOCK
cat > "$work/bin/dumpsys" <<'MOCK'
#!/bin/sh
echo versionName=2.7.1
MOCK
chmod +x "$work/bin/pm" "$work/bin/dumpsys"
export PATH="$work/bin:$PATH" PM_CALLS="$work/calls"
export BAIZE_APK="$work/app.apk" BAIZE_HASH_FILE="$work/app.sha256" BAIZE_STATE_DIR="$work/state"
check() {
 export PM_RESULT="$1"
 rm -f "$work/state/installed-app.sha256"
 : > "$PM_CALLS"
 set +e
 sh "$ROOT/v2/module/app-installer.sh" ensure >/dev/null 2>&1
 code=$?
 set -e
 test "$code" = "$2"
 grep -Fxq "reason=$3" "$work/state/app-install.env"
 test "$(wc -l < "$PM_CALLS")" = 1
}
check INSTALL_FAILED_UPDATE_INCOMPATIBLE 11 preserved_existing_app
check INSTALL_FAILED_INSUFFICIENT_STORAGE 12 insufficient_storage
check INSTALL_FAILED_VERSION_DOWNGRADE 12 version_downgrade_blocked
check INSTALL_FAILED_INTERNAL_ERROR 12 install_failed
check success 0 installed_or_updated
cmp "$work/app.sha256" "$work/state/installed-app.sha256"
echo 'app installer failure classification passed'
