# BaiZe App Icon

Official icon for BaiZe 3.0.1: a hand-authored **vector** frosted-glass mark — a tilted glass document card
behind a glass storage ring with a teal "cleaned" progress arc, two sparkles and a small cloud — on a light
cyan gradient (#ABF0EA → #84DFDA → #5BCAC6) with a soft top-left glow. Original artwork; no third-party assets.

- Single geometry source: `tools/icon/baize_icon.py` (all rotations/scales are baked into absolute paths,
  gradient endpoints and stroke widths included, so neither the SVG nor the VectorDrawables contain transforms) generates
  - `tools/icon/baize-icon.svg` (108×108 viewport, background + foreground)
  - `tools/icon/baize-icon-monochrome.svg` (themed icon, same geometry as the monochrome vector)
  - `v2/app/src/main/res/drawable/ic_baize_background.xml` (vector gradient + soft glow)
  - `v2/app/src/main/res/drawable/ic_baize_foreground.xml` (vector mark, inside the 66dp safe circle)
  - `v2/app/src/main/res/drawable/ic_baize_monochrome.xml` (Android 13 themed icon)
  - `v2/app/src/main/res/drawable/ic_baize_splash_mark.xml` (splash icon: background clipped to a r=35.5 disc + the same foreground)
  `python3 tools/icon/baize_icon.py --check` verifies the committed files match (run by `v2/tests/test-module-layout.py`).
- Legacy bitmaps rendered from the SVG by `tools/icon/render_legacy.py` (needs CairoSVG + Pillow):
  - `v2/app/src/main/res/mipmap-*/ic_baize.webp` (rounded square, five densities)
  - Stable path: `design/app-icons/official-icon.webp` (192×192 = xxxhdpi), identical to `branding/baize-app-icon.webp`
  - Round export: `design/app-icons/official-icon-round.webp` (reference only; minSdk 26 always resolves the
    adaptive `@mipmap/ic_baize`, which the launcher masks itself)
- Android launcher resource: `@mipmap/ic_baize`
- Splash: `drawable/ic_baize_splash.xml` insets `ic_baize_splash_mark` (no bitmap). Background `#E6F7F6`
  (light) / `#0A4F55` (night); branding image is dark-teal text in `drawable-*dpi/` and white text in `drawable-night-*dpi/`.

`baize-emerald-30010.webp` is the previous (30010–3.0.0-pre) emerald icon; `baize-spirit-beast.webp`
is the one before it. The 3.0.0 raster cut-out icon was replaced by this vector in 3.0.1.
