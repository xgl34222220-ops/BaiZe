#!/usr/bin/env python3
"""白泽 3.0.1 应用图标（矢量版）的唯一几何来源。

一份图形定义同时生成：
  tools/icon/baize-icon.svg                              SVG 源（108×108 视口，背景 + 前景）
  v2/app/src/main/res/drawable/ic_baize_background.xml   自适应图标背景（浅青渐变 + 左上柔光）
  v2/app/src/main/res/drawable/ic_baize_foreground.xml   自适应图标前景（磨砂玻璃文档卡 + 存储环 + 星光 + 祥云）
  v2/app/src/main/res/drawable/ic_baize_monochrome.xml   Android 13 主题图标（单色）
  v2/app/src/main/res/drawable/ic_baize_splash_mark.xml  启动页图标（背景裁成 72 视口内的圆 + 前景）
设计稿里的旋转、缩放全部在这里预先烘焙进绝对坐标路径（渐变端点、描边宽度同步变换），
SVG 与 Android 矢量都不含任何 transform，因此两者几何逐字一致。图形为白泽原创手写矢量。

用法：
  python3 tools/icon/baize_icon.py           重新生成上述文件
  python3 tools/icon/baize_icon.py --check   只校验已提交文件与本定义一致（纯 Python，无依赖）
旧式 mipmap / 品牌位图由 tools/icon/render_legacy.py 从 SVG 渲染。
"""
from pathlib import Path
import math
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'v2/app/src/main/res'
W = '#FFFFFF'
SHADOW = '#0B5E63'
LINE = '#3DB7B2'
K = '#000000'


# ---------------------------------------------------------------- 仿射变换（仅相似变换：平移 / 等比缩放 / 旋转）
class T:
    def __init__(self, a=1.0, b=0.0, c=0.0, d=1.0, e=0.0, f=0.0):
        self.m = (a, b, c, d, e, f)

    def __matmul__(self, o):  # self ∘ o：先 o 后 self
        a, b, c, d, e, f = self.m
        A, B, C, D, E, F = o.m
        return T(a * A + c * B, b * A + d * B, a * C + c * D, b * C + d * D, a * E + c * F + e, b * E + d * F + f)

    def pt(self, x, y):
        a, b, c, d, e, f = self.m
        return a * x + c * y + e, b * x + d * y + f

    @property
    def scale(self):
        return math.hypot(self.m[0], self.m[1])

    @property
    def angle(self):
        return math.degrees(math.atan2(self.m[1], self.m[0]))


def translate(x, y):
    return T(e=x, f=y)


def scale(s):
    return T(a=s, d=s)


def rotate(deg, cx=0.0, cy=0.0):
    r = math.radians(deg)
    return translate(cx, cy) @ T(math.cos(r), math.sin(r), -math.sin(r), math.cos(r)) @ translate(-cx, -cy)


def n(v):
    s = f'{v:.2f}'.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


_TOK = re.compile(r'[MmLlHhVvCcQqAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
_ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'Q': 4, 'A': 7, 'Z': 0}


