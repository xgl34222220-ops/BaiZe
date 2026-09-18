#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENGINE="$ROOT/v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt"
APK_PATHS="$ROOT/v2/module/apk-paths.sh"
APK_SCAN="$ROOT/v2/module/apk-snapshot-scan.sh"
INDEX="$ROOT/v2/module/storage-index.sh"

grep -Fq 'val rawUsers = numericUsers(File("/data/media"))' "$ENGINE"
grep -Fq 'result.addAll(numericUsers(File("/storage/emulated")))' "$ENGINE"
grep -Fq 'path.endsWith("/android/obb") || path.contains("/android/obb/")' "$ENGINE"
! grep -Fq 'SHARED_PROTECTED.contains(name)' "$ENGINE"

grep -Fq 'BAIZE_PUBLIC_MEDIA_ROOT' "$APK_PATHS"
grep -Fq 'APK_PRIVATE_BOUNDARIES' "$APK_PATHS"
grep -Fq 'apk_collect_private_candidates' "$APK_PATHS"
! grep -Fq '[ "$\_apk_real" = "$1" ] || return 1' "$APK_PATHS"

grep -Fq 'DIRECT_APK_INDEX=' "$APK_SCAN"
grep -Fq 'SHARED_APK_INDEX="$STATE_DIR/index/apk-files.nul"' "$APK_SCAN"
grep -Fq 'direct_index_code=' "$APK_SCAN"
grep -Fq 'shared_index_code=' "$APK_SCAN"
grep -Fq 'engine=apk-snapshot-v2.3-global-index' "$APK_SCAN"

grep -Fq 'PUBLIC_MEDIA_ROOT=${BAIZE_PUBLIC_MEDIA_ROOT:-/storage/emulated}' "$INDEX"
grep -Fq 'for userdir in "$PUBLIC_MEDIA_ROOT"/[0-9]*' "$INDEX"
grep -Fq '"$MEDIA_ROOT" "$PUBLIC_MEDIA_ROOT"' "$INDEX"

echo "global discovery contract: ok"
