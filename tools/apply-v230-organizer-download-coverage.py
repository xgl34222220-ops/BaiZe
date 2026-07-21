from pathlib import Path

root = Path(__file__).resolve().parents[1]
organizer = root / "v2/app/src/main/java/io/github/xgl34222220/baize/root/FileOrganizerEngine.kt"
text = organizer.read_text()

old_download_names = '''        private val DOWNLOAD_DIRECTORY_NAMES = setOf(
            "download", "downloads", "下载",
            "received", "receive", "recv", "file_recv",
            "qqfile_recv", "qqmy_file_recv", "qqfile_receive",
            "timfile_recv", "tim_file_recv"
        )'''
new_download_names = '''        private val DOWNLOAD_DIRECTORY_NAMES = setOf(
            "download", "downloads", "downloaded", "下载",
            "received", "receive", "recv", "file_recv",
            "qqfile_recv", "qqmy_file_recv", "qqfile_receive",
            "timfile_recv", "tim_file_recv",
            "attachment", "attachments",
            "export", "exports", "saved", "shared",
            "document", "documents", "transfer", "transfers", "offline"
        )'''
if old_download_names in text:
    text = text.replace(old_download_names, new_download_names, 1)
elif new_download_names not in text:
    raise SystemExit("file organizer download directory policy anchor not found")

old_user_names = '''        private val USER_DIRECTORY_NAMES = setOf(
            "download", "downloads", "下载",
            "document", "documents", "file", "files",
            "received", "receive", "recv", "export", "exports",
            "telegram", "telegram_documents", "telegram_files",
            "nagram", "nagramx", "saved", "shared"
        )'''
new_user_names = '''        private val USER_DIRECTORY_NAMES = setOf(
            "download", "downloads", "downloaded", "下载",
            "document", "documents", "file", "files",
            "received", "receive", "recv", "export", "exports",
            "attachment", "attachments", "transfer", "transfers", "offline",
            "telegram", "telegram_documents", "telegram_files",
            "nagram", "nagramx", "saved", "shared"
        )'''
if old_user_names in text:
    text = text.replace(old_user_names, new_user_names, 1)
elif new_user_names not in text:
    raise SystemExit("file organizer user directory policy anchor not found")
organizer.write_text(text)

storage = root / "v2/module/storage-index.sh"
source = storage.read_text()

# Legacy readable storage-index implementation.
if "discover_app_user_roots" not in source and "add_root() {" in source:
    anchor = '''add_root() {
  group=$1 depth=$2 root=${3%/}
  [ -d "$root" ] || return 0
  grep -Fq "$(printf '\\t%s\\n' "$root")" "$ROOTS" 2>/dev/null && return 0
  printf '%s\\t%s\\t%s\\n' "$(safe_field "$group")" "$depth" "$root" >>"$ROOTS"
}
'''
    helper = anchor + '''

discover_app_user_roots() {
  package_root=$1
  package_name=$2
  [ -d "$package_root" ] || return 0
  discovered="$TMP/discovered.$package_name.nul"
  : >"$discovered"
  find "$package_root" -xdev -mindepth 1 -maxdepth 8 -type d \\
    \\( -iname download -o -iname downloads -o -iname downloaded -o -iname 下载 \\
       -o -iname received -o -iname receive -o -iname recv -o -iname file_recv \\
       -o -iname qqfile_recv -o -iname qqmy_file_recv -o -iname qqfile_receive \\
       -o -iname timfile_recv -o -iname tim_file_recv \\
       -o -iname attachment -o -iname attachments \\
       -o -iname export -o -iname exports -o -iname saved -o -iname shared \\
       -o -iname documents -o -iname document -o -iname transfer -o -iname transfers -o -iname offline \\) \\
    -print0 2>/dev/null >"$discovered"
  while IFS= read -r -d '' candidate; do
    lower=$(printf '%s' "$candidate" | tr '[:upper:]' '[:lower:]')
    case "$lower" in
      */cache|*/cache/*|*/code_cache|*/code_cache/*|*/databases|*/databases/*|*/shared_prefs|*/shared_prefs/*|*/no_backup|*/no_backup/*|*/tmp|*/tmp/*|*/temp|*/temp/*|*/thumbnails|*/thumbnails/*|*/.thumbnails|*/.thumbnails/*|*/crash|*/crash/*|*/crashes|*/crashes/*) continue ;;
    esac
    leaf=${candidate##*/}
    add_root "应用用户文件:$package_name:$leaf" 14 "$candidate"
  done <"$discovered"
}
'''
    if anchor not in source:
        raise SystemExit("legacy storage index add_root anchor not found")
    source = source.replace(anchor, helper, 1)
    media_anchor = '''    [ -d "$pkg" ] && add_root "应用媒体:${pkg##*/}" 14 "$pkg"
'''
    media_new = '''    [ -d "$pkg" ] || continue
    package=${pkg##*/}
    add_root "应用媒体:$package" 14 "$pkg"
    discover_app_user_roots "$pkg" "$package.media"
'''
    if media_anchor in source:
        source = source.replace(media_anchor, media_new, 1)
    data_anchor = '''    add_root "TIM接收:$package" 12 "$pkg/Tencent/Timfile_recv"
'''
    data_new = data_anchor + '''    discover_app_user_roots "$pkg" "$package"
'''
    if data_anchor in source and "discover_app_user_roots \"$pkg\" \"$package\"" not in source:
        source = source.replace(data_anchor, data_new, 1)

