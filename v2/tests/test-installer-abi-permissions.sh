#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
CUSTOMIZE="$ROOT/v2/module/customize.sh"
RESOLVER="$ROOT/v2/module/abi-resolve.sh"
chmod_line=$(grep -n 'for engine in "\$MODPATH"/bin/\*/baize_engine' "$CUSTOMIZE" | head -n1 | cut -d: -f1)
resolve_line=$(grep -n 'NATIVE_ENGINE=$(baize_resolve_engine' "$CUSTOMIZE" | head -n1 | cut -d: -f1)
[ -n "$chmod_line" ] && [ -n "$resolve_line" ] && [ "$chmod_line" -lt "$resolve_line" ]
grep -Fq '[ -f "$engine" ] && chmod 0755 "$engine"' "$CUSTOMIZE"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/fakebin" "$TMP/module/bin/arm64-v8a"
cat > "$TMP/fakebin/getprop" <<'SH'
#!/bin/sh
[ "$1" = ro.product.cpu.abilist ] && printf 'arm64-v8a,armeabi-v7a\n'
SH
chmod 0755 "$TMP/fakebin/getprop"
: > "$TMP/module/bin/arm64-v8a/baize_engine"
: > "$TMP/module/bin/arm64-v8a/baize_deep_snapshot"
: > "$TMP/module/bin/arm64-v8a/baize_compat_filter"
chmod 0644 "$TMP/module/bin/arm64-v8a/baize_engine" "$TMP/module/bin/arm64-v8a/baize_deep_snapshot" "$TMP/module/bin/arm64-v8a/baize_compat_filter"
PATH="$TMP/fakebin:$PATH"
. "$RESOLVER"
! baize_resolve_engine "$TMP/module" baize_engine >/dev/null 2>&1
! baize_resolve_engine "$TMP/module" baize_compat_filter >/dev/null 2>&1
MODPATH="$TMP/module"
# Exercise the installer's actual permission loop, not a copied list of engines.
eval "$(sed -n '/^for engine in /,/^done/p' "$CUSTOMIZE")"
test "$(baize_resolve_engine "$TMP/module" baize_engine)" = "$TMP/module/bin/arm64-v8a/baize_engine"
test "$(baize_resolve_engine "$TMP/module" baize_deep_snapshot)" = "$TMP/module/bin/arm64-v8a/baize_deep_snapshot"
test -x "$TMP/module/bin/arm64-v8a/baize_engine"
test -x "$TMP/module/bin/arm64-v8a/baize_deep_snapshot"
test "$(baize_resolve_engine "$TMP/module" baize_compat_filter)" = "$TMP/module/bin/arm64-v8a/baize_compat_filter"
test -x "$TMP/module/bin/arm64-v8a/baize_compat_filter"
echo 'installer ABI permission regression passed'
