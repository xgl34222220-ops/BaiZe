#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
CUSTOMIZE="$ROOT/v2/module/customize.sh"
sh -n "$CUSTOMIZE"
last=$(awk 'NF {line=$0} END {print line}' "$CUSTOMIZE")
test "$last" = 'exit 0'
grep -Fq 'if ! cp -f "$HASH_FILE" "$STATE_DIR/installed-app.sha256" 2>/dev/null; then' "$CUSTOMIZE"
grep -Fq 'App 安装状态记录失败，不影响模块安装' "$CUSTOMIZE"
grep -Fq '白泽模块安装脚本完成' "$CUSTOMIZE"
grep -Fq 'abort "! 模块包中缺少 app/baize.apk"' "$CUSTOMIZE"
grep -Fq 'abort "! 模块包中没有适配当前架构' "$CUSTOMIZE"
echo 'installer explicit-success regression passed'
