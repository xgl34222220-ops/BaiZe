from pathlib import Path

fixer = Path("v2/scripts/apply-scheduler-hardening.py")
text = fixer.read_text()
old = '''replace_once(
    scheduler,
    '  if [ "$(bool_value daily_schedule_enabled)" = 1 ]; then',
    '  if [ "$(daily_mode_enabled)" = 1 ]; then',
)
'''
new = '''daily_flag = '  if [ "$(bool_value daily_schedule_enabled)" = 1 ]; then'
daily_mode = '  if [ "$(daily_mode_enabled)" = 1 ]; then'
scheduler_text = Path(scheduler).read_text()
if scheduler_text.count(daily_flag) != 2:
    raise SystemExit(
        f"{scheduler}: expected two standalone legacy daily checks, "
        f"found {scheduler_text.count(daily_flag)}"
    )
Path(scheduler).write_text(scheduler_text.replace(daily_flag, daily_mode))
'''
if text.count(old) != 1:
    raise SystemExit("temporary fixer source block did not match exactly once")
fixer.write_text(text.replace(old, new, 1))
Path(__file__).unlink()
