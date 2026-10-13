#!/usr/bin/env bash
# 记录详情：逐项明细文件 run-details/<id>.tsv 与 history.tsv 第 13 列 ~v2 标记。
# - 只有尝试删除却被系统拒绝的项目算 failed；保护名单、变化、消失、慢目录、读取不完整都是 skipped。
# - 每次最多 BAIZE_RUN_DETAILS_MAX_ITEMS 行，只保留最新 BAIZE_RUN_DETAILS_KEEP 份，不留临时文件。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
HELPER="$ROOT/v2/module/scripts/run-details.sh"
T=$(mktemp -d "${TMPDIR:-/tmp}/baize-run-details.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

shells=(sh)
command -v busybox >/dev/null 2>&1 && shells+=("busybox ash")

for SH in "${shells[@]}"; do
  S="$T/state"; rm -rf "$S"; mkdir -p "$S"
  R="$T/report.tsv"
  printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$R"
  printf 'cleaned\tlow\tAPK安装包\t1\t100\t/x/a.apk\n' >>"$R"
  printf 'protected\thigh\t路径保护\t1\t0\t/x/protected.apk\n' >>"$R"
  printf 'protected\tlow\t大文件保护\t1\t5000\t/x/large.apk\n' >>"$R"
  printf 'missing\tlow\t目标已不存在\t1\t0\t/x/gone.apk\n' >>"$R"
  printf 'skipped\tslow\t规则垃圾\t1\t0\t/x/slow\n' >>"$R"
  printf 'skipped\tincomplete\t空目录:规则\t1\t0\t/x/unreadable\n' >>"$R"
  printf 'protected\tchanged\t规则垃圾\t3\t0\t候选状态已变化\n' >>"$R"
  printf 'failed\tlow\t深度不可变快照\t1\t300\t/x/denied.bin\n' >>"$R"
  # shellcheck disable=SC2086
  tokens=$(STATE_DIR="$S" $SH -c '. "$1"; baize_run_details_init deep-clean
    printf "/x/denied.bin\tPermission denied\n" >>"$BAIZE_FAILURE_ITEMS"
    baize_run_details_finalize "$2"' _ "$HELPER" "$R")
  case "$tokens" in "~v2;"*) ;; *) fail "$SH tokens must start with ~v2: $tokens" ;; esac
  case "$tokens" in *"深度不可变快照|1|Permission denied|/x/denied.bin"*) ;; *) fail "$SH failed group: $tokens" ;; esac
  for code in protected size missing slow unreadable changed; do
    case "$tokens" in *"~skip|$code|"*) ;; *) fail "$SH skip $code missing: $tokens" ;; esac
  done
  case "$tokens" in *"~skip|changed|3|"*) ;; *) fail "$SH changed count: $tokens" ;; esac
  id=${tokens##*~detail|}
  file="$S/run-details/$id.tsv"
  [ -f "$file" ] || fail "$SH detail file missing"
  grep -q $'^#counts\tfailed=1\tskipped=8\t' "$file" || fail "$SH counts: $(cat "$file")"
  [ "$(grep -c '^failed' "$file")" = 1 ] || fail "$SH failed rows"
  first=$(grep -v '^#' "$file" | head -n 1)
  [ "${first%%$'\t'*}" = failed ] || fail "$SH failed rows must come first"
  grep -q $'^failed\tdelete_failed\t深度不可变快照\t1\t300\t/x/denied.bin\tPermission denied' "$file" || fail "$SH reason lookup"
  grep -q 'APK安装包' "$file" && fail "$SH cleaned rows must not be listed"
  [ -z "$(find "$S/run-details" -name '.*' -type f)" ] || fail "$SH scratch files left"

  # Clean run: no detail file, just ~v2.
  printf 'action\trisk\tcategory\titems\tbytes\tpath\ncleaned\tlow\tx\t1\t1\t/x/ok\n' >"$R"
  # shellcheck disable=SC2086
  tokens=$(STATE_DIR="$S" $SH -c '. "$1"; baize_run_details_init rules-clean; baize_run_details_finalize "$2"' _ "$HELPER" "$R")
  [ "$tokens" = "~v2" ] || fail "$SH clean run tokens: $tokens"

  # Script-listed failures replace aggregate report rows (no double counting).
  printf 'action\trisk\tcategory\titems\tbytes\tpath\nfailed\tlow\tAPK安装包\t2\t0\t仍存在的安装包\n' >"$R"
  # shellcheck disable=SC2086
  tokens=$(STATE_DIR="$S" $SH -c '. "$1"; baize_run_details_init apk-auto
    baize_run_details_add failed delete_failed APK安装包 1 0 /x/one.apk "Operation not permitted"
    baize_run_details_add failed delete_failed APK安装包 1 0 /x/two.apk "Device or resource busy"
    baize_run_details_finalize "$2"' _ "$HELPER" "$R")
  case "$tokens" in *"仍存在的安装包"*) fail "$SH aggregate row double counted: $tokens" ;; esac
  id=${tokens##*~detail|}
  [ "$(grep -c '^failed' "$S/run-details/$id.tsv")" = 2 ] || fail "$SH listed failures"

  # Bounded: rows per run and files kept.
  : >"$R"; printf 'action\trisk\tcategory\titems\tbytes\tpath\n' >"$R"
  for i in $(seq 1 50); do printf 'protected\thigh\t路径保护\t1\t0\t/x/p%s\n' "$i" >>"$R"; done
  for run in $(seq 1 6); do
    # shellcheck disable=SC2086
    STATE_DIR="$S" BAIZE_RUN_DETAILS_MAX_ITEMS=10 BAIZE_RUN_DETAILS_KEEP=3 $SH -c \
      '. "$1"; BAIZE_RUN_ID=; baize_run_details_init "loop$3"; baize_run_details_finalize "$2" >/dev/null' _ "$HELPER" "$R" "$run"
  done
  [ "$(find "$S/run-details" -maxdepth 1 -name '*.tsv' | wc -l | tr -d ' ')" -le 3 ] || fail "$SH keep cap"
  newest=$(ls -1t "$S/run-details"/*.tsv | head -n 1)
  [ "$(grep -vc '^#' "$newest")" = 10 ] || fail "$SH row cap"
  grep -q $'^#counts\tfailed=0\tskipped=50\tlisted=10' "$newest" || fail "$SH counts keep totals beyond the cap"
done
echo "run details passed"
