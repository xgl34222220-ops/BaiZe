from pathlib import Path

patcher = Path("v2/scripts/apply-ui-simplify-rules.py")
text = patcher.read_text()
old = '''replace_once(autopilot, '  [ "$HOT" = 1 ] && { GLOBAL_STATUS=waiting; GLOBAL_REASON=temperature_high; }\\n', "")'''
new = '''replace_once(autopilot, '[ "$HOT" = 1 ] && { GLOBAL_STATUS=waiting; GLOBAL_REASON=temperature_high; }\\n', "")'''
if text.count(old) != 1:
    raise SystemExit(f"expected one matcher to correct, found {text.count(old)}")
patcher.write_text(text.replace(old, new, 1))
Path(__file__).unlink(missing_ok=True)
