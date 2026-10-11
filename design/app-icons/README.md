# BaiZe App Icon

Official icon for BaiZe 3.0.1, 「白泽晶」: a hand-authored **vector** frosted-glass hexagon gem with an inner
engraved frame, a lit top facet, a white 白泽 horn and a sparkle, on a deep 白泽青 gradient
(#0E8F94 → #0A7E84 → #006B72) with a soft top-left glow. Original artwork; no third-party assets.

- Single geometry source: `tools/icon/baize_icon.py` (all translations/scales are baked into absolute paths,
  gradient endpoints and stroke widths included, so neither the SVG nor the VectorDrawables contain transforms) generates
  - `tools/icon/baize-icon.svg` (108×108 viewport, background + foreground)
  - `tools/icon/baize-icon-monochrome.svg` (themed icon, same geometry as the monochrome vector)
  - `v2/app/src/main/res/drawable/ic_baize_background.xml` (vector gradient + soft glow)
  - `v2/app/src/main/res/drawable/ic_baize_foreground.xml` (vector mark, inside the 66dp safe circle)
  - `v2/app/src/main/res/drawable/ic_baize_monochrome.xml` (Android 13 themed icon)
  `python3 tools/icon/baize_icon.py --check` verifies the committed files match (run by `v2/tests/test-module-layout.py`).
- Legacy bitmaps rendered from the SVG by `tools/icon/render_legacy.py` (needs CairoSVG + Pillow):
  - `v2/app/src/main/res/mipmap-*/ic_baize.webp` (rounded square, five densities)
  - Stable path: `design/app-icons/official-icon.webp` (192×192 = xxxhdpi), identical to `branding/baize-app-icon.webp`
  - Round export: `design/app-icons/official-icon-round.webp` (reference only; minSdk 26 always resolves the
    adaptive `@mipmap/ic_baize`, which the launcher masks itself)
- Android launcher resource: `@mipmap/ic_baize`
- Splash: `drawable/ic_baize_splash.xml` insets the launcher foreground `ic_baize_foreground` (no bitmap, no copy).
  Background `#006B72` (the icon gradient's deep end) in both light and dark mode; branding image is white text.

`baize-emerald-30010.webp` is the previous (30010–3.0.0-pre) emerald icon; `baize-spirit-beast.webp`
is the one before it. The 3.0.0 raster cut-out icon was replaced by this vector in 3.0.1.
