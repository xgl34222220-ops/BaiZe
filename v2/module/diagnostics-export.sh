#!/system/bin/sh
set -eu
MODDIR=${0%/*}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
OUT_DIR="$STATE_DIR/exports"
STAMP=$(date '+%Y%m%d-%H%M%S')
STAGE="$OUT_DIR/diag-$STAMP"
ZIP="$OUT_DIR/BaiZe-diagnostics-$STAMP.zip"
mkdir -p "$STAGE"
for f in module.env supervisor.env scheduler.env worker.env running.env latest.env totals.env app-install.env root-worker-profile.env; do
  [ -f "$STATE_DIR/$f" ] && cp -f "$STATE_DIR/$f" "$STAGE/$f"
done
[ -f "$MODDIR/module.prop" ] && cp -f "$MODDIR/module.prop" "$STAGE/module.prop"
[ -f "$MODDIR/config/rules.meta.env" ] && cp -f "$MODDIR/config/rules.meta.env" "$STAGE/rules.meta.env"
# Paths are privacy-sensitive: retain only basename and a stable short hash.
if [ -f "$STATE_DIR/reports/latest.tsv" ]; then
  awk -F '\t' 'BEGIN{OFS="\t"} NR==1{print;next} {path=$6; cmd="printf %s \\"" path "\\" | sha256sum"; cmd|getline hash; close(cmd); n=split(path,a,"/"); $6="…/" a[n] "#" substr(hash,1,10); print}' "$STATE_DIR/reports/latest.tsv" >"$STAGE/latest-redacted.tsv" 2>/dev/null || true
fi
for log in $(ls -1t "$STATE_DIR"/logs/*.log 2>/dev/null | head -n 3); do
  sed -E 's#/(data|storage|sdcard|mnt)/[^ ]+#<redacted-path>#g' "$log" >"$STAGE/$(basename "$log")" 2>/dev/null || true
done
{
  echo "date=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "kernel=$(uname -a 2>/dev/null)"
  echo "android=$(getprop ro.build.version.release 2>/dev/null)"
  echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
  echo "device=$(getprop ro.product.manufacturer 2>/dev/null)/$(getprop ro.product.model 2>/dev/null)"
  echo "root_framework=$([ -n "${KSU:-}" ] && echo KernelSU || { [ -n "${APATCH:-}" ] && echo APatch || echo Magisk; })"
} >"$STAGE/device.env"
(cd "$STAGE" && zip -qr "$ZIP" .)
rm -rf "$STAGE"
chmod 0600 "$ZIP" 2>/dev/null || true
echo "$ZIP"
