#!/system/bin/sh
set -eu

# Conservative package exclusions for unattended cache cleanup.
OUT=${1:?output file required}
REASONS=${2:-${OUT}.reasons.tsv}
TMP="${OUT}.tmp.$$"
REASONS_TMP="${REASONS}.tmp.$$"
: >"$TMP"
printf 'package\treason\n' >"$REASONS_TMP"

add_packages() {
  reason=$1
  while IFS= read -r package; do
    case "$package" in ''|*[!A-Za-z0-9_.]*|.*|*.) continue ;; esac
    case "$package" in *.*) ;; *) continue ;; esac
    printf '%s\n' "$package" >>"$TMP"
    printf '%s\t%s\n' "$package" "$reason" >>"$REASONS_TMP"
  done
}

if command -v dumpsys >/dev/null 2>&1; then
  dumpsys activity activities 2>/dev/null |
    sed -n 's/.*\(mResumedActivity\|topResumedActivity\|ResumedActivity\).* u[0-9][0-9]* \([A-Za-z0-9_.][A-Za-z0-9_.]*\)\/.*/\2/p' |
    add_packages foreground-activity
  dumpsys window windows 2>/dev/null |
    sed -n 's/.*\(mCurrentFocus\|mFocusedApp\).* u[0-9][0-9]* \([A-Za-z0-9_.][A-Za-z0-9_.]*\)\/.*/\2/p' |
    add_packages focused-window
  dumpsys media_session 2>/dev/null |
    sed -n 's/.*package=\([A-Za-z0-9_.][A-Za-z0-9_.]*\).*/\1/p' |
    add_packages active-media-session
fi

printf '%s\n' com.android.providers.downloads com.android.providers.downloads.ui >>"$TMP"
printf '%s\t%s\n' com.android.providers.downloads active-transfer-provider >>"$REASONS_TMP"
printf '%s\t%s\n' com.android.providers.downloads.ui active-transfer-provider >>"$REASONS_TMP"

sort -u "$TMP" >"$OUT"
awk -F '\t' 'NR==1 || !seen[$1 FS $2]++' "$REASONS_TMP" >"$REASONS"
rm -f "$TMP" "$REASONS_TMP"
chmod 0600 "$OUT" "$REASONS" 2>/dev/null || true
