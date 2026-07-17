#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "config"
PACKAGE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
EXPECTED_DEEP_SHA = "73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c"


def fail(path: Path, line_no: int, message: str) -> None:
    raise SystemExit(f"{path.relative_to(ROOT)}:{line_no}: {message}")


def validate_relative_rules(name: str) -> int:
    path = CONFIG / name
    seen: set[tuple[str, str]] = set()
    count = 0
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) != 3:
            fail(path, line_no, "规则必须是 包名|相对路径|保留天数")
        package, relative, days_text = (part.strip() for part in parts)
        if not PACKAGE.fullmatch(package):
            fail(path, line_no, f"非法包名：{package}")
        if not relative or relative.startswith("/") or "//" in relative:
            fail(path, line_no, f"非法相对路径：{relative}")
        if any(segment in {"", ".", ".."} for segment in relative.split("/")):
            fail(path, line_no, f"路径包含穿越或空段：{relative}")
        if any(char in relative for char in "\x00\r\n"):
            fail(path, line_no, "路径包含控制字符")
        if not days_text.isdigit() or not 0 <= int(days_text) <= 365:
            fail(path, line_no, f"保留天数必须在 0..365：{days_text}")
        # Android/Linux paths are case-sensitive. Keep intentional variants such as Log/log.
        key = (package, relative)
        if key in seen:
            fail(path, line_no, f"重复规则：{package}|{relative}")
        seen.add(key)
        count += 1
    return count


def validate_hidden_rules() -> int:
    path = CONFIG / "hidden.rules"
    seen: set[tuple[str, str]] = set()
    count = 0
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) != 3:
            fail(path, line_no, "隐藏规则必须是 类型|名称|保留天数")
        kind, value, days_text = (part.strip() for part in parts)
        if kind not in {"dir", "file"}:
            fail(path, line_no, f"未知类型：{kind}")
        if not value or "/" in value or "\\" in value or any(char in value for char in "\x00\r\n"):
            fail(path, line_no, f"非法名称：{value!r}")
        if kind == "dir" and not value.startswith("."):
            fail(path, line_no, "隐藏目录规则必须以点开头")
        if not days_text.isdigit() or not 0 <= int(days_text) <= 365:
            fail(path, line_no, f"保留天数必须在 0..365：{days_text}")
        # `.trash` and `.Trash`, for example, can be different real directories.
        key = (kind, value)
        if key in seen:
            fail(path, line_no, f"重复隐藏规则：{kind}|{value}")
        seen.add(key)
        count += 1
    return count


def validate_deep_rules() -> int:
    path = CONFIG / "deep.rules"
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != EXPECTED_DEEP_SHA:
        raise SystemExit(f"config/deep.rules SHA 不匹配：{digest}")
    count = 0
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if not line.startswith("/"):
            fail(path, line_no, "深度规则必须是绝对路径")
        if any(segment in {"..", "."} for segment in line.split("/")):
            fail(path, line_no, "深度规则包含路径穿越")
        if line.startswith(("/data/adb", "/metadata", "/proc", "/sys", "/dev")):
            fail(path, line_no, "深度规则触及硬保护根")
        count += 1
    return count


def main() -> None:
    app = validate_relative_rules("app.rules")
    external = validate_relative_rules("external.rules")
    hidden = validate_hidden_rules()
    deep = validate_deep_rules()
    print(f"规则校验通过：应用 {app}，外部 {external}，隐藏 {hidden}，深度 {deep}")


if __name__ == "__main__":
    main()