def bake(d, t):
    """把 SVG 路径（可含相对命令、H/V、弧）在变换 t 下改写成绝对坐标路径。"""
    toks = _TOK.findall(d)
    out, i, cmd = [], 0, None
    cx = cy = sx = sy = 0.0
    P = lambda x, y: ','.join(n(v) for v in t.pt(x, y))
    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
        up, rel = cmd.upper(), cmd.islower()
        k = _ARGS[up]
        a = [float(v) for v in toks[i:i + k]]
        i += k
        if up == 'Z':
            out.append('Z')
            cx, cy = sx, sy
            continue
        if up == 'H':
            x, y = (cx + a[0] if rel else a[0]), cy
            out.append('L' + P(x, y))
        elif up == 'V':
            x, y = cx, (cy + a[0] if rel else a[0])
            out.append('L' + P(x, y))
        elif up in 'ML':
            x, y = (cx + a[0], cy + a[1]) if rel else (a[0], a[1])
            out.append(up + P(x, y))
            if up == 'M':
                sx, sy = x, y
                cmd = 'l' if rel else 'L'
        elif up == 'C':
            pts = [(a[j] + (cx if rel else 0), a[j + 1] + (cy if rel else 0)) for j in (0, 2, 4)]
            x, y = pts[-1]
            out.append('C' + ' '.join(P(*p) for p in pts))
        elif up == 'Q':
            pts = [(a[j] + (cx if rel else 0), a[j + 1] + (cy if rel else 0)) for j in (0, 2)]
            x, y = pts[-1]
            out.append('Q' + ' '.join(P(*p) for p in pts))
        else:  # A：相似变换下仍是圆弧，半径乘缩放，椭圆转角加旋转角，方向标志不变
            x, y = (cx + a[5], cy + a[6]) if rel else (a[5], a[6])
            rot = (a[2] + t.angle) if abs(a[0] - a[1]) > 1e-9 else 0
            out.append(f'A{n(a[0] * t.scale)},{n(a[1] * t.scale)} {n(rot)} {int(a[3])},{int(a[4])} {P(x, y)}')
        cx, cy = x, y
    return ''.join(out)


def rrect(x, y, w, h, r):
    return (f'M{x + r},{y}H{x + w - r}A{r},{r} 0 0 1 {x + w},{y + r}V{y + h - r}A{r},{r} 0 0 1 {x + w - r},{y + h}'
            f'H{x + r}A{r},{r} 0 0 1 {x},{y + h - r}V{y + r}A{r},{r} 0 0 1 {x + r},{y}Z')


def glass(t, x1, y1, x2, y2, *alphas):
    (X1, Y1), (X2, Y2) = t.pt(x1, y1), t.pt(x2, y2)
    k = len(alphas) - 1
    return ('linear', X1, Y1, X2, Y2, [(i / k, W, al) for i, al in enumerate(alphas)])


# ---------------------------------------------------------------- 设计稿（work/baize-icon-vector/baize-v2-C.svg）
PLACE = translate(54, 54) @ scale(0.87) @ translate(-53, -56)   # 主体等比缩到 66dp 安全圆内（设计稿 0.88，单色描边留余量）
CARD = PLACE @ rotate(-9, 38, 52)                                  # 文档卡倾斜 -9°
CLOUD_T = PLACE @ translate(27, 78) @ scale(0.7)
S = PLACE.scale

CARD_RECT = rrect(23, 33, 30, 36, 4)
CARD_SHADOW = rrect(24, 35, 30, 36, 4)
CARD_LINES = 'M29,42h12M29,48h9M29,54h11M29,60h7'
RING = ('M63,42.5a16.5,16.5 0 1 1 0,33a16.5,16.5 0 1 1 0,-33z'
        'M63,50.5a8.5,8.5 0 1 0 0,17a8.5,8.5 0 1 0 0,-17z')
RING_SHADOW = ('M64,44.1a16.5,16.5 0 1 1 0,33a16.5,16.5 0 1 1 0,-33z'
               'M64,52.1a8.5,8.5 0 1 0 0,17a8.5,8.5 0 1 0 0,-17z')
RING_GLINT = 'M49.5,53A13.5,13.5 0 0 1 59,45.5'
RING_ARC = 'M67.28,47.25A12.5,12.5 0 0 1 72.58,67.03'      # 存储环上 20°→130° 的「已清理」进度弧
SPARK_BIG = 'M78,35Q78.8,38.2 82,39Q78.8,39.8 78,43Q77.2,39.8 74,39Q77.2,38.2 78,35Z'
SPARK_SMALL = 'M83.5,44.1Q83.88,45.62 85.4,46Q83.88,46.38 83.5,47.9Q83.12,46.38 81.6,46Q83.12,45.62 83.5,44.1Z'
CLOUD = 'M0,0h10a2.6,2.6 0 0 0 0,-5.2a3.7,3.7 0 0 0 -6.8,-1.3a2.8,2.8 0 0 0 -3.2,3.4a1.6,1.6 0 0 0 0,3.1z'
# 单色版：文档卡在存储环外 2 单位处断开（外框顺时针 + 圆逆时针，非零环绕即镂空，VectorDrawable clip-path 可用）
KNOCK = 'M-50,-50H160V160H-50ZM63,40.5a18.5,18.5 0 1 0 0,37a18.5,18.5 0 1 0 0,-37z'

