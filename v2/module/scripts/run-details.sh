#!/system/bin/sh
# Source-only helpers: per-run item details for the 记录 detail page.
#
# A task records every item it did NOT remove, one line each, so the App can list
# them (path, size, category, plain-language reason) and let the user act on them.
#   - failed  : BaiZe tried to delete the item and the kernel refused (EACCES, EPERM,
#               EBUSY, EIO, timeout...). Only these count as 失败.
#   - skipped : BaiZe chose not to touch the item (protection list, review required,
#               changed/recent, vanished, slow or unreadable directory, size guard...).
#               Shown as a neutral 已跳过, never as an error.
#
# Boot safety: written only when a task ends, never at boot. One file per run under
# <state>/run-details/, at most BAIZE_RUN_DETAILS_MAX_ITEMS lines each and only the
# newest BAIZE_RUN_DETAILS_KEEP files kept (the budget audit enforces the same cap).
#
# Detail line (tab separated, no header besides '#' lines):
#   kind  reason_code  category  count  bytes  path  note  state
# history.tsv column 13 gets the tokens printed by baize_run_details_finalize:
#   ~v2 ; category|count|reason|sample (failed groups) ; ~skip|code|count|sample ; ~detail|<run id>
BAIZE_RUN_DETAILS_MAX_ITEMS=${BAIZE_RUN_DETAILS_MAX_ITEMS:-200}
BAIZE_RUN_DETAILS_KEEP=${BAIZE_RUN_DETAILS_KEEP:-30}
BAIZE_RUN_ID=${BAIZE_RUN_ID:-}
BAIZE_RUN_DETAIL_EXTRA=${BAIZE_RUN_DETAIL_EXTRA:-}

baize_run_details_dir() {
  printf '%s/run-details' "${BAIZE_ROOT_STATE_DIR:-${STATE_DIR:-/data/adb/baize-v2}}"
}

# baize_run_details_init MODE: choose the run id and the scratch files of this run.
baize_run_details_init() {
  _brdi_mode=$(printf '%s' "${1:-task}" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-32)
  BAIZE_RUN_ID="$(date '+%Y%m%d-%H%M%S')-$_brdi_mode-$$"
  _brdi_dir=$(baize_run_details_dir)
  mkdir -p "$_brdi_dir" 2>/dev/null || { BAIZE_RUN_ID=; return 0; }
  chmod 0700 "$_brdi_dir" 2>/dev/null || true
  BAIZE_RUN_DETAIL_EXTRA="$_brdi_dir/.building-$BAIZE_RUN_ID"
  : >"$BAIZE_RUN_DETAIL_EXTRA" 2>/dev/null || true
  # Native engines append "path<TAB>reason" for each real deletion failure here (bounded).
  BAIZE_FAILURE_ITEMS="$_brdi_dir/.failures-$BAIZE_RUN_ID"
  : >"$BAIZE_FAILURE_ITEMS" 2>/dev/null || true
  BAIZE_FAILURE_ITEMS_MAX=$BAIZE_RUN_DETAILS_MAX_ITEMS
  export BAIZE_FAILURE_ITEMS BAIZE_FAILURE_ITEMS_MAX
  return 0
}

_baize_rd_field() { printf '%s' "$1" | tr '\t\r\n' '   ' | cut -c1-600; }

# baize_run_details_add KIND REASON_CODE CATEGORY COUNT BYTES PATH [NOTE]
baize_run_details_add() {
  [ -n "$BAIZE_RUN_DETAIL_EXTRA" ] || return 0
  case "$1" in failed|skipped) ;; *) return 0 ;; esac
  _brda_count=${4:-1}; _brda_bytes=${5:-0}
  case "$_brda_count" in ''|*[!0-9]*) _brda_count=1 ;; esac
  case "$_brda_bytes" in ''|*[!0-9]*) _brda_bytes=0 ;; esac
  # Bound the scratch file too: a pathological run cannot grow it without limit.
  _brda_size=$(wc -c <"$BAIZE_RUN_DETAIL_EXTRA" 2>/dev/null | tr -d ' ')
  case "$_brda_size" in ''|*[!0-9]*) _brda_size=0 ;; esac
  [ "$_brda_size" -le 524288 ] || return 0
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t\n' "$1" "$(_baize_rd_field "$2")" "$(_baize_rd_field "$3")" \
    "$_brda_count" "$_brda_bytes" "$(_baize_rd_field "$6")" "$(_baize_rd_field "${7:-}")" \
    >>"$BAIZE_RUN_DETAIL_EXTRA" 2>/dev/null || true
}

