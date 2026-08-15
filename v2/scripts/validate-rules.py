#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "config"
PACKAGE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
META = CONFIG / "rules.meta.env"
RISK_LEVELS = {"low", "medium", "high", "critical"}


def read_meta() -> dict[str, str]:
    """规则元数据以 rules.meta.env 为唯一来源。

    此前深度规则的 SHA 硬编码在本文件、README 和 rules.meta.env 三处，
    改一次规则要同步三个地方，漏改就在 CI 上炸。
    """
    values: dict[str, str] = {}
    if not META.is_file():
        return values
    for raw in META.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


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
    audit = Audit()
    seen: set[str] = set()
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        # 规则可带可选的显式风险标注："路径|risk"
        if "|" in line:
            line, _, risk = line.rpartition("|")
            line = line.strip()
            risk = risk.strip()
            if risk not in RISK_LEVELS:
                audit.rejected += 1
                reject(path, line_no, f"未知风险等级：{risk}")
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


def validate_risk_overrides() -> Audit:
    """校验用户风险覆盖文件：绝对路径|风险等级。"""
    path = CONFIG / "risk-overrides.conf"
    audit = Audit()
    if not path.is_file():
        return audit
    seen: set[str] = set()
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        target, sep, risk = line.rpartition("|")
        target, risk = target.strip(), risk.strip()
        reason = None
        if not sep:
            reason = "格式不是 绝对路径|风险等级"
        elif risk not in RISK_LEVELS:
            reason = f"未知风险等级：{risk}"
        elif not target.startswith("/"):
            reason = "不是绝对路径"
        elif any(segment in {"..", "."} for segment in target.split("/")):
            reason = "包含路径穿越"
        if reason is not None:
            audit.rejected += 1
            reject(path, line_no, reason)
            continue
        if target in seen:
            audit.duplicates += 1
            print(f"[运行时去重] {path.relative_to(ROOT)}:{line_no}: {target}")
            continue
        seen.add(target)
        audit.accepted += 1
    return audit


def sync_meta(deep_audit: Audit, *, check_only: bool) -> int:
    """把实际的规则数量与 SHA 写回 rules.meta.env，或只校验一致性。"""
    path = CONFIG / "deep.rules"
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    meta = read_meta()
    expected_sha = meta.get("rules_sha256", "")
    expected_count = meta.get("rules_count", "")
    problems = []
    if expected_sha != digest:
        problems.append(f"rules_sha256 应为 {digest}，实际为 {expected_sha or '(缺失)'}")
    if expected_count != str(deep_audit.accepted):
        problems.append(
            f"rules_count 应为 {deep_audit.accepted}，实际为 {expected_count or '(缺失)'}"
        )
    if not problems:
        return 0
    if check_only:
        print("\nconfig/rules.meta.env 与规则文件不一致：")
        for item in problems:
            print(f"  - {item}")
        print("运行 python3 v2/scripts/validate-rules.py 自动修正。")
        return 1
    lines = META.read_text(encoding="utf-8").splitlines() if META.is_file() else []
    updated, seen_keys = [], set()
    for raw in lines:
        key = raw.split("=", 1)[0].strip()
        if key == "rules_sha256":
            updated.append(f"rules_sha256={digest}")
        elif key == "rules_count":
            updated.append(f"rules_count={deep_audit.accepted}")
        else:
            updated.append(raw)
        seen_keys.add(key)
    if "rules_sha256" not in seen_keys:
        updated.append(f"rules_sha256={digest}")
    if "rules_count" not in seen_keys:
        updated.append(f"rules_count={deep_audit.accepted}")
    META.write_text("\n".join(updated) + "\n", encoding="utf-8")
    print("\n已更新 config/rules.meta.env：")
    for item in problems:
        print(f"  - {item}")
    return 0


def main() -> None:
    check_only = "--check" in sys.argv
    audits = {
        "应用": validate_relative_rules("app.rules"),
        "外部": validate_relative_rules("external.rules"),
        "隐藏": validate_hidden_rules(),
        "深度": validate_deep_rules(),
        "风险覆盖": validate_risk_overrides(),
    }
    print("规则审计完成：")
    for name, audit in audits.items():
        print(f"- {name}：接受 {audit.accepted}，拒绝 {audit.rejected}，重复 {audit.duplicates}")

    rejected = sum(a.rejected for a in audits.values())
    code = sync_meta(audits["深度"], check_only=check_only)
    if rejected and check_only:
        print(f"\n有 {rejected} 条规则被拒绝。")
        code = 1
    if code == 0:
        print("危险或历史兼容规则不会进入删除链；元数据与规则文件一致。")
    raise SystemExit(code)


if __name__ == "__main__":
    main()
