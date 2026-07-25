#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP="$ROOT/config/app.rules"
EXTERNAL="$ROOT/config/external.rules"

for expected in \
  'com.xingin.xhs|app_webview/Default/Code Cache|0' \
  'com.sina.weibo|files/xlog|0' \
  'com.zhihu.android|app_crashrecord|0' \
  'com.ss.android.ugc.aweme|files/perfUploading|0' \
  'tv.danmaku.bili|app_webview/Default/Service Worker/CacheStorage|0' \
  'com.eg.android.AlipayGphone|files/tnetlogs|0' \
  'com.taobao.taobao|app_webview/Default/Code Cache|0' \
  'com.jingdong.app.mall|files/logs|0' \
  'com.autonavi.minimap|app_crashrecord|0' \
  'com.android.chrome|app_chrome/Default/Code Cache|0'; do
  grep -Fqx "$expected" "$APP"
done

for expected in \
  'com.xingin.xhs|files/crash|0' \
  'com.sina.weibo|files/perfUploading|0' \
  'tv.danmaku.bili|files/xlog|0' \
  'com.eg.android.AlipayGphone|files/tnetlogs|0' \
  'com.xunmeng.pinduoduo|files/crash|0' \
  'com.miui.gallery|files/MiPushLog|0'; do
  grep -Fqx "$expected" "$EXTERNAL"
done

python3 - "$APP" "$EXTERNAL" <<'PY'
from pathlib import Path
import sys

markers = {
    'app.rules': '# 2026.07.25：补充常见应用的可再生日志、崩溃记录、性能与 WebView 渲染缓存。',
    'external.rules': '# 2026.07.25：补充 Android/data 下明确的诊断、崩溃、性能与网络日志目录。',
}
for raw in sys.argv[1:]:
    path = Path(raw)
    lines = path.read_text().splitlines()
    active = [line.strip() for line in lines if line.strip() and not line.lstrip().startswith('#')]
    if len(active) != len(set(active)):
        raise SystemExit(f'duplicate active rule in {path}')
    marker = markers[path.name]
    start = lines.index(marker) + 1
    forbidden = ('download', 'draft', 'database', 'databases', 'shared_prefs', 'dcim', 'pictures', 'movies', 'chat', 'message', 'attachment', 'voice')
    for line in lines[start:]:
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = line.split('|')
        if len(parts) != 3 or parts[2] != '0':
            raise SystemExit(f'invalid curated rule: {line}')
        relative = parts[1].lower()
        if any(token in relative for token in forbidden):
            raise SystemExit(f'user-data-like path rejected: {line}')
PY

echo 'curated rules contract passed'
