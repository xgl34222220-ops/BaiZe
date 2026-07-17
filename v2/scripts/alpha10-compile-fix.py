from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt")
text = path.read_text()

# alpha10-source-patch is intentionally removed after a verified build. Correct the two Python
# escape sequences it emits before compiling the final committed Kotlin source.
text = text.replace("raw.contains('\x00')", r"raw.contains('\u0000')")
text = text.replace(r'Regex(".*\.log\.[0-9]+$")', r'Regex(""".*\.log\.[0-9]+$""")')

if 'Regex(""".*\\.log\\.[0-9]+$""")' not in text:
    raise SystemExit("Alpha 10 regex escape correction was not applied")
if r"raw.contains('\u0000')" not in text:
    raise SystemExit("Alpha 10 NUL escape correction was not applied")

path.write_text(text)
print("Alpha 10 Kotlin escape correction complete")
