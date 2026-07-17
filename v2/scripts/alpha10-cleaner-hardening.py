from pathlib import Path

path = Path("cleaner.sh")
text = path.read_text()

anchor = '''get_uint() {
  value=$(get_value "$1")
  fallback=$2
  min=$3
  max=$4
  case "$value" in ''|*[!0-9]*) value=$fallback ;; esac
  [ "$value" -lt "$min" ] && value=$min
  [ "$value" -gt "$max" ] && value=$max
  echo "$value"
}
'''
helpers = anchor + '''
canonical_rule_path() {
  target=$1
  if command -v readlink >/dev/null 2>&1; then
    resolved=$(readlink -f -- "$target" 2>/dev/null) && [ -n "$resolved" ] && { printf '%s\\n' "$resolved"; return 0; }
  fi
  if command -v realpath >/dev/null 2>&1; then
    resolved=$(realpath -- "$target" 2>/dev/null) && [ -n "$resolved" ] && { printf '%s\\n' "$resolved"; return 0; }
  fi
  return 1
}

resolve_rule_target() {
  base=$1
  target=$2
  [ -e "$target" ] || return 1
  [ -L "$target" ] && return 1
  base_real=$(canonical_rule_path "$base") || return 1
  target_real=$(canonical_rule_path "$target") || return 1
  case "$target_real" in
    "$base_real"/*) printf '%s\\n' "$target_real"; return 0 ;;
  esac
  return 1
}

rule_target_once() {
  target=$1
  [ -n "$target" ] || return 1
  grep -Fqx -- "$target" "$RULE_SEEN_FILE" 2>/dev/null && return 1
  printf '%s\\n' "$target" >>"$RULE_SEEN_FILE"
  return 0
}
'''
if 'canonical_rule_path() {' not in text:
    if anchor not in text:
        raise SystemExit('get_uint anchor missing')
    text = text.replace(anchor, helpers, 1)

init_anchor = '''REPORT_FILE="$REPORT_DIR/$STAMP-$REQUEST_MODE.tsv"
printf 'action\\trisk\\tcategory\\titems\\tbytes\\tpath\\n' >"$REPORT_FILE"
set_phase "准备扫描"
'''
init_replacement = '''REPORT_FILE="$REPORT_DIR/$STAMP-$REQUEST_MODE.tsv"
RULE_SEEN_FILE="$TMP_DIR/rule-targets.seen"
: >"$RULE_SEEN_FILE"
printf 'action\\trisk\\tcategory\\titems\\tbytes\\tpath\\n' >"$REPORT_FILE"
set_phase "准备扫描"
'''
if 'RULE_SEEN_FILE="$TMP_DIR/rule-targets.seen"' not in text:
    if init_anchor not in text:
        raise SystemExit('report init anchor missing')
    text = text.replace(init_anchor, init_replacement, 1)

old_app = '''run_app_rules() {
  [ -f "$APP_RULES" ] || return 0
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:应用规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:规则天数] $package/$relative"; continue ;; esac
    for base in /data/user/[0-9]*/"$package" /data/user_de/[0-9]*/"$package"; do
      [ -d "$base" ] || continue
      target="$base/$relative"
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && { [ "$days" -eq 0 ] || find "$target" -type f -mtime "+$days" -print 2>/dev/null | grep -q .; }; then
        CATEGORY="应用扩展规则:$package"
        size=$(stat -c %s "$target" 2>/dev/null)
        if [ "${size:-0}" = "0" ]; then
          if [ "$CLEAN_EMPTY_FILES" = "1" ]; then handle_file "$target" empty || return $?; fi
        else
          handle_file "$target" regular || return $?
        fi
      fi
    done
  done <"$APP_RULES"
  return 0
}
'''
new_app = '''run_app_rules() {
  [ -f "$APP_RULES" ] || return 0
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:应用规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:规则天数] $package/$relative"; continue ;; esac
    [ "$days" -le 365 ] || { log_line "[拒绝:规则天数超限] $package/$relative"; continue; }
    for base in /data/user/[0-9]*/"$package" /data/user_de/[0-9]*/"$package"; do
      [ -d "$base" ] || continue
      raw_target="$base/$relative"
      target=$(resolve_rule_target "$base" "$raw_target") || {
        { [ -e "$raw_target" ] || [ -L "$raw_target" ]; } && log_line "[拒绝:规则越界或符号链接] $raw_target"
        continue
      }
      rule_target_once "$target" || continue
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && { [ "$days" -eq 0 ] || find "$target" -type f -mtime "+$days" -print 2>/dev/null | grep -q .; }; then
        CATEGORY="应用扩展规则:$package"
        size=$(stat -c %s "$target" 2>/dev/null)
        if [ "${size:-0}" = "0" ]; then
          if [ "$CLEAN_EMPTY_FILES" = "1" ]; then handle_file "$target" empty || return $?; fi
        else
          handle_file "$target" regular || return $?
        fi
      fi
    done
  done <"$APP_RULES"
  return 0
}
'''
if '规则天数超限' not in text:
    if old_app not in text:
        raise SystemExit('run_app_rules block missing')
    text = text.replace(old_app, new_app, 1)

