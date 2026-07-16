#!/system/bin/sh
MODDIR=${0%/*}

echo ""
echo " 白泽"
echo " ────────────────────"
"$MODDIR/cleaner.sh" clean action
echo ""
echo " 详细日志：/data/adb/safesweep/logs/latest.log"
