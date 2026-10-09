#!/system/bin/sh
# Source-only helpers. Every file under /data/adb is relabeled by init's
# `restorecon --recursive --skip-ce /data` on each boot, so the number of loose
# files in the module state directory is a boot-time cost. Keep per-task ledgers
# bounded; nothing here touches cleaned data, reports, rules or configuration.
BAIZE_TASK_RESULTS_KEEP=${BAIZE_TASK_RESULTS_KEEP:-200}
BAIZE_TASK_LOGS_KEEP=${BAIZE_TASK_LOGS_KEEP:-200}

# baize_prune_newest DIR SUFFIX_GLOB KEEP: keep the newest KEEP regular files whose
# name matches the case pattern, remove the rest. Lists the directory itself so a
# huge legacy directory cannot overflow the exec argument limit.
baize_prune_newest() {
  _bpn_dir=$1; _bpn_glob=$2; _bpn_keep=$3
  case "$_bpn_keep" in ''|*[!0-9]*) return 0 ;; esac
  [ -d "$_bpn_dir" ] || return 0
  _bpn_seen=0
  ls -1t "$_bpn_dir" 2>/dev/null | while IFS= read -r _bpn_name; do
    # shellcheck disable=SC2254
    case "$_bpn_name" in $_bpn_glob) ;; *) continue ;; esac
    case "$_bpn_name" in */*|.|..|'') continue ;; esac
    [ -f "$_bpn_dir/$_bpn_name" ] || continue
    _bpn_seen=$((_bpn_seen + 1))
    [ "$_bpn_seen" -le "$_bpn_keep" ] && continue
    rm -f -- "$_bpn_dir/$_bpn_name"
  done
  return 0
}

# Results: the App reads the newest 60; startup acknowledgements are transient and
# only a launcher that timed out can leave one behind.
baize_prune_task_results() {
  _bptr_dir=$1
  [ -d "$_bptr_dir" ] || return 0
  baize_prune_newest "$_bptr_dir" '*.env' "$BAIZE_TASK_RESULTS_KEEP"
  find "$_bptr_dir" -maxdepth 1 -type f -name '*.started' -mmin +60 -exec rm -f {} + 2>/dev/null || true
  find "$_bptr_dir" -maxdepth 1 -type f -name '*.env.tmp.*' -mmin +60 -exec rm -f {} + 2>/dev/null || true
  return 0
}

# Per-task worker logs follow the same bound as the results that point at them.
baize_prune_task_logs() {
  _bptl_dir=$1
  baize_prune_newest "$_bptl_dir" 'worker-*.log' "$BAIZE_TASK_LOGS_KEEP"
  baize_prune_newest "$_bptl_dir" 'cache-lane-*' "$BAIZE_TASK_LOGS_KEEP"
}

# ---- Whole-directory budget (boot safety) -------------------------------------
# Every per-run artifact with a generated name must be capped here. The CI test
# v2/tests/test-boot-safety.sh fails when a script starts writing a new kind of
# dynamically named file under the state directory without a matching cap.
BAIZE_REPORTS_KEEP=${BAIZE_REPORTS_KEEP:-40}
BAIZE_RUN_LOGS_KEEP=${BAIZE_RUN_LOGS_KEEP:-40}
BAIZE_ORGANIZER_LOGS_KEEP=${BAIZE_ORGANIZER_LOGS_KEEP:-20}
BAIZE_LOG_MAX_BYTES=${BAIZE_LOG_MAX_BYTES:-262144}
BAIZE_LOG_KEEP_BYTES=${BAIZE_LOG_KEEP_BYTES:-65536}
BAIZE_STATE_FILE_BUDGET=${BAIZE_STATE_FILE_BUDGET:-5000}

# baize_rotate_log FILE: keep one file. When it grows past the limit, keep only its
# tail in place (same inode, so `>>` writers keep appending) instead of adding .1/.2.
baize_rotate_log() {
  _brl_file=$1
  [ -f "$_brl_file" ] && [ ! -L "$_brl_file" ] || return 0
  _brl_size=$(wc -c <"$_brl_file" 2>/dev/null | tr -d ' ')
  case "$_brl_size" in ''|*[!0-9]*) return 0 ;; esac
  [ "$_brl_size" -gt "$BAIZE_LOG_MAX_BYTES" ] || return 0
  tail -c "$BAIZE_LOG_KEEP_BYTES" "$_brl_file" >"$_brl_file.rot.$$" 2>/dev/null &&
    cat "$_brl_file.rot.$$" >"$_brl_file" 2>/dev/null
  rm -f "$_brl_file.rot.$$"
  return 0
}

# baize_append_capped FILE LINE MAXLINES: one compact log line, file never exceeds MAXLINES.
baize_append_capped() {
  _bac_file=$1; _bac_line=$2; _bac_max=${3:-50}
  printf '%s\n' "$_bac_line" >>"$_bac_file" 2>/dev/null || return 0
  _bac_count=$(wc -l <"$_bac_file" 2>/dev/null | tr -d ' ')
  case "$_bac_count" in ''|*[!0-9]*) return 0 ;; esac
  [ "$_bac_count" -le "$_bac_max" ] && return 0
  tail -n "$_bac_max" "$_bac_file" >"$_bac_file.tmp.$$" 2>/dev/null && mv -f "$_bac_file.tmp.$$" "$_bac_file"
  rm -f "$_bac_file.tmp.$$" 2>/dev/null
  return 0
}

# baize_enforce_state_budget STATE_DIR: caps every generated-name artifact family and
# rotates the long-lived append logs. Cheap: lists only the top of reports/ and logs/.
baize_enforce_state_budget() {
  _bes_dir=$1
  [ -d "$_bes_dir" ] || return 0
  # Timestamped per-run reports and logs (YYYY-MM-DD_HH-MM-SS-<mode>.*); latest.* stay.
  baize_prune_newest "$_bes_dir/reports" '20[0-9][0-9]-*.tsv' "$BAIZE_REPORTS_KEEP"
  baize_prune_newest "$_bes_dir/logs" '20[0-9][0-9]-*.log' "$BAIZE_RUN_LOGS_KEEP"
  baize_prune_newest "$_bes_dir/reports" 'cache-lane-*' "$BAIZE_REPORTS_KEEP"
  baize_prune_newest "$_bes_dir/logs" 'organizer-*.log' "$BAIZE_ORGANIZER_LOGS_KEEP"
  baize_prune_task_logs "$_bes_dir/logs"
  baize_prune_task_results "$_bes_dir/task-results"
  for _bes_log in "$_bes_dir"/logs/*.log "$_bes_dir"/logs/*.log.1; do
    [ -f "$_bes_log" ] || continue
    case "${_bes_log##*/}" in 20[0-9][0-9]-*|worker-*|organizer-*|cache-lane-*) continue ;; esac
    baize_rotate_log "$_bes_log"
  done
  return 0
}

