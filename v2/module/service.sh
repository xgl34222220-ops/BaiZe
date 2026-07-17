#!/system/bin/sh
STATE=/data/adb/baize-v2/module.env
mkdir -p /data/adb/baize-v2
{
  echo "boot_epoch=$(date +%s)"
  if pm path io.github.xgl34222220.baize >/dev/null 2>&1; then
    echo "app_installed=1"
  else
    echo "app_installed=0"
  fi
} > "$STATE.tmp"
mv -f "$STATE.tmp" "$STATE"
chmod 0600 "$STATE"
