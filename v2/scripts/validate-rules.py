#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "config"
PACKAGE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
EXPECTED_DEEP_SHA = "73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c"


@dataclass
class Audit:
    accepted: int = 0
    rejected: int = 0
    duplicates: int = 0


def reject(path: Path, line_no: int, message: str) -> None:
    print(f"[运行时拒绝] {path.relative_to(ROOT)}:{line_no}: {message}")


def validate_relative_rules(name: str) -> Audit:
    path = CONFIG / name
    seen: set[tuple[str, str]] = set()
    audit = Audit()
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) != 3:
            audit.rejected += 1
            reject(path, line_no, "格式不是 包名|相对路径|保留天数")
            continue
        package, relative, days_text = (part.strip() for part in parts)
        reason = None
        if not PACKAGE.fullmatch(package):
            reason = f"非法包名：{package}"
        elif not relative or relative.startswith("/") or "//" in relative:
            reason = f"非法相对路径：{relative}"
        elif any(segment in {"", ".", ".."} for segment in relative.split("/")):
            reason = f"路径包含穿越或空段：{relative}"
        elif any(char in relative for char in "\x00\r\n"):
            reason = "路径包含控制字符"
        elif not days_text.isdigit() or not 0 <= int(days_text) <= 365:
            reason = f"保留天数不在 0..365：{days_text}"
        if reason is not None:
            audit.rejected += 1
            reject(path, line_no, reason)
            continue
        key = (package, relative)
        if key in seen:
            audit.duplicates += 1
            print(f"[运行时去重] {path.relative_to(ROOT)}:{line_no}: {package}|{relative}")
            continue
        seen.add(key)
        audit.accepted += 1
    return audit


def validate_hidden_rules() -> Audit:
    path = CONFIG / "hidden.rules"
    seen: set[tuple[str, str]] = set()
    audit = Audit()
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) != 3:
            audit.rejected += 1
            reject(path, line_no, "格式不是 类型|名称|保留天数")
            continue
        kind, value, days_text = (part.strip() for part in parts)
        reason = None
        if kind not in {"dir", "file"}:
            reason = f"未知类型：{kind}"
        elif not value or "/" in value or "\\" in value or any(char in value for char in "\x00\r\n"):
            reason = f"非法名称：{value!r}"
        elif kind == "dir" and not value.startswith("."):
            reason = "隐藏目录规则没有以点开头"
        elif not days_text.isdigit() or not 0 <= int(days_text) <= 365:
            reason = f"保留天数不在 0..365：{days_text}"
        if reason is not None:
            audit.rejected += 1
            reject(path, line_no, reason)
            continue
        key = (kind, value)
        if key in seen:
            audit.duplicates += 1
            print(f"[运行时去重] {path.relative_to(ROOT)}:{line_no}: {kind}|{value}")
            continue
        seen.add(key)
        audit.accepted += 1
    return audit


def validate_deep_rules() -> Audit:
    path = CONFIG / "deep.rules"
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != EXPECTED_DEEP_SHA:
        raise SystemExit(f"config/deep.rules SHA 不匹配：{digest}")
    audit = Audit()
    seen: set[str] = set()
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        reason = None
        if not line.startswith("/"):
            reason = "不是绝对路径"
        elif any(char in line for char in "\x00\r\n"):
            reason = "包含控制字符"
        elif any(segment in {"..", "."} for segment in line.split("/")):
            reason = "包含路径穿越"
        elif line.startswith(("/data/adb", "/metadata", "/proc", "/sys", "/dev")):
            reason = "触及硬保护根"
        if reason is not None:
            audit.rejected += 1
            reject(path, line_no, reason)
            continue
        if line in seen:
            audit.duplicates += 1
            print(f"[运行时去重] {path.relative_to(ROOT)}:{line_no}: {line}")
            continue
        seen.add(line)
        audit.accepted += 1
    return audit


def main() -> None:
    audits = {
        "应用": validate_relative_rules("app.rules"),
        "外部": validate_relative_rules("external.rules"),
        "隐藏": validate_hidden_rules(),
        "深度": validate_deep_rules(),
    }
    print("规则审计完成：")
    for name, audit in audits.items():
        print(f"- {name}：接受 {audit.accepted}，拒绝 {audit.rejected}，重复 {audit.duplicates}")
    print("危险或历史兼容规则不会进入删除链；深度规则原始 SHA 校验通过。")


if __name__ == "__main__":
    main()
