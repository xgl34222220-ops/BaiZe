# BaiZe dual-style UI contract

BaiZe keeps two independent Compose presentations over the same business state and Root actions.

## Material 3

- Standard centered app bars and Material typography.
- Compact tonal cards, 12dp page margins and 18dp primary card radius.
- Standard Material controls and a flat Material navigation dock.

## MIUIX / HyperOS

- Large left-aligned titles and spacious page rhythm.
- Pale lavender-neutral background, 18dp page margins and 24–26dp superellipse cards.
- MIUIX-specific tabs, rows, action tiles and floating glass dock.

The two styles must not share a conditional skin component. Routes dispatch directly to separate `material` and `miuix` screen implementations. Only state models, actions and semantic colors may be shared.
