#!/system/bin/sh
set -eu

MODDIR=${0%/*}
STATE_DIR=${BAIZE_STATE_DIR:-/data/adb/baize-v2}
PRIVATE_OUT="$STATE_DIR/exports"
USER_ID=$(cmd activity get-current-user 2>/dev/null || echo 0)
case "$USER_ID" in ''|*[!0-9]*) USER_ID=0 ;; esac
PUBLIC_OUT=${BAIZE_DIAG_PUBLIC_DIR:-/storage/emulated/$USER_ID/Download/BaiZe}
STAMP=$(date '+%Y%m%d-%H%M%S')
STAGE="$PRIVATE_OUT/.diag-stage-$STAMP-$$"
OUT_DIR=$PRIVATE_OUT
PUBLIC=0

mkdir -p "$PRIVATE_OUT" "$STAGE"
if mkdir -p "$PUBLIC_OUT" 2>/dev/null && : >"$PUBLIC_OUT/.baize-write-test-$$" 2>/dev/null; then
  rm -f "$PUBLIC_OUT/.baize-write-test-$$"
  OUT_DIR=$PUBLIC_OUT
  PUBLIC=1
fi
ZIP="$OUT_DIR/BaiZe-diagnostics-$STAMP.zip"
trap 'rm -rf "$STAGE"' EXIT INT TERM

for f in module.env supervisor.env scheduler.env worker.env running.env latest.env totals.env app-install.env root-worker-profile.env runtime-schema; do
  [ -f "$STATE_DIR/$f" ] && cp -f "$STATE_DIR/$f" "$STAGE/$f"
done
[ -f "$STATE_DIR/config.conf" ] && sed -E 's#^([^=]*(path|dir|folder)[^=]*)=.*$#\1=<redacted>#' "$STATE_DIR/config.conf" >"$STAGE/config-redacted.conf" 2>/dev/null || true
[ -f "$STATE_DIR/scheduler-queue.tsv" ] && awk -F '\t' 'BEGIN{OFS="\t"} {if (NF>=6 && $6!="-") {$6="<request-file>"}; print}' "$STATE_DIR/scheduler-queue.tsv" >"$STAGE/scheduler-queue-redacted.tsv" 2>/dev/null || true
[ -f "$MODDIR/module.prop" ] && cp -f "$MODDIR/module.prop" "$STAGE/module.prop"
[ -f "$MODDIR/config/rules.meta.env" ] && cp -f "$MODDIR/config/rules.meta.env" "$STAGE/rules.meta.env"

# Paths are privacy-sensitive: retain only basename and a stable short hash.
if [ -f "$STATE_DIR/reports/latest.tsv" ]; then
  awk -F '\t' 'BEGIN{OFS="\t"} NR==1{print;next} {path=$6; cmd="printf %s \\"" path "\\" | sha256sum"; cmd|getline hash; close(cmd); n=split(path,a,"/"); $6="…/" a[n] "#" substr(hash,1,10); print}' "$STATE_DIR/reports/latest.tsv" >"$STAGE/latest-redacted.tsv" 2>/dev/null || true
fi

for log in $(ls -1t "$STATE_DIR"/logs/*.log 2>/dev/null | head -n 3); do
  sed -E 's#/(data|storage|sdcard|mnt)/[^ ]+#<redacted-path>#g; s#(package=)[A-Za-z0-9._-]+#\1<redacted-package>#g' "$log" >"$STAGE/$(basename "$log")" 2>/dev/null || true
done

root_framework=Magisk
[ -d /data/adb/ksu ] && root_framework=KernelSU
[ -d /data/adb/ap ] && root_framework=APatch
{
  echo "date=$(date '+%Y-%m-%d %H:%M:%S')"
  echo "kernel=$(uname -a 2>/dev/null)"
  echo "android=$(getprop ro.build.version.release 2>/dev/null)"
  echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
  echo "device=$(getprop ro.product.manufacturer 2>/dev/null)/$(getprop ro.product.model 2>/dev/null)"
  echo "root_framework=$root_framework"
  echo "export_location=$([ "$PUBLIC" = 1 ] && echo public-downloads || echo private-state)"
} >"$STAGE/device.env"

umask 077
(cd "$STAGE" && zip -qr "$ZIP" .)
if [ "$PUBLIC" = 1 ]; then chmod 0644 "$ZIP" 2>/dev/null || true; else chmod 0600 "$ZIP" 2>/dev/null || true; fi
printf '%s\n' "$ZIP"