old_external = '''run_external_rules() {
  [ -f "$EXTERNAL_RULES" ] || return 0
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:外部规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:外部规则包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:外部相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:外部规则天数] $package/$relative"; continue ;; esac
    for userdir in /data/media/[0-9]*; do
      [ -d "$userdir" ] || continue
      target="$userdir/Android/data/$package/$relative"
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "外部应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && { [ "$days" -eq 0 ] || find "$target" -type f -mtime "+$days" -print 2>/dev/null | grep -q .; }; then
        CATEGORY="外部应用扩展规则:$package"
        size=$(stat -c %s "$target" 2>/dev/null)
        if [ "${size:-0}" = "0" ]; then
          if [ "$CLEAN_EMPTY_FILES" = "1" ]; then handle_file "$target" empty || return $?; fi
        else
          handle_file "$target" regular || return $?
        fi
      fi
    done
  done <"$EXTERNAL_RULES"
  return 0
}
'''
new_external = '''run_external_rules() {
  [ -f "$EXTERNAL_RULES" ] || return 0
  while IFS='|' read -r package relative days extra || [ -n "$package$relative$days$extra" ]; do
    case "$package" in ''|'#'*) continue ;; esac
    [ -z "$extra" ] || { log_line "[拒绝:外部规则格式] $package"; continue; }
    case "$package" in *[!A-Za-z0-9._-]*) log_line "[拒绝:外部规则包名] $package"; continue ;; esac
    case "$relative" in ''|/*|*'..'*|*'//'*) log_line "[拒绝:外部相对路径] $package/$relative"; continue ;; esac
    case "$days" in ''|*[!0-9]*) log_line "[拒绝:外部规则天数] $package/$relative"; continue ;; esac
    [ "$days" -le 365 ] || { log_line "[拒绝:外部规则天数超限] $package/$relative"; continue; }
    for userdir in /data/media/[0-9]*; do
      [ -d "$userdir" ] || continue
      base="$userdir/Android/data/$package"
      [ -d "$base" ] || continue
      raw_target="$base/$relative"
      target=$(resolve_rule_target "$base" "$raw_target") || {
        { [ -e "$raw_target" ] || [ -L "$raw_target" ]; } && log_line "[拒绝:外部规则越界或符号链接] $raw_target"
        continue
      }
      rule_target_once "$target" || continue
      if [ -d "$target" ]; then
        clean_dir "$target" "$days" "外部应用扩展规则:$package" || return $?
      elif [ -f "$target" ] && { [ "$days" -eq 0 ] || find "$target" -type f -mtime "+$days" -print 2>/dev/null | grep -q .; }; then
        CATEGORY="外部应用扩展规则:$package"
        size=$(stat -c %s "$target" 2>/dev/null)
        if [ "${size:-0}" = "0" ]; then
          if [ "$CLEAN_EMPTY_FILES" = "1" ]; then handle_file "$target" empty || return $?; fi
        else
          handle_file "$target" regular || return $?
        fi
      fi
    done
  done <"$EXTERNAL_RULES"
  return 0
}
'''
if '外部规则天数超限' not in text:
    if old_external not in text:
        raise SystemExit('run_external_rules block missing')
    text = text.replace(old_external, new_external, 1)

path.write_text(text)
print('Alpha 10 cleaner target hardening complete')
