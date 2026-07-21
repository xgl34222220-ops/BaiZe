from pathlib import Path

root = Path(__file__).resolve().parents[1]
activity = root / "v2/app/src/main/java/io/github/xgl34222220/baize/FileOrganizerActivity.kt"
text = activity.read_text()

broken = 'if (path.isNotBlank()) append("\n").append(path)'
fixed = 'if (path.isNotBlank()) append("\\n").append(path)'

if fixed not in text:
    if broken not in text:
        raise SystemExit("detached organizer newline anchor missing")
    text = text.replace(broken, fixed, 1)

activity.write_text(text)
print("detached organizer Kotlin newline repaired")