# ---- 背景：浅青 → 青 斜向渐变，左上一块柔光 ----
BG_GRADIENT = ('linear', 20, 0, 88, 108, [(0, '#ABF0EA', 1), (0.55, '#84DFDA', 1), (1, '#5BCAC6', 1)])
BG_GLOW = ('radial', 30, 22, 60, [(0, W, 0.4), (1, W, 0)])
BACKGROUND = [
    {'d': 'M0,0h108v108h-108z', 'fill': BG_GRADIENT, 'note': '浅青斜向渐变'},
    {'d': 'M0,0h108v108h-108z', 'fill': BG_GLOW, 'note': '左上柔光'},
]

# ---- 前景：全部落在 66dp 安全圆（中心 54,54，半径 33）内 ----
FOREGROUND = [
    {'d': bake(CARD_SHADOW, CARD), 'fill': ('solid', SHADOW, 0.08), 'note': '文档卡投影'},
    {'d': bake(CARD_RECT, CARD), 'fill': glass(CARD, 23, 33, 53, 69, 0.55, 0.25),
     'stroke': (W, 0.9, 0.9 * S), 'note': '磨砂玻璃文档卡（倾斜 -9°）'},
    {'d': bake(CARD_LINES, CARD), 'stroke': (W, 0.85, 2 * S), 'note': '文档卡文字行'},
    {'d': bake(RING_SHADOW, PLACE), 'fill': ('solid', SHADOW, 0.15), 'evenodd': True, 'note': '存储环投影'},
    {'d': bake(RING, PLACE), 'fill': glass(PLACE, 46, 42, 80, 76, 0.88, 0.7, 0.56), 'evenodd': True,
     'stroke': (W, 0.95, 1 * S), 'note': '磨砂玻璃存储环'},
    {'d': bake(RING_GLINT, PLACE), 'stroke': (W, 0.5, 1.2 * S), 'note': '存储环左上高光'},
    {'d': bake(RING_ARC, PLACE), 'stroke': (LINE, 0.8, 3 * S), 'note': '青色进度弧'},
    {'d': bake(SPARK_BIG, PLACE), 'fill': ('solid', W, 0.95), 'note': '星光'},
    {'d': bake(SPARK_SMALL, PLACE), 'fill': ('solid', W, 0.85)},
    {'d': bake(CLOUD, CLOUD_T), 'fill': ('solid', W, 0.85), 'note': '祥云'},
]

# ---- 单色：同一组图形；文档卡在存储环外断开 ----
MONOCHROME = [
    {'clip': bake(KNOCK, PLACE), 'note': '文档卡在存储环处断开', 'children': [
        {'d': bake(CARD_RECT, CARD), 'fill': ('solid', K, 0.35), 'stroke': (K, 1, 1.4 * S), 'note': '文档卡'},
        {'d': bake(CARD_LINES, CARD), 'stroke': (K, 1, 2 * S)},
    ]},
    {'d': bake(RING, PLACE), 'fill': ('solid', K, 0.35), 'evenodd': True, 'stroke': (K, 1, 1.4 * S), 'note': '存储环'},
    {'d': bake(RING_ARC, PLACE), 'stroke': (K, 1, 3 * S), 'note': '进度弧'},
    {'d': bake(SPARK_BIG, PLACE), 'fill': ('solid', K, 1), 'note': '星光'},
    {'d': bake(SPARK_SMALL, PLACE), 'fill': ('solid', K, 1)},
    {'d': bake(CLOUD, CLOUD_T), 'fill': ('solid', K, 1), 'note': '祥云'},
]

