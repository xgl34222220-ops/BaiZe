#!/system/bin/sh
set -eu
MODDIR=${0%/*}
RULES=${BAIZE_RULES_PATH:-$MODDIR/config/deep.rules}
META=${BAIZE_RULES_META:-${RULES%/*}/rules.meta.env}
[ -f "$RULES" ] || exit 5
count=$(grep -c '^/' "$RULES" 2>/dev/null || echo 0)
sha=$(sha256sum "$RULES" | awk '{print $1}')
expected=$(sed -n 's/^rules_sha256=//p' "$META" 2>/dev/null | tail -n 1)
[ -z "$expected" ] || [ "$expected" = "$sha" ] || { echo "规则库哈希不匹配" >&2; exit 7; }
case "$count" in ''|*[!0-9]*) exit 7 ;; esac
[ "$count" -gt 100 ] || { echo "规则库数量异常" >&2; exit 7; }
echo "rules=$count sha256=$sha"