# Compact multi-user and removable-storage implementation.
if "discover_app_user_roots" not in source and "add_user_root(){" in source:
    helper = '''
discover_app_user_roots(){
  dau_user=$1; dau_volume=$2; dau_root=$3
  for dau_pkg in "$dau_root"/Android/data/* "$dau_root"/Android/media/*; do
    [ -d "$dau_pkg" ] || continue
    dau_name=${dau_pkg##*/}
    dau_list="$TMP/discovered.$dau_user.$dau_name.nul"
    : >"$dau_list"
    find "$dau_pkg" -xdev -mindepth 1 -maxdepth 8 -type d \\
      \\( -iname download -o -iname downloads -o -iname downloaded -o -iname 下载 \\
         -o -iname received -o -iname receive -o -iname recv -o -iname file_recv \\
         -o -iname qqfile_recv -o -iname qqmy_file_recv -o -iname qqfile_receive \\
         -o -iname timfile_recv -o -iname tim_file_recv \\
         -o -iname attachment -o -iname attachments -o -iname export -o -iname exports \\
         -o -iname saved -o -iname shared -o -iname documents -o -iname document \\
         -o -iname transfer -o -iname transfers -o -iname offline \\) -print0 2>/dev/null >"$dau_list"
    while IFS= read -r -d '' dau_dir; do
      dau_lower=$(printf '%s' "$dau_dir" | tr '[:upper:]' '[:lower:]')
      case "$dau_lower" in */cache|*/cache/*|*/code_cache|*/code_cache/*|*/databases|*/databases/*|*/tmp|*/tmp/*|*/temp|*/temp/*) continue ;; esac
      add_root "应用用户文件:$dau_name:${dau_dir##*/}" "$dau_user" "$dau_volume" 14 "$dau_dir"
    done <"$dau_list"
  done
}
'''
    position = source.find("add_user_root(){")
    source = source[:position] + helper + source[position:]

# Shell variables are global on Android sh. Every compact helper must use a unique prefix.
safe_add_root = 'add_root(){ ar_group=$1; ar_user=$2; ar_volume=$3; ar_depth=$4; ar_root=${5%/}; [ -d "$ar_root" ] || return 0; [ ! -L "$ar_root" ] || return 0; grep -Fq "$(printf \'\\t%s\\n\' "$ar_root")" "$ROOTS" 2>/dev/null && return 0; printf \'%s\\t%s\\t%s\\t%s\\t%s\\n\' "$(safe "$ar_group")" "$ar_user" "$(safe "$ar_volume")" "$ar_depth" "$ar_root" >>"$ROOTS"; }'
safe_add_user_root = 'add_user_root(){ au_user=$1; au_root=$2; au_volume=$3; add_root "共享存储" "$au_user" "$au_volume" 2 "$au_root"; add_root "QQ接收" "$au_user" "$au_volume" 12 "$au_root/Tencent/QQfile_recv"; add_root "TIM接收" "$au_user" "$au_volume" 12 "$au_root/Tencent/Timfile_recv"; for au_path in "$au_root"/Download "$au_root"/Downloads "$au_root"/Documents; do add_root "用户文件" "$au_user" "$au_volume" 12 "$au_path"; done; for au_pkg in "$au_root"/Android/media/*; do [ -d "$au_pkg" ] && add_root "应用媒体:${au_pkg##*/}" "$au_user" "$au_volume" 14 "$au_pkg"; done; for au_pkg in "$au_root"/Android/data/*; do [ -d "$au_pkg" ] || continue; au_name=${au_pkg##*/}; add_root "应用文件:$au_name" "$au_user" "$au_volume" 12 "$au_pkg/files"; add_root "应用下载:$au_name" "$au_user" "$au_volume" 10 "$au_pkg/Download"; add_root "应用下载:$au_name" "$au_user" "$au_volume" 10 "$au_pkg/Downloads"; done; discover_app_user_roots "$au_user" "$au_volume" "$au_root"; }'

lines = source.splitlines()
compact_root_found = False
compact_user_found = False
for index, line in enumerate(lines):
    if line.startswith("add_root(){"):
        lines[index] = safe_add_root
        compact_root_found = True
    elif line.startswith("add_user_root(){"):
        lines[index] = safe_add_user_root
        compact_user_found = True
if compact_root_found or compact_user_found:
    if not (compact_root_found and compact_user_found):
        raise SystemExit("compact storage index functions are incomplete")
    source = "\n".join(lines) + "\n"

if "discover_app_user_roots" not in source:
    raise SystemExit("application download discovery was not installed in storage index")
if "add_user_root(){" in source:
    required = (
        'add_root(){ ar_group=$1; ar_user=$2; ar_volume=$3; ar_depth=$4; ar_root=',
        'add_user_root(){ au_user=$1; au_root=$2; au_volume=$3;',
        'discover_app_user_roots "$au_user" "$au_volume" "$au_root"',
    )
    for marker in required:
        if marker not in source:
            raise SystemExit(f"compact storage index safety marker missing: {marker}")
    for stale in ('add_root(){ group=', 'add_user_root(){ user=', 'discover_app_user_roots "$user"'):
        if stale in source:
            raise SystemExit(f"unsafe compact storage index variables remain: {stale}")

storage.write_text(source)

checks = {
    organizer: '"attachment", "attachments"',
    storage: "discover_app_user_roots",
}
for path, marker in checks.items():
    if marker not in path.read_text():
        raise SystemExit(f"organizer download coverage marker missing in {path}")

print("generic application download discovery and shell-safe storage index applied")
