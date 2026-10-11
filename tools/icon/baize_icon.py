#!/usr/bin/env python3
"""白泽 3.0.1 应用图标（矢量版）的唯一几何来源。

一份图形定义同时生成：
  tools/icon/baize-icon.svg                         SVG 源（108×108 视口，背景 + 前景）
  v2/app/src/main/res/drawable/ic_baize_background.xml   自适应图标背景（矢量）
  v2/app/src/main/res/drawable/ic_baize_foreground.xml   自适应图标前景（矢量，磨砂白玻璃白泽）
  v2/app/src/main/res/drawable/ic_baize_monochrome.xml   Android 13 主题图标（单色剪影）
因此 SVG 与 Android 矢量的几何逐字一致。图形为白泽原创手写矢量，未引用任何第三方素材。

用法：
  python3 tools/icon/baize_icon.py           重新生成上述文件
  python3 tools/icon/baize_icon.py --check   只校验已提交文件与本定义一致（纯 Python，无依赖）
旧式 mipmap / 品牌位图由 tools/icon/render_legacy.py 从 SVG 渲染。
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'v2/app/src/main/res'
W = '#FFFFFF'
SHADOW = '#0B5F66'
INK = '#0F7F86'
LINE = '#1A9CA2'
K = ('solid', '#000000', 1)


def glass(x1, y1, x2, y2, *alphas):
    n = len(alphas) - 1
    return ('linear', x1, y1, x2, y2, [(i / n, W, a) for i, a in enumerate(alphas)])


# ---- 背景：薄青 → 青 → 深青斜向渐变（与白泽青 #006B72 同一色相），左上一块柔光 ----
BACKGROUND = [
    {'d': 'M0,0h108v108h-108z', 'note': '青色斜向渐变',
     'fill': ('linear', 22, 0, 86, 108, [(0, '#7FE3E0', 1), (0.55, '#2BB5B8', 1), (1, '#0F8F96', 1)])},
    {'d': 'M0,0h108v108h-108z', 'note': '左上柔光',
     'fill': ('radial', 30, 22, 62, [(0, W, 0.42), (1, W, 0)])},
]

# ---- 前景：侧首白泽，独角、三缕卷云鬃、卷须、颈侧祥云纹；全部落在 66dp 安全圆（中心 54,54，半径 33）内 ----
HEAD_BACK = 'M45,83.5C43,75 44,66 48,58C51,50 54,44 58,40'
HEAD = (HEAD_BACK + 'C62,36 68,35 72,37.5C75,39.5 76.5,42 77,44.5C79.5,45.5 82,47 84,49'
        'C86.5,51.5 86.5,55.5 84,56.5C82.5,57 81,57 80,57.5C81.5,60.5 80,64.5 76.5,65.5'
        'C73,66.5 70,66.5 67.5,68.5C65,71 64.5,75 65.5,83C60,85 50,85 45,83.5Z')
MANE_EDGE = ('M58,40C50,30 38,27 32,33C28,37 31,43 36,41C39,40 41,42 42,45C34,44 27,49 26,56'
             'C25,62 31,65 34,61C36,59 39,60 41,63C35,66 31.5,71.5 33.5,77C35,81 39.5,82.5 45,83.5')
MANE = MANE_EDGE + 'C43,75 44,66 48,58C51,50 54,44 58,40Z'
HORN = 'M62.6,37.6C61.5,31 63.5,27 68,24.5C66.5,28.5 66.6,32.5 69,36.8C67,36 64.5,36.4 62.6,37.6Z'
BEARD = ('M76.5,65.5C73,66.5 70,66.5 67.5,68.5C69,71 71,72.5 73,73C71,74.5 71.5,77 74,76.5'
         'C77.5,75.5 79.5,71 78.5,66.5C78,66 77.3,65.7 76.5,65.5Z')
EYE = 'M69.5,46.5C71.5,44.5 74.5,43.8 77,44.5C75.5,46.5 72.5,47.5 69.5,46.5Z'
BROW = 'M67,42.5C70,40 74,40.5 77,43'
SWIRL = 'M50,77C46,70 49,62 56,62C61,62 62,68 58,69.5C55.5,70.2 54,67.8 55.6,66.6'

FOREGROUND = [
    {'d': MANE, 'fill': glass(28, 30, 48, 85, 0.66, 0.28), 'note': '鬃：三缕卷云，较淡的后层磨砂玻璃'},
    {'d': MANE_EDGE, 'stroke': (W, 0.85, 0.8), 'note': '鬃外缘高光'},
    {'d': HORN, 'fill': glass(62, 25, 68, 38, 1, 0.85), 'note': '独角（根部压在头部轮廓之下）'},
    {'d': BEARD, 'fill': glass(70, 66, 78, 77, 0.9, 0.6), 'note': '卷须'},
    {'d': BEARD, 'stroke': (W, 0.8, 0.8)},
    {'d': HEAD, 'fill': ('solid', SHADOW, 0.16), 'tx': (1, 1.5), 'note': '头颈柔和投影'},
    {'d': HEAD, 'fill': glass(46, 36, 80, 86, 0.94, 0.72, 0.5), 'note': '头与颈：主磨砂玻璃'},
    {'d': HEAD, 'stroke': (W, 0.95, 1), 'note': '头颈外缘高光'},
    {'d': EYE, 'fill': ('solid', INK, 0.9), 'note': '眼'},
    {'d': BROW, 'stroke': (LINE, 0.45, 1.1), 'note': '眉'},
    {'d': SWIRL, 'stroke': (LINE, 0.5, 1.6), 'note': '颈侧祥云纹'},
]

# ---- 单色：同一组图形的剪影；鬃与头之间留一道缝，眼睛镂空 ----
MANE_MONO = ('M55.6,37.8C49,30 38,27 32,33C28,37 31,43 36,41C39,40 41,42 42,45C34,44 27,49 26,56'
             'C25,62 31,65 34,61C36,59 39,60 41,63C35,66 31.5,71.5 33.5,77C35,81 39,82.3 42.6,83'
             'C40.8,75 41.8,65.5 46,57C49,49 52,43 55.6,37.8Z')
MONOCHROME = [
    {'d': MANE_MONO, 'fill': K, 'note': '鬃'},
    {'d': HEAD + EYE, 'fill': K, 'evenodd': True, 'note': '头颈（眼睛镂空）'},
    {'d': HORN, 'fill': K, 'note': '独角'},
    {'d': BEARD, 'fill': K, 'note': '卷须'},
]


def _argb(color, alpha=1.0):
    return '#%02X%s' % (round(alpha * 255), color.lstrip('#').upper())


def _svg_paint(fill, gid, defs):
    if fill[0] == 'solid':
        return f'fill="{fill[1]}"' + (f' fill-opacity="{fill[2]:g}"' if fill[2] < 1 else '')
    stops = ''.join(f'<stop offset="{o:g}" stop-color="{c}" stop-opacity="{a:g}"/>' for o, c, a in fill[-1])
    if fill[0] == 'linear':
        x1, y1, x2, y2 = fill[1:5]
        defs.append(f'<linearGradient id="{gid}" x1="{x1:g}" y1="{y1:g}" x2="{x2:g}" y2="{y2:g}" '
                    f'gradientUnits="userSpaceOnUse">{stops}</linearGradient>')
    else:
        cx, cy, r = fill[1:4]
        defs.append(f'<radialGradient id="{gid}" cx="{cx:g}" cy="{cy:g}" r="{r:g}" '
                    f'gradientUnits="userSpaceOnUse">{stops}</radialGradient>')
    return f'fill="url(#{gid})"'


def svg(layers, comment):
    defs, body = [], []
    for i, s in enumerate(layers):
        attrs = [f'd="{s["d"]}"', _svg_paint(s['fill'], f'g{i}', defs) if s.get('fill') else 'fill="none"']
        if s.get('evenodd'):
            attrs.append('fill-rule="evenodd"')
        if s.get('stroke'):
            c, a, w = s['stroke']
            attrs.append(f'stroke="{c}" stroke-opacity="{a:g}" stroke-width="{w:g}" '
                         'stroke-linecap="round" stroke-linejoin="round"')
        path = '<path ' + ' '.join(attrs) + '/>'
        if s.get('tx'):
            path = f'<g transform="translate({s["tx"][0]:g} {s["tx"][1]:g})">{path}</g>'
        if s.get('note'):
            body.append(f'  <!-- {s["note"]} -->')
        body.append('  ' + path)
    head = ['<?xml version="1.0" encoding="UTF-8"?>', f'<!-- {comment} -->',
            '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">']
    if defs:
        head.append('  <defs>\n    ' + '\n    '.join(defs) + '\n  </defs>')
    return '\n'.join(head + body + ['</svg>']) + '\n'


def vector(layers, comment):
    gradients = any(s.get('fill') and s['fill'][0] != 'solid' for s in layers)
    out = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {comment} -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
           + ('\n    xmlns:aapt="http://schemas.android.com/aapt"' if gradients else '')
           + '\n    android:width="108dp"\n    android:height="108dp"'
             '\n    android:viewportWidth="108"\n    android:viewportHeight="108">']
    for s in layers:
        attrs = [f'android:pathData="{s["d"]}"']
        fill = s.get('fill')
        if fill and fill[0] == 'solid':
            attrs.append(f'android:fillColor="{_argb(fill[1], fill[2])}"')
        if s.get('evenodd'):
            attrs.append('android:fillType="evenOdd"')
        if s.get('stroke'):
            c, a, w = s['stroke']
            attrs += [f'android:strokeWidth="{w:g}"', f'android:strokeColor="{_argb(c, a)}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        if fill and fill[0] != 'solid':
            items = '\n'.join(f'                <item android:offset="{o:g}" android:color="{_argb(c, a)}" />'
                              for o, c, a in fill[-1])
            if fill[0] == 'linear':
                g = (f'android:type="linear" android:startX="{fill[1]:g}" android:startY="{fill[2]:g}" '
                     f'android:endX="{fill[3]:g}" android:endY="{fill[4]:g}"')
            else:
                g = (f'android:type="radial" android:centerX="{fill[1]:g}" android:centerY="{fill[2]:g}" '
                     f'android:gradientRadius="{fill[3]:g}"')
            path = ('    <path ' + '\n        '.join(attrs) + '>\n        <aapt:attr name="android:fillColor">\n'
                    f'            <gradient {g}>\n{items}\n            </gradient>\n        </aapt:attr>\n    </path>')
        else:
            path = '    <path ' + '\n        '.join(attrs) + ' />'
        if s.get('tx'):
            path = (f'    <group android:translateX="{s["tx"][0]:g}" android:translateY="{s["tx"][1]:g}">\n'
                    + '\n'.join('    ' + line for line in path.split('\n')) + '\n    </group>')
        if s.get('note'):
            out.append(f'    <!-- {s["note"]} -->')
        out.append(path)
    return '\n'.join(out + ['</vector>']) + '\n'


OUTPUTS = {
    ROOT / 'tools/icon/baize-icon.svg': lambda: svg(
        BACKGROUND + FOREGROUND,
        '白泽 3.0.1 图标 SVG 源：与 res/drawable/ic_baize_{background,foreground}.xml 同一套几何（108×108 视口）。'
        '由 tools/icon/baize_icon.py 生成；白泽原创手写矢量。'),
    RES / 'drawable/ic_baize_background.xml': lambda: vector(
        BACKGROUND, '白泽 3.0.1 图标背景：薄青到深青的斜向渐变 + 左上柔光。由 tools/icon/baize_icon.py 生成，勿手改。'),
    RES / 'drawable/ic_baize_foreground.xml': lambda: vector(
        FOREGROUND, '白泽 3.0.1 图标前景：磨砂白玻璃侧首白泽（独角、卷云鬃、卷须），全部在 66dp 安全圆内。'
                    '同一矢量也用作启动页图标。由 tools/icon/baize_icon.py 生成，勿手改。'),
    RES / 'drawable/ic_baize_monochrome.xml': lambda: vector(
        MONOCHROME, '白泽 3.0.1 主题图标（Android 13 起随壁纸着色）：前景同一组图形的单色剪影。'
                    '由 tools/icon/baize_icon.py 生成，勿手改。'),
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
