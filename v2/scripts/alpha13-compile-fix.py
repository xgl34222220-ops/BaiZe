from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeProfileRootService.kt")
text = path.read_text(encoding="utf-8")
replacements = {
    r'Regex("^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$")': r'Regex("""^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$""")',
    r'Regex("^/data/(?:user|user_de)/\d+/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$")': r'Regex("""^/data/(?:user|user_de)/\d+/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")',
    r'Regex("^/data/media/\d+/Android/data/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$")': r'Regex("""^/data/media/\d+/Android/data/([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"missing Kotlin regex literal: {old}")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
