# BaiZe App Icon

Official icon for BaiZe 3.0.0: frosted white-glass Bai Ze head on a deep teal gradient
(#0A5A60 → #06404A).

- Stable path: `design/app-icons/official-icon.webp` (192×192 legacy launcher export, rounded square)
- Round legacy export: `design/app-icons/official-icon-round.webp` (reference only; minSdk 26 always
  resolves the adaptive `@mipmap/ic_baize`, which the launcher masks itself)
- Android launcher resource: `@mipmap/ic_baize`
  - background: `drawable/ic_baize_background.xml` (vertical gradient)
  - foreground: `drawable-nodpi/ic_baize_art.webp` (432px transparent cut-out = full 108dp canvas,
    subject inside the 66dp safe circle)
  - monochrome (Android 13 themed icon): `drawable-nodpi/ic_baize_monochrome_art.png`
- Splash icon: `drawable-*dpi/ic_baize_splash_art.webp` (cut-out with soft glow, no tile)

`baize-emerald-30010.webp` is the previous (30010–3.0.0-pre) emerald icon; `baize-spirit-beast.webp`
is the one before it.