# baize_run_details_prune DIR: newest KEEP detail files, stale scratch files removed.
baize_run_details_prune() {
  _brdp_dir=$1
  [ -d "$_brdp_dir" ] || return 0
  _brdp_seen=0
  ls -1t "$_brdp_dir" 2>/dev/null | while IFS= read -r _brdp_name; do
    case "$_brdp_name" in *.tsv) ;; *) continue ;; esac
    case "$_brdp_name" in */*|.*|'') continue ;; esac
    [ -f "$_brdp_dir/$_brdp_name" ] || continue
    _brdp_seen=$((_brdp_seen + 1))
    [ "$_brdp_seen" -le "$BAIZE_RUN_DETAILS_KEEP" ] && continue
    rm -f -- "$_brdp_dir/$_brdp_name"
  done
  find "$_brdp_dir" -maxdepth 1 -type f \( -name '.building-*' -o -name '.failures-*' -o -name '.tmp-*' \) \
    -mmin +60 -exec rm -f {} + 2>/dev/null || true
  return 0
}

# baize_run_details_finalize REPORT_FILE: write <state>/run-details/<id>.tsv from the
# per-item report (action risk category items bytes path), the native failure list and
# the rows added with baize_run_details_add. Prints the history column-13 tokens.
baize_run_details_finalize() {
  _brdf_report=${1:-}
  _brdf_dir=$(baize_run_details_dir)
  if [ -z "$BAIZE_RUN_ID" ]; then printf '~v2'; return 0; fi
  _brdf_tmp="$_brdf_dir/.tmp-$BAIZE_RUN_ID"
  _brdf_fail=${BAIZE_FAILURE_ITEMS:-/dev/null}; [ -f "$_brdf_fail" ] || _brdf_fail=/dev/null
  _brdf_extra=${BAIZE_RUN_DETAIL_EXTRA:-/dev/null}; [ -f "$_brdf_extra" ] || _brdf_extra=/dev/null
  [ -n "$_brdf_report" ] && [ -f "$_brdf_report" ] || _brdf_report=/dev/null
  _brdf_tokens=$(awk -F '\t' -v OFS='\t' -v max="$BAIZE_RUN_DETAILS_MAX_ITEMS" -v out="$_brdf_tmp" \
    -v id="$BAIZE_RUN_ID" -v now="$(date +%s)" '
    function clean(s) { gsub(/[|;]/, " ", s); return s }
    function skip_code(action, risk, category) {
      if (action == "missing" || action == "absent" || risk == "missing") return "missing"
      if (risk == "slow" || risk == "timeout") return "slow"
      if (risk == "incomplete") return "unreadable"
      if (risk == "mount") return "mount"
      if (risk == "protected") return "protected"
      if (risk == "large") return "size"
      if (category ~ /个人媒体/) return "review"
      if (action == "changed" || risk == "changed" || category ~ /变化|已修改/) return "changed"
      if (category ~ /白名单|路径保护|保护名单|应用保护/) return "protected"
      if (category ~ /大文件|大小/) return "size"
      if (category ~ /重新安装/) return "reinstalled"
      if (category ~ /无法复核/) return "unverified"
      if (category ~ /最近|近期|使用中/) return "recent"
      if (risk == "high" || risk == "critical") return "review"
      return "safety"
    }
    function add(kind, code, category, count, bytes, path, note) {
      n++; K[n]=kind; C[n]=code; G[n]=category; N[n]=count+0; B[n]=bytes+0; P[n]=path; T[n]=note
      if (kind == "failed") { failed += count; key=category SUBSEP note
        if (!(key in fc)) { fo[++fn]=key; fs[key]=path } fc[key]+=count }
      else { skipped += count; if (!(code in sc)) { so[++sn]=code; ss[code]=path } sc[code]+=count
        if (ss[code] == "" && path != "") ss[code]=path }
    }
    FILENAME == ARGV[1] { if ($1 != "") reason[$1]=$2; next }
    FILENAME == ARGV[2] {
      if (FNR == 1 && $1 == "action") next
      if (NF < 6 || $1 == "cleaned" || $1 == "candidate" || $1 == "quarantined" || $1 == "partial") next
      count = ($4 ~ /^[0-9]+$/) ? $4 : 1; if (count == 0) count = 1
      if ($1 == "failed") { note = ($6 in reason) ? reason[$6] : "系统拒绝删除"; seen[$6]=1
        add("failed", "delete_failed", $3, count, $5, $6, note) }
      else add("skipped", skip_code($1, $2, $3), $3, count, ($1 == "protected" && $3 ~ /大文件/) ? $5 : 0, $6, "")
      next
    }
    FILENAME == ARGV[3] {
      if (NF < 6 || ($1 != "failed" && $1 != "skipped")) next
      if ($1 == "failed" && $6 != "" && ($6 in seen)) next
      add($1, $2, $3, $4, $5, $6, $7)
      next
    }
    END {
      if (n == 0) { printf "~v2"; exit }
      print "#baize-run-details", "v1", id, now > out
      print "#counts", "failed=" failed+0, "skipped=" skipped+0, "listed=" ((n < max) ? n : max) > out
      listed = 0
      for (pass = 0; pass < 2; pass++) for (i = 1; i <= n && listed < max; i++) {
        if ((pass == 0) != (K[i] == "failed")) continue
        print K[i], C[i], G[i], N[i], B[i], P[i], T[i], "" > out; listed++
      }
      close(out)
      printf "~v2"
      for (i = 1; i <= fn && i <= 8; i++) { split(fo[i], part, SUBSEP)
        printf ";%s|%d|%s|%s", clean(part[1]), fc[fo[i]], clean(part[2]), clean(fs[fo[i]]) }
      for (i = 1; i <= sn && i <= 12; i++) printf ";~skip|%s|%d|%s", so[i], sc[so[i]], clean(ss[so[i]])
      printf ";~detail|%s", id
    }
  ' "$_brdf_fail" "$_brdf_report" "$_brdf_extra" 2>/dev/null)
  if [ -f "$_brdf_tmp" ]; then
    mv -f "$_brdf_tmp" "$_brdf_dir/$BAIZE_RUN_ID.tsv" 2>/dev/null || {
      rm -f "$_brdf_tmp"; _brdf_tokens=$(printf '%s' "$_brdf_tokens" | sed 's/;~detail|[^;]*//')
    }
    chmod 0600 "$_brdf_dir/$BAIZE_RUN_ID.tsv" 2>/dev/null || true
  fi
  rm -f "${BAIZE_RUN_DETAIL_EXTRA:-}" "${BAIZE_FAILURE_ITEMS:-}" 2>/dev/null
  baize_run_details_prune "$_brdf_dir"
  printf '%s' "${_brdf_tokens:-~v2}"
}
