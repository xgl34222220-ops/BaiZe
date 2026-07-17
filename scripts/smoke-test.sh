#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WORK=/tmp/safesweep-smoke
STATE=/data/adb/safesweep
INTERNAL=/data/user/0/com.baize.smoke/cache
EXTERNAL=/data/media/0/Android/data/com.baize.smoke/cache

cleanup() {
  rm -rf "$WORK" "$STATE" /data/user/0/com.baize.smoke /data/media/0/Android/data/com.baize.smoke
}
trap cleanup EXIT INT TERM
cleanup
mkdir -p "$WORK" "$STATE" "$INTERNAL" "$EXTERNAL"
cp -a "$ROOT"/. "$WORK"/
cp "$WORK/config/default.conf" "$STATE/config.conf"
cp "$WORK/config/whitelist.conf" "$STATE/whitelist.conf"
cp "$WORK/config/custom.rules" "$STATE/custom.rules"

n=0
while [ "$n" -lt 1200 ]; do
  bucket=$((n / 100))
  mkdir -p "$INTERNAL/$bucket" "$EXTERNAL/$bucket"
  printf x >"$INTERNAL/$bucket/f$n.tmp"
  printf x >"$EXTERNAL/$bucket/f$n.tmp"
  n=$((n + 1))
done

sh "$WORK/cleaner.sh" deep-scan ci >/tmp/baize-smoke-scan.out
[ -f "$STATE/deep_scan.targets" ]
grep -Fq "$INTERNAL" "$STATE/deep_scan.targets"
grep -Fq "$EXTERNAL" "$STATE/deep_scan.targets"
! grep -Fq "$INTERNAL/0" "$STATE/deep_scan.targets"

sh "$WORK/cleaner.sh" deep-clean ci >/tmp/baize-smoke-clean.out
[ -d "$INTERNAL" ] && [ -d "$EXTERNAL" ]
[ "$(find "$INTERNAL" -type f | wc -l | tr -d ' ')" = "0" ]
[ "$(find "$EXTERNAL" -type f | wc -l | tr -d ' ')" = "0" ]

echo "性能冒烟测试通过：缓存根目录保留，分片规则已合并"
