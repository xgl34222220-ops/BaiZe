from pathlib import Path

cleaner = Path("cleaner.sh")
text = cleaner.read_text(encoding="utf-8")
old = r'''printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" >>"$HISTORY_FILE"'''
new = r'''printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$REQUEST_MODE" "$BYTES" "$TOTAL_FILES" "$EMPTY_DIRS" "$ERRORS" "$RESULT" "$TRIGGER" >>"$HISTORY_FILE"'''
if old not in text:
    raise SystemExit("missing literal cleaner history line")
cleaner.write_text(text.replace(old, new, 1), encoding="utf-8")

patch = Path("v2/scripts/alpha20-source-patch.py")
source = patch.read_text(encoding="utf-8")
start_marker = "# Preserve trigger information for new records while remaining compatible with the old seven-column file.\n"
end_marker = "# Dashboard history UI and rendering.\n"
start = source.find(start_marker)
end = source.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("missing cleaner patch block markers")
patch.write_text(source[:start] + "# Cleaner history trigger was applied by alpha20-prepatch.py.\n" + source[end:], encoding="utf-8")
