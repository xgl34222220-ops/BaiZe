#!/system/bin/sh
if pm path io.github.xgl34222220.baize >/dev/null 2>&1; then
  am start -n io.github.xgl34222220.baize/.MainActivity >/dev/null 2>&1
else
  echo "请先安装白泽 v2 App"
fi