# baize_state_file_count STATE_DIR: one bounded walk of our own directory, used only
# by the hourly background audit (never at boot). Prints the number of entries.
baize_state_file_count() {
  find "$1" -xdev 2>/dev/null | wc -l | tr -d ' '
}

# baize_state_budget_audit STATE_DIR: enforce caps, then record the entry count in one
# fixed file; an over-budget count is logged (capped) so diagnostics can show it.
baize_state_budget_audit() {
  _bsa_dir=$1
  [ -d "$_bsa_dir" ] || return 0
  baize_enforce_state_budget "$_bsa_dir"
  _bsa_files=$(baize_state_file_count "$_bsa_dir")
  case "$_bsa_files" in ''|*[!0-9]*) _bsa_files=0 ;; esac
  _bsa_over=0; [ "$_bsa_files" -le "$BAIZE_STATE_FILE_BUDGET" ] || _bsa_over=1
  { echo "files=$_bsa_files"; echo "budget=$BAIZE_STATE_FILE_BUDGET"; echo "over_budget=$_bsa_over"; echo "updated=$(date +%s)"; } \
    >"$_bsa_dir/state-budget.env.tmp" && mv -f "$_bsa_dir/state-budget.env.tmp" "$_bsa_dir/state-budget.env"
  if [ "$_bsa_over" = 1 ]; then
    mkdir -p "$_bsa_dir/logs" 2>/dev/null
    baize_append_capped "$_bsa_dir/logs/state-budget.log" "$(date '+%F %T') files=$_bsa_files budget=$BAIZE_STATE_FILE_BUDGET" 30
  fi
  return 0
}

# CLI entry (only when executed directly, never when sourced by another script).
case "${0##*/}" in
  state-retention.sh)
    [ "${1:-}" = budget ] && [ -n "${2:-}" ] || { echo "用法: state-retention.sh budget STATE_DIR" >&2; exit 2; }
    renice -n 19 -p "$$" >/dev/null 2>&1 || true
    ionice -c 3 -p "$$" >/dev/null 2>&1 || true
    baize_state_budget_audit "$2"
    ;;
esac
