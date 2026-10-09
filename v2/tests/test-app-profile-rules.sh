#!/usr/bin/env bash
# 应用专项缓存规则：档位逐级包含、用户媒体必须“增强 + 明确开启”、非媒体档位永不触及用户数据。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
COMPILER="$ROOT/v2/module/scripts/app-profile-rules.sh"
RULES="$ROOT/config/app-profiles.rules"
T=$(mktemp -d "${TMPDIR:-/tmp}/baize-app-profile.XXXXXX")
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

shells=(sh)
command -v busybox >/dev/null 2>&1 && shells+=("busybox ash")

for shell in "${shells[@]}"; do
  # shellcheck disable=SC2086
  $shell "$COMPILER" lint "$RULES" || fail "$shell: shipped rules must lint clean"

  for tier in 0 1 2; do
    for media in 0 1; do
      # shellcheck disable=SC2086
      $shell "$COMPILER" compile "$tier" "$media" "$RULES" "$T/d$tier$media" "$T/e$tier$media" >"$T/s$tier$media"
      grep -q '^data=[0-9]* ext=[0-9]* rejected=0$' "$T/s$tier$media" || fail "$shell: summary $(cat "$T/s$tier$media")"
      # Output format is exactly what the app.rules executor accepts.
      if grep -vqE '^[A-Za-z0-9][A-Za-z0-9._-]*\|[^|/][^|]*\|[0-9]+$' "$T/d$tier$media" "$T/e$tier$media" 2>/dev/null; then
        grep -vE '^[A-Za-z0-9][A-Za-z0-9._-]*\|[^|/][^|]*\|[0-9]+$' "$T/d$tier$media" "$T/e$tier$media" || true
        fail "$shell: compiled rule format"
      fi
    done
  done

  # Tiers are cumulative: every conservative target is in standard, every standard in enhanced.
  paths() { cut -d'|' -f1,2 "$1" | sort -u; }
  for scope in d e; do
    [ -z "$(comm -23 <(paths "$T/${scope}00") <(paths "$T/${scope}10"))" ] || fail "$shell: conservative ⊄ standard ($scope)"
    [ -z "$(comm -23 <(paths "$T/${scope}10") <(paths "$T/${scope}20"))" ] || fail "$shell: standard ⊄ enhanced ($scope)"
  done
  [ "$(wc -l <"$T/d00")" -lt "$(wc -l <"$T/d10")" ] || fail "$shell: standard must add data rules"
  [ "$(wc -l <"$T/e10")" -lt "$(wc -l <"$T/e20")" ] || fail "$shell: enhanced must add external rules"

  # User media: only enhanced AND explicit opt-in.
  media_re='(^|/)(chatpic|shortvideo|ptt|image2|voice2|video2)(/|\|)'
  for combo in 00 01 10 11 20; do
    if grep -Eiq "$media_re" "$T/d$combo" "$T/e$combo"; then fail "$shell: user media leaked into tier/media=$combo"; fi
  done
  grep -Eq "^com\.tencent\.mobileqq\|Tencent/MobileQQ/chatpic\|([0-9]+)$" "$T/e21" || fail "$shell: enhanced+opt-in must include QQ chatpic"
  awk -F'|' '$2 ~ /chatpic|shortvideo/ && $3 < 7 { bad=1 } END { exit bad }' "$T/e21" || fail "$shell: media retention < 7 days"

  # Without media opt-in, no compiled rule may touch databases/prefs/chat/received files.
  deny_re='(^|/)(databases|shared_prefs|no_backup|mmkv|download|downloads|weixin|qqfile_recv|filerecv|favorite|draft|drafts|documents|dcim|pictures|movies|music)(/|\|)|\.db\|'
  for combo in 00 10 20; do
    if grep -Eiq "$deny_re" "$T/d$combo" "$T/e$combo"; then fail "$shell: user data in tier=$combo"; fi
  done

  # Same path selected by several tiers keeps the most aggressive retention only once.
  [ "$(grep -c '^com\.tencent\.mm|cache|' "$T/e20")" = 1 ] || fail "$shell: duplicate merged path"
  grep -qx 'com.tencent.mm|cache|0' "$T/e20" || fail "$shell: enhanced keeps shortest retention"
  grep -qx 'com.tencent.mm|cache|1' "$T/e10" || fail "$shell: standard retention"
  grep -q 'com.tencent.mm|cache' "$T/e00" && fail "$shell: conservative must not clean whole caches"

  # Hostile rules are rejected by lint and skipped by compile; valid lines still compile.
  cat >"$T/bad.rules" <<'EOF'
standard|data|com.tencent.mm|databases|0
standard|ext|com.tencent.mm|MicroMsg/0123456789abcdef0123456789abcdef/image2|0
standard|ext|com.tencent.mm|MicroMsg/Download|0
standard|data|com.tencent.mm|EnMicroMsg.db|0
enhanced-media|ext|com.tencent.mobileqq|Tencent/MobileQQ/chatpic|3
standard|data|com.example|../escape|0
standard|data|com.example|/abs|0
standard|data|com.example|cache/*|0
standard|data|com.example|a//b|0
standard|data|com.example|cache|999
standard|nowhere|com.example|cache|0
turbo|data|com.example|cache|0
standard|data|bad package|cache|0
standard|data|com.example|cache
standard|data|com.example.ok|files/log|0
EOF
  # shellcheck disable=SC2086
  if $shell "$COMPILER" lint "$T/bad.rules" 2>"$T/lint.err"; then fail "$shell: lint must reject hostile rules"; fi
  [ "$(grep -c '^\[拒绝' "$T/lint.err")" = 14 ] || { cat "$T/lint.err"; fail "$shell: expected 14 rejections"; }
  # shellcheck disable=SC2086
  $shell "$COMPILER" compile 2 1 "$T/bad.rules" "$T/bd" "$T/be" >"$T/bs" 2>/dev/null
  grep -q 'rejected=14' "$T/bs" || fail "$shell: compile rejection count $(cat "$T/bs")"
  [ "$(cat "$T/bd")" = 'com.example.ok|files/log|0' ] || fail "$shell: only the valid line compiles"
  [ ! -s "$T/be" ] || fail "$shell: rejected external rules must not compile"
done

# Executor wiring: the profile pass runs inside the existing app-rule gate, forces the
# media switch off below the enhanced tier and keeps its compiled rules in the task lock.
C="$ROOT/v2/module/scripts/cleaner-compat.sh"
grep -q 'run_app_profile_rules || STOPPED=1' "$C" || fail "cleaner-compat must run profile rules"
grep -q '\[ "$profile_tier" = "2" \] || profile_media=0' "$C" || fail "media gated on enhanced tier"
grep -q 'profile_data="$TMP_DIR/app-profile-data.rules"' "$C" || fail "compiled rules must live in the task tmp dir"
awk '/get_bool clean_app_rules/{gate=1} gate && /run_app_profile_rules \|\|/{found=1} /^fi$/{gate=0} END{exit !found}' "$C" || fail "profile pass must sit under clean_app_rules"
grep -q '^app_profile_tier=1$' "$ROOT/config/default.conf" || fail "default tier must be standard"
grep -q '^app_profile_user_media=0$' "$ROOT/config/default.conf" || fail "user media must default off"
echo "app profile rules passed"