# ---- 启动页：系统把 288dp 图标裁成 192dp 圆（= 108 视口中间 72）。浅色启动底上白玻璃会看不清，
#      所以带上图标背景并裁成半径 35.5 的圆盘，前景与桌面图标逐字相同。 ----
DISC = 'M54,18.5a35.5,35.5 0 1 1 0,71a35.5,35.5 0 1 1 0,-71z'
SPLASH = [{'d': DISC, 'fill': BG_GRADIENT, 'note': '图标背景圆盘'},
          {'d': DISC, 'fill': BG_GLOW}] + FOREGROUND


def _argb(color, alpha=1.0):
    return '#%02X%s' % (round(alpha * 255), color.lstrip('#').upper())


def _flat(layers):
    for s in layers:
        yield from (s.get('children') or [s])


# ---------------------------------------------------------------- SVG
def _svg_paint(fill, gid, defs):
    if fill[0] == 'solid':
        return f'fill="{fill[1]}"' + (f' fill-opacity="{fill[2]:g}"' if fill[2] < 1 else '')
    stops = ''.join(f'<stop offset="{o:g}" stop-color="{c}" stop-opacity="{a:g}"/>' for o, c, a in fill[-1])
    if fill[0] == 'linear':
        x1, y1, x2, y2 = (n(v) for v in fill[1:5])
        defs.append(f'<linearGradient id="{gid}" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
                    f'gradientUnits="userSpaceOnUse">{stops}</linearGradient>')
    else:
        cx, cy, r = (n(v) for v in fill[1:4])
        defs.append(f'<radialGradient id="{gid}" cx="{cx}" cy="{cy}" r="{r}" '
                    f'gradientUnits="userSpaceOnUse">{stops}</radialGradient>')
    return f'fill="url(#{gid})"'


def _svg_path(s, gid, defs):
    attrs = [f'd="{s["d"]}"', _svg_paint(s['fill'], gid, defs) if s.get('fill') else 'fill="none"']
    if s.get('evenodd'):
        attrs.append('fill-rule="evenodd"')
    if s.get('stroke'):
        c, a, w = s['stroke']
        attrs.append(f'stroke="{c}"' + (f' stroke-opacity="{a:g}"' if a < 1 else '')
                     + f' stroke-width="{n(w)}" stroke-linecap="round" stroke-linejoin="round"')
    return '<path ' + ' '.join(attrs) + '/>'


def svg(layers, comment):
    defs, body = [], []
    for i, s in enumerate(layers):
        if s.get('note'):
            body.append(f'  <!-- {s["note"]} -->')
        if 'clip' in s:
            defs.append(f'<clipPath id="c{i}"><path d="{s["clip"]}"/></clipPath>')
            inner = ''.join(_svg_path(c, f'g{i}_{j}', defs) for j, c in enumerate(s['children']))
            body.append(f'  <g clip-path="url(#c{i})">{inner}</g>')
        else:
            body.append('  ' + _svg_path(s, f'g{i}', defs))
    head = ['<?xml version="1.0" encoding="UTF-8"?>', f'<!-- {comment} -->',
            '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">']
    if defs:
        head.append('  <defs>\n    ' + '\n    '.join(defs) + '\n  </defs>')
    return '\n'.join(head + body + ['</svg>']) + '\n'


