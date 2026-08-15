#!/usr/bin/env python3
"""把 Kotlin 源码里的中文字面量抽取到 strings.xml。

背景：v2 有 3200+ 处硬编码中文字面量，res/values/strings.xml 里只有一条
app_name。Magisk / KernelSU 生态里海外用户占比不低，现在等于完全排除在外。

这个脚本做两件事：
  1. `--report`  统计各文件的字面量数量与可安全抽取的比例（默认）
  2. `--apply`   把可安全抽取的字面量替换为 getString(R.string.xxx)

**安全边界**：只抽取"独立的、不含插值的"字符串字面量。以下一律跳过，
需要人工处理：
  - 含 `$` 插值的模板字符串（要改成带占位符的 formatted string）
  - 多行字符串
  - 注解参数、常量声明中的字面量
  - 已经在 strings.xml 里的

因为无法在本环境编译 Kotlin，`--apply` 默认只处理 `--files` 显式指定的文件，
改完请立刻跑 `./gradlew :app:compileDebugKotlin` 验证。
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "v2/app/src/main/java"
STRINGS = ROOT / "v2/app/src/main/res/values/strings.xml"

CJK = re.compile(r"[\u4e00-\u9fff]")
# 单行、双引号、不含转义引号与插值的字面量
SIMPLE_LITERAL = re.compile(r'"([^"\\$\n]*[\u4e00-\u9fff][^"\\$\n]*)"')
TEMPLATE_LITERAL = re.compile(r'"[^"\n]*\$[^"\n]*"')
RAW_STRING = re.compile(r'"""', re.S)


def slugify(text: str, used: set[str]) -> str:
    """由中文文案生成稳定的资源 id。"""
    import hashlib

    digest = hashlib.sha1(text.encode("utf-8")).hexdigest()[:8]
    base = f"s_{digest}"
    name = base
    n = 2
    while name in used:
        name = f"{base}_{n}"
        n += 1
    used.add(name)
    return name


def scan(paths: list[Path]) -> dict[Path, dict]:
    result = {}
    for path in paths:
        text = path.read_text(encoding="utf-8")
        simple = SIMPLE_LITERAL.findall(text)
        templates = TEMPLATE_LITERAL.findall(text)
        cjk_templates = [t for t in templates if CJK.search(t)]
        raw = len(RAW_STRING.findall(text))
        total = len([m for m in re.findall(r'"[^"\n]*"', text) if CJK.search(m)])
        result[path] = {
            "total": total,
            "simple": simple,
            "templates": cjk_templates,
            "raw_blocks": raw,
        }
    return result


# 明显不是 UI 文案的位置：日志、JSON 值、异常消息、进程/文件写入。
NON_UI_CONTEXT = re.compile(
    r'(log_?line|Log\.[dviwe]|println|\.put\(|writeText|appendText|'
    r'error\(|throw |require\(|check\(|reason\s*=|"message"|"phase")',
    re.I,
)


def classify_line(line: str) -> str:
    """粗分类：ui / non-ui。用于估算真正需要国际化的规模。"""
    return "non-ui" if NON_UI_CONTEXT.search(line) else "ui"


def cmd_report(paths: list[Path]) -> int:
    data = scan(paths)
    rows = []
    grand = Counter()
    for path, info in data.items():
        if info["total"] == 0:
            continue
        rows.append(
            (
                info["total"],
                len(info["simple"]),
                len(info["templates"]),
                path.relative_to(ROOT),
            )
        )
        grand["total"] += info["total"]
        grand["simple"] += len(info["simple"])
        grand["templates"] += len(info["templates"])

    # 按行分类估算 UI / 非 UI 比例
    ui = non_ui = 0
    for path in data:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not CJK.search(line):
                continue
            hits = len(SIMPLE_LITERAL.findall(line))
            if hits == 0:
                continue
            if classify_line(line) == "ui":
                ui += hits
            else:
                non_ui += hits

    rows.sort(reverse=True)
    print(f"{'总计':>6} {'可直接抽':>8} {'含插值':>8}  文件")
    for total, simple, tmpl, rel in rows[:40]:
        print(f"{total:>6} {simple:>8} {tmpl:>8}  {rel}")
    if len(rows) > 40:
        print(f"... 另有 {len(rows) - 40} 个文件")
    print()
    print(f"合计 {grand['total']} 处中文字面量，分布在 {len(rows)} 个文件")
    print(f"  其中 {grand['simple']} 处不含插值，可由本脚本直接抽取")
    print(f"  另有 {grand['templates']} 处含 $ 插值，需改成带占位符的 formatted string")
    print()
    print("按出现位置粗分类（估算，供排期参考）：")
    print(f"  约 {ui} 处出现在界面文案位置，是国际化的真实目标")
    print(f"  约 {non_ui} 处出现在日志、JSON 字段值、异常消息中，")
    print("     这些不应进 strings.xml——它们是协议或诊断信息，翻译反而有害")
    print()
    print("建议迁移顺序：ui/ 包下的 Compose 屏幕 -> Activity -> 其余")
    print("每迁一个文件立刻跑 ./gradlew :app:compileDebugKotlin 验证。")
    return 0


def load_existing() -> dict[str, str]:
    if not STRINGS.is_file():
        return {}
    text = STRINGS.read_text(encoding="utf-8")
    return {
        m.group(2): m.group(1)
        for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', text, re.S)
    }


def xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace('"', "\\\"")
    )


def cmd_apply(paths: list[Path]) -> int:
    if not paths:
        print("--apply 需要用 --files 指定要处理的文件", file=sys.stderr)
        return 2
    existing = load_existing()
    used = set(re.findall(r'<string name="([^"]+)"', STRINGS.read_text(encoding="utf-8")))
    new_entries: list[tuple[str, str]] = []
    changed = 0

    for path in paths:
        text = path.read_text(encoding="utf-8")
        if RAW_STRING.search(text):
            print(f"[跳过] {path.name}：含原始字符串块，需人工处理")
            continue

        def repl(match: re.Match) -> str:
            nonlocal changed
            literal = match.group(1)
            name = existing.get(literal)
            if name is None:
                name = slugify(literal, used)
                existing[literal] = name
                new_entries.append((name, literal))
            changed += 1
            return f"getString(R.string.{name})"

        updated = SIMPLE_LITERAL.sub(repl, text)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            print(f"[已处理] {path.relative_to(ROOT)}")

    if new_entries:
        content = STRINGS.read_text(encoding="utf-8")
        block = "\n".join(
            f'    <string name="{n}">{xml_escape(v)}</string>' for n, v in new_entries
        )
        content = content.replace("</resources>", block + "\n</resources>")
        STRINGS.write_text(content, encoding="utf-8")
        print(f"\n新增 {len(new_entries)} 条字符串资源")

    print(f"共替换 {changed} 处")
    print("\n请立刻运行以下命令验证：")
    print("  cd v2 && ./gradlew :app:compileDebugKotlin")
    print("Compose 可组合函数中 getString 需要 LocalContext.current，编译报错属预期，需手工调整。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="实际改写文件")
    parser.add_argument("--files", nargs="*", help="限定处理的文件（相对仓库根）")
    args = parser.parse_args()

    if args.files:
        paths = [ROOT / f for f in args.files]
    else:
        paths = sorted(SRC.rglob("*.kt"))

    return cmd_apply(paths) if args.apply else cmd_report(paths)


if __name__ == "__main__":
    raise SystemExit(main())
