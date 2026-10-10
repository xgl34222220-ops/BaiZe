#!/usr/bin/env bash
# 交互流畅度契约（3.0.0）：列表稳定键、统一动效令牌、预测性返回、回收站撤销。
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
APP="$ROOT/v2/app/src/main"
SRC="$APP/java/io/github/xgl34222220/baize"
fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. 预测性返回：应用级开启；不得再用已废弃的 onBackPressed 覆写或拦截返回键。
grep -A12 '<application' "$APP/AndroidManifest.xml" | grep -Fq 'android:enableOnBackInvokedCallback="true"' \
  || fail "application must enable predictive back"
! grep -rEn 'override fun onBackPressed|KEYCODE_BACK' "$SRC" --include=*.kt || fail "deprecated back handling found"
grep -Fq 'BackHandler(enabled = page != BaiZePage.Home && showDock)' "$SRC/BaiZeMiuixApp.kt" \
  || fail "main tabs must return to Home on system back"

# 2. 所有 Lazy 列表的 items/itemsIndexed 都带稳定 key 与 contentType。
python3 - "$SRC" <<'PY'
import pathlib, re, sys
bad = []
for path in pathlib.Path(sys.argv[1]).rglob('*.kt'):
    text = path.read_text(encoding='utf-8')
    for match in re.finditer(r'(?<![\w.])(items|itemsIndexed)\(', text):
        depth, i = 1, match.end()
        while depth and i < len(text):
            depth += {'(': 1, '[': 1, '{': 1, ')': -1, ']': -1, '}': -1}.get(text[i], 0)
            i += 1
        args = text[match.end():i - 1]
        if 'key =' not in args or 'contentType =' not in args:
            bad.append(f"{path.name}:{text.count(chr(10), 0, match.start()) + 1}")
if bad:
    sys.exit("lazy items without key/contentType: " + ", ".join(bad))
PY

# 3. 统一短动效令牌：进入 250ms、退出/选中 200ms；二级详情不再各自写 tween(300)。
grep -Fq 'const val ENTER = 250' "$SRC/ui/theme/BaiZeTokens.kt" || fail "ENTER token"
grep -Fq 'const val EXIT = 200' "$SRC/ui/theme/BaiZeTokens.kt" || fail "EXIT token"
grep -Fq 'const val SELECTION = 200' "$SRC/ui/theme/BaiZeTokens.kt" || fail "SELECTION token"
for file in "$SRC/ui/clean/CleanRoute.kt" "$SRC/ui/settings/miuix/LuoShuSettingsHub.kt"; do
  grep -Fq 'BaiZeMotionSpecs.detailTransition' "$file" || fail "detail transition token missing in $file"
  ! grep -Fq 'tween(300)' "$file" || fail "raw tween(300) in $file"
done
grep -Fq 'BaiZeMotionSpecs.enterMillis()' "$SRC/BaiZeMiuixApp.kt" || fail "page host must use motion token"

# 4. 回收站撤销：四个移入回收站的页面都有 Snackbar「撤销」，恢复只走回收站原有核对。
for file in StorageToolsActivity.kt ApkScanActivity.kt SwipeReviewActivity.kt RootTidyScreen.kt; do
  grep -Fq 'TrashUndoSnackbarEffect(' "$SRC/$file" || fail "undo snackbar missing in $file"
  grep -Fq 'snackbarHost = { SnackbarHost(undoSnackbar) }' "$SRC/$file" || fail "snackbar host missing in $file"
done
grep -Fq 'trash.restore(entry.id, expected = entry)' "$SRC/TrashUndo.kt" || fail "undo must use reviewed restore"
! grep -Eq '\.purge\(|\.delete\(' "$SRC/TrashUndo.kt" || fail "undo must never delete"
grep -Fq 'TrashUndo.restoreBatch(' "$SRC/StorageToolsViewModel.kt" || fail "storage undo uses TrashUndo"
grep -Fq 'TrashUndo.restoreBatch(' "$SRC/ApkScanActivity.kt" || fail "apk undo uses TrashUndo"
test -f "$ROOT/v2/app/src/test/java/io/github/xgl34222220/baize/TrashUndoTest.kt" || fail "undo unit test"
test -f "$ROOT/v2/app/src/testDebug/java/io/github/xgl34222220/baize/TrashUndoSnackbarUiTest.kt" || fail "undo ui test"

# 5. 触感反馈：开始扫描、确认删除、长按勾选。
grep -Fq 'fun scanStart()' "$SRC/ui/components/BaiZeHaptics.kt" || fail "haptics helper"
grep -Fq 'haptics.confirmDelete(); onConfirm()' "$SRC/IndexedCleanupReviewDialog.kt" || fail "confirm delete haptic"
grep -Fq 'onLongClick = longPressSelect' "$SRC/ui/components/DetailPageComponents.kt" || fail "long-press select"

echo "smooth interaction contract ok"