# ---------------------------------------------------------------- VectorDrawable
def _vd_path(s, pad):
    attrs = [f'android:pathData="{s["d"]}"']
    fill = s.get('fill')
    if fill and fill[0] == 'solid':
        attrs.append(f'android:fillColor="{_argb(fill[1], fill[2])}"')
    if s.get('evenodd'):
        attrs.append('android:fillType="evenOdd"')
    if s.get('stroke'):
        c, a, w = s['stroke']
        attrs += [f'android:strokeWidth="{n(w)}"', f'android:strokeColor="{_argb(c, a)}"',
                  'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
    if fill and fill[0] != 'solid':
        items = '\n'.join(f'{pad}            <item android:offset="{o:g}" android:color="{_argb(c, a)}" />'
                          for o, c, a in fill[-1])
        if fill[0] == 'linear':
            g = (f'android:type="linear" android:startX="{n(fill[1])}" android:startY="{n(fill[2])}" '
                 f'android:endX="{n(fill[3])}" android:endY="{n(fill[4])}"')
        else:
            g = (f'android:type="radial" android:centerX="{n(fill[1])}" android:centerY="{n(fill[2])}" '
                 f'android:gradientRadius="{n(fill[3])}"')
        return (f'{pad}<path ' + f'\n{pad}    '.join(attrs) + f'>\n{pad}    <aapt:attr name="android:fillColor">\n'
                f'{pad}        <gradient {g}>\n{items}\n{pad}        </gradient>\n{pad}    </aapt:attr>\n{pad}</path>')
    return f'{pad}<path ' + f'\n{pad}    '.join(attrs) + ' />'


def vector(layers, comment):
    gradients = any(s.get('fill') and s['fill'][0] != 'solid' for s in _flat(layers))
    out = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {comment} -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
           + ('\n    xmlns:aapt="http://schemas.android.com/aapt"' if gradients else '')
           + '\n    android:width="108dp"\n    android:height="108dp"'
             '\n    android:viewportWidth="108"\n    android:viewportHeight="108">']
    for s in layers:
        if s.get('note'):
            out.append(f'    <!-- {s["note"]} -->')
        if 'clip' in s:
            out.append('    <group>')
            out.append(f'        <clip-path android:pathData="{s["clip"]}" />')
            for c in s['children']:
                if c.get('note'):
                    out.append(f'        <!-- {c["note"]} -->')
                out.append(_vd_path(c, '        '))
            out.append('    </group>')
        else:
            out.append(_vd_path(s, '    '))
    return '\n'.join(out + ['</vector>']) + '\n'


GEN = '由 tools/icon/baize_icon.py 生成，勿手改。'
OUTPUTS = {
    ROOT / 'tools/icon/baize-icon.svg': lambda: svg(
        BACKGROUND + FOREGROUND,
        '白泽 3.0.1 图标 SVG 源：与 res/drawable/ic_baize_{background,foreground}.xml 同一套几何（108×108 视口，无 transform）。'
        + GEN + '白泽原创手写矢量。'),
    ROOT / 'tools/icon/baize-icon-monochrome.svg': lambda: svg(
        MONOCHROME, '白泽 3.0.1 主题图标 SVG：与 res/drawable/ic_baize_monochrome.xml 同一套几何。' + GEN),
    RES / 'drawable/ic_baize_background.xml': lambda: vector(
        BACKGROUND, '白泽 3.0.1 图标背景：浅青斜向渐变 + 左上柔光。' + GEN),
    RES / 'drawable/ic_baize_foreground.xml': lambda: vector(
        FOREGROUND, '白泽 3.0.1 图标前景：磨砂玻璃文档卡 + 玻璃存储环（青色进度弧）+ 星光 + 祥云，'
                    '全部在 66dp 安全圆内。' + GEN),
    RES / 'drawable/ic_baize_monochrome.xml': lambda: vector(
        MONOCHROME, '白泽 3.0.1 主题图标（Android 13 起随壁纸着色）：前景同一组图形的单色版。' + GEN),
    RES / 'drawable/ic_baize_splash_mark.xml': lambda: vector(
        SPLASH, '白泽 3.0.1 启动页图标：图标背景裁成半径 35.5 的圆盘（落在系统 192dp 可见圆内）+ 与桌面图标相同的前景。'
                + GEN),
}


def main(argv):
    stale = []
    for path, build in OUTPUTS.items():
        text = build()
        if '--check' in argv:
            if not path.is_file() or path.read_text(encoding='utf-8') != text:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.write_text(text, encoding='utf-8')
            print('wrote', path.relative_to(ROOT))
    if stale:
        print('与 tools/icon/baize_icon.py 不一致：' + ', '.join(stale))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
