# BaiZe App Icon

Official icon for BaiZe 3.0.1: a hand-authored **vector** Bai Ze — head in profile with a single horn,
a three-lock cloud mane, curled beard and a cloud swirl on the neck — built from frosted white glass
shapes on a fresh teal gradient (#7FE3E0 → #2BB5B8 → #0F8F96, same hue family as the 白泽青 accent #006B72).
Original artwork; no third-party assets.

- Single geometry source: `tools/icon/baize_icon.py` generates
  - `tools/icon/baize-icon.svg` (108×108 viewport, background + foreground)
  - `v2/app/src/main/res/drawable/ic_baize_background.xml` (vector gradient + soft glow)
  - `v2/app/src/main/res/drawable/ic_baize_foreground.xml` (vector mark, inside the 66dp safe circle)
  - `v2/app/src/main/res/drawable/ic_baize_monochrome.xml` (Android 13 themed icon silhouette)
  `python3 tools/icon/baize_icon.py --check` verifies the committed files match (run by `v2/tests/test-module-layout.py`).
- Legacy bitmaps rendered from the SVG by `tools/icon/render_legacy.py` (needs CairoSVG + Pillow):
  - `v2/app/src/main/res/mipmap-*/ic_baize.webp` (rounded square, five densities)
  - Stable path: `design/app-icons/official-icon.webp` (192×192 = xxxhdpi), identical to `branding/baize-app-icon.webp`
  - Round export: `design/app-icons/official-icon-round.webp` (reference only; minSdk 26 always resolves the
    adaptive `@mipmap/ic_baize`, which the launcher masks itself)
- Android launcher resource: `@mipmap/ic_baize`
- Splash icon: `drawable/ic_baize_splash.xml` reuses the same foreground vector (no bitmap).

`baize-emerald-30010.webp` is the previous (30010–3.0.0-pre) emerald icon; `baize-spirit-beast.webp`
is the one before it. The 3.0.0 raster cut-out icon was replaced by this vector in 3.0.1.
