#!/system/bin/sh
# 在 Android/BusyBox 或常规 Linux shell 中执行基础静态检查。
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

for f in action.sh cleaner.sh customize.sh job-runner.sh notify.sh service.sh status.sh uninstall.sh webctl.sh; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
  sh -n "$f"
done

for f in module.prop README.md config/default.conf config/deep.rules webroot/index.html webroot/app.js webroot/style.css; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
done

RULES=$(awk '/^[[:space:]]*\// { n++ } END { print n+0 }' config/deep.rules)
[ "$RULES" = "4746" ] || { echo "深度规则数量异常: $RULES" >&2; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
  HASH=$(sha256sum config/deep.rules | awk '{print $1}')
  [ "$HASH" = "73d4c898630a292753adca33298c8aabbf6146debf414b2cabbe6b87d1d5c31c" ] || {
    echo "深度规则校验失败: $HASH" >&2
    exit 1
  }
fi

if command -v node >/dev/null 2>&1; then
  node --check webroot/app.js
fi

if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
from html.parser import HTMLParser
from pathlib import Path
import re

root = Path('.')
html = (root / 'webroot/index.html').read_text(encoding='utf-8')
js = (root / 'webroot/app.js').read_text(encoding='utf-8')
css = (root / 'webroot/style.css').read_text(encoding='utf-8')
config_text = (root / 'config/default.conf').read_text(encoding='utf-8')

class Inspector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.keys = []
        self.number_controls = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.ids.append(attrs['id'])
        if 'data-key' in attrs:
            self.keys.append(attrs['data-key'])
        if tag == 'input' and attrs.get('type') == 'number' and 'data-key' in attrs:
            self.number_controls.append((attrs['data-key'], int(attrs['min']), int(attrs['max'])))

inspector = Inspector()
inspector.feed(html)

def duplicates(values):
    return sorted({value for value in values if values.count(value) > 1})

duplicate_ids = duplicates(inspector.ids)
duplicate_keys = duplicates(inspector.keys)
assert not duplicate_ids, f'重复 HTML id: {duplicate_ids}'
assert not duplicate_keys, f'重复 data-key: {duplicate_keys}'

static_js_ids = set(re.findall(r"\$\(['\"]#([A-Za-z0-9_-]+)['\"]\)", js))
missing_ids = sorted(static_js_ids - set(inspector.ids))
assert not missing_ids, f'JavaScript 引用了不存在的 id: {missing_ids}'

config = {}
for raw in config_text.splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    key, value = line.split('=', 1)
    config[key] = value

missing_keys = sorted(set(inspector.keys) - set(config))
assert not missing_keys, f'WebUI 配置项缺少默认值: {missing_keys}'
for key, minimum, maximum in inspector.number_controls:
    value = int(config[key])
    assert minimum <= value <= maximum, f'{key} 默认值 {value} 超出 {minimum}..{maximum}'

for key in ('schedule_cache_hours', 'schedule_empty_hours', 'schedule_rules_hours', 'schedule_fragment_hours'):
    assert config[key] == '1', f'{key} 默认值必须保持 1 小时'
    assert f'min="1" max="720" data-key="{key}"' in html, f'{key} WebUI 范围必须保持 1..720'

assert css.count('{') == css.count('}'), 'CSS 花括号不平衡'
version = re.search(r'^version=(.+)$', (root / 'module.prop').read_text(encoding='utf-8'), re.M).group(1)
changelog = root / f'CHANGELOG-{version}.md'
assert changelog.is_file(), f'缺少当前版本更新日志: {changelog}'
print(f'WebUI 与配置检查通过，静态控件 {len(inspector.ids)} 个，配置项 {len(inspector.keys)} 个')
PY
fi

echo "基础检查通过，深度规则: $RULES 条"
