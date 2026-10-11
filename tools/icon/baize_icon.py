#!/usr/bin/env python3
"""白泽 3.0.1 应用图标（矢量版）的唯一几何来源。

一份图形定义同时生成：
  tools/icon/baize-icon.svg                              SVG 源（108×108 视口，背景 + 前景）
  tools/icon/baize-icon-monochrome.svg                   主题图标 SVG
  v2/app/src/main/res/drawable/ic_baize_background.xml   自适应图标背景（白泽青渐变 + 左上柔光）
  v2/app/src/main/res/drawable/ic_baize_foreground.xml   自适应图标前景（白泽晶：磨砂六棱晶石 + 内框 + 白泽角 + 星光）
  v2/app/src/main/res/drawable/ic_baize_monochrome.xml   Android 13 主题图标（单色）
启动页 drawable/ic_baize_splash.xml 直接 inset 前景矢量，不另存图形。
设计稿里的平移、缩放全部在这里预先烘焙进绝对坐标路径（渐变端点、描边宽度同步变换），
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
SHADOW = '#003C40'
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


# ---------------------------------------------------------------- 设计稿（方案 4「白泽晶」：work/baize-icon-vector/baize-v3-4.svg）
PLACE = translate(54, 54) @ scale(0.93) @ translate(-54, -54)   # 主体以画布中心等比缩到 0.93，右上星光也落进 66dp 安全圆
S = PLACE.scale

GEM = 'M54,29.5L76.08,42.25L76.08,67.75L54,80.5L31.92,67.75L31.92,42.25Z'          # 六棱晶石（顺时针）
FRAME = 'M54,34.5L71.75,44.75L71.75,65.25L54,75.5L36.25,65.25L36.25,44.75Z'        # 内刻框
FACET = 'M31.92,42.25L54,29.5L76.08,42.25L71.75,44.75L54,34.5L36.25,44.75Z'        # 顶部两面受光
HORN = 'M50.2,67C48.6,57.5 51.8,48.2 61.8,41.2C56.4,48.4 54.6,57 55.6,67Z'         # 白泽角
SPARK = 'M78.5,29.7Q79.26,32.74 82.3,33.5Q79.26,34.26 78.5,37.3Q77.74,34.26 74.7,33.5Q77.74,32.74 78.5,29.7Z'
# 投影 = 晶石右下平移 (1.2, 2) 后减去晶石本身，只剩外露的月牙边；预先求好多边形，不用 clip-path
GEM_SHADOW = 'M33.12,69.75L55.2,82.5L77.28,69.75L77.28,44.25L76.08,43.56L76.08,67.75L54,80.5L33.12,68.44Z'

# ---- 背景：白泽青 斜向渐变，左上一块柔光 ----
BG_GRADIENT = ('linear', 20, 0, 88, 108, [(0, '#0E8F94', 1), (0.55, '#0A7E84', 1), (1, '#006B72', 1)])
BG_GLOW = ('radial', 30, 22, 62, [(0, W, 0.22), (1, W, 0)])
BACKGROUND = [
    {'d': 'M0,0h108v108h-108z', 'fill': BG_GRADIENT, 'note': '白泽青斜向渐变'},
    {'d': 'M0,0h108v108h-108z', 'fill': BG_GLOW, 'note': '左上柔光'},
]


def glass(x1, y1, x2, y2, stops):
    (X1, Y1), (X2, Y2) = PLACE.pt(x1, y1), PLACE.pt(x2, y2)
    return ('linear', X1, Y1, X2, Y2, [(o, W, al) for o, al in stops])


# ---- 前景：全部落在 66dp 安全圆（中心 54,54，半径 33）内 ----
FOREGROUND = [
    {'d': bake(GEM_SHADOW, PLACE), 'fill': ('solid', SHADOW, 0.28), 'note': '晶石投影（只露在晶石右下外沿）'},
    {'d': bake(GEM, PLACE), 'fill': glass(28.5, 29.5, 79.5, 80.5, [(0, 0.46), (0.5, 0.3), (1, 0.2)]),
     'stroke': (W, 0.9, 1.0 * S), 'note': '磨砂玻璃六棱晶石'},
    {'d': bake(FRAME, PLACE), 'stroke': (W, 0.39, 0.7 * S), 'note': '内刻框'},
    {'d': bake(FACET, PLACE), 'fill': ('solid', W, 0.16), 'note': '顶部受光面'},
    {'d': bake(HORN, PLACE), 'fill': ('solid', W, 0.95), 'note': '白泽角'},
    {'d': bake(SPARK, PLACE), 'fill': ('solid', W, 0.95), 'note': '星光'},
]

# ---- 单色：同一组图形 ----
MONOCHROME = [
    {'d': bake(GEM, PLACE), 'fill': ('solid', K, 0.35), 'stroke': (K, 1, 1.4 * S), 'note': '晶石'},
    {'d': bake(FRAME, PLACE), 'stroke': (K, 1, 0.9 * S), 'note': '内刻框'},
    {'d': bake(HORN, PLACE), 'fill': ('solid', K, 1), 'note': '白泽角'},
    {'d': bake(SPARK, PLACE), 'fill': ('solid', K, 1), 'note': '星光'},
]


# ---- 启动页（弥散渐变玻璃）----
# 启动图标：系统把 288dp 图标裁成 192dp 圆（= 108 视口中间的 72，半径 36）。前景后面垫一圈玻璃光晕：
# 柔光径向渐变（到半径 36 处完全透明）+ 一枚极淡的磨砂玻璃圆片，全部在可见圆内。
HALO_DISC = 'M54,20.5a33.5,33.5 0 1 1 0,67a33.5,33.5 0 1 1 0,-67z'
SPLASH_HALO = [
    {'d': 'M54,18a36,36 0 1 1 0,72a36,36 0 1 1 0,-72z',
     'fill': ('radial', 54, 54, 36, [(0, W, 0.16), (0.5, W, 0.09), (0.8, W, 0.03), (1, W, 0)]), 'note': '光晕'},
    {'d': HALO_DISC, 'fill': ('linear', 30, 30, 78, 78, [(0, W, 0.09), (0.6, W, 0.03), (1, W, 0.015)]),
     'stroke': (W, 0.13, 0.45), 'note': '磨砂玻璃圆片'},
]
SPLASH = SPLASH_HALO + FOREGROUND

# 启动底：360×800dp 视口（108×240），白泽青底色上叠几团大而虚的色斑，边缘全部渐隐到 0，没有硬边。
MESH_W, MESH_H = 108, 240
FULL = f'M0,0h{MESH_W}v{MESH_H}h-{MESH_W}z'


def blob(cx, cy, r, color, alpha):
    # 近似高斯衰减的径向渐变
    return ('radial', cx, cy, r, [(o, color, alpha * k) for o, k in
                                  ((0, 1), (0.25, 0.86), (0.5, 0.52), (0.75, 0.18), (1, 0))])


MESH = [
    {'d': FULL, 'fill': ('linear', 0, 0, 108, 240, [(0, '#0E8F94', 1), (0.5, '#0A7880', 1), (1, '#005A62', 1)]),
     'note': '白泽青底色'},
    {'d': FULL, 'fill': blob(16, 44, 78, '#5CCBC6', 0.5), 'note': '左上浅青色斑'},
    {'d': FULL, 'fill': blob(108, 168, 78, '#2E98C4', 0.38), 'note': '右下一抹湖蓝'},
    {'d': FULL, 'fill': blob(4, 240, 80, '#004750', 0.55), 'note': '左下沉一点深青'},
    {'d': FULL, 'fill': blob(54, 120, 50, '#8FE3DC', 0.14), 'note': '中心托住晶石的微光'},
]


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


def svg(layers, comment, w=108, h=108):
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
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">']
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


def vector(layers, comment, w=108, h=108, dp=None):
    gradients = any(s.get('fill') and s['fill'][0] != 'solid' for s in _flat(layers))
    out = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {comment} -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
           + ('\n    xmlns:aapt="http://schemas.android.com/aapt"' if gradients else '')
           + f'\n    android:width="{(dp or (w, h))[0]}dp"\n    android:height="{(dp or (w, h))[1]}dp"'
             f'\n    android:viewportWidth="{w}"\n    android:viewportHeight="{h}">']
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
        BACKGROUND, '白泽 3.0.1 图标背景：白泽青斜向渐变（#0E8F94→#006B72）+ 左上柔光。' + GEN),
    RES / 'drawable/ic_baize_foreground.xml': lambda: vector(
        FOREGROUND, '白泽 3.0.1 图标前景「白泽晶」：磨砂玻璃六棱晶石 + 内刻框 + 白泽角 + 星光，'
                    '全部在 66dp 安全圆内；启动页 ic_baize_splash.xml 也直接用它。' + GEN),
    RES / 'drawable/ic_baize_monochrome.xml': lambda: vector(
        MONOCHROME, '白泽 3.0.1 主题图标（Android 13 起随壁纸着色）：前景同一组图形的单色版。' + GEN),
    RES / 'drawable/ic_baize_splash_mark.xml': lambda: vector(
        SPLASH, '白泽 3.0.1 启动页图标：玻璃光晕（落在系统 192dp 可见圆内）+ 与桌面图标逐字相同的前景。' + GEN),
    RES / 'drawable/baize_splash_mesh.xml': lambda: vector(
        MESH, '白泽 3.0.1 启动底「弥散渐变」：白泽青底色 + 三团柔和色斑，按窗口拉伸。' + GEN, MESH_W, MESH_H, (360, 800)),
    ROOT / 'tools/icon/baize-splash-mesh.svg': lambda: svg(
        MESH, '白泽 3.0.1 启动底 SVG：与 res/drawable/baize_splash_mesh.xml 同一套几何。' + GEN, MESH_W, MESH_H),
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
