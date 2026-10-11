#!/usr/bin/env python3
"""把 baize-icon.svg 渲染成旧式（非自适应）图标与品牌位图。

自适应图标的 108dp 画布里只有中间 72dp 可见，所以先裁出中间 2/3，再按密度缩放，套圆角方形遮罩：
  v2/app/src/main/res/mipmap-*/ic_baize.webp      五档密度（无损 WebP）
  design/app-icons/official-icon.webp             = xxxhdpi（192px），APK 打包校验用的正式图标
  branding/baize-app-icon.webp                    = 同上
  design/app-icons/official-icon-round.webp       圆形导出（仅参考）
需要 CairoSVG 与 Pillow（只在本地重绘时需要，构建不依赖）。用法：python3 tools/icon/render_legacy.py
"""
import io
import os
import cairosvg
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..')
RES = os.path.join(ROOT, 'v2', 'app', 'src', 'main', 'res')
SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def render(px):
    full = int(round(px * 108 / 72))
    png = cairosvg.svg2png(url=os.path.join(HERE, 'baize-icon.svg'), output_width=full, output_height=full)
    im = Image.open(io.BytesIO(png)).convert('RGBA')
    off = (full - px) // 2
    return im.crop((off, off, off + px, off + px))


def masked(im, round_):
    s = 4
    big = Image.new('L', (im.width * s, im.height * s), 0)
    d = ImageDraw.Draw(big)
    if round_:
        d.ellipse((0, 0, big.width - 1, big.height - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius=int(big.width * 0.22), fill=255)
    mask = big.resize(im.size, Image.LANCZOS)
    out = Image.new('RGBA', im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, 'WEBP', lossless=True, quality=100, method=6)
    print(os.path.relpath(path, ROOT))


for name, px in SIZES.items():
    save(masked(render(px), False), os.path.join(RES, 'mipmap-' + name, 'ic_baize.webp'))
official = masked(render(192), False)
save(official, os.path.join(ROOT, 'design', 'app-icons', 'official-icon.webp'))
save(official, os.path.join(ROOT, 'branding', 'baize-app-icon.webp'))
save(masked(render(192), True), os.path.join(ROOT, 'design', 'app-icons', 'official-icon-round.webp'))
