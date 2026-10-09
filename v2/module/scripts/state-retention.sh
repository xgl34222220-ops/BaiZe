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
