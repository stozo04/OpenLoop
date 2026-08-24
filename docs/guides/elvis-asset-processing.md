# Elvis Lens Asset Processing — Photoreal Bitmap Workflow

**Date:** 2026-08-16  
**Status:** Complete — assets processed and measured

## Processed Assets

Two photoreal bitmap layers in `app/src/main/res/drawable-nodpi/`:

1. **lens_elvis_shades_art.webp** — Gold aviators
   - Source: chroma-keyed 3D render, alpha applied, trimmed
   - Measured: 1420×504 pixels, artAspect = 0.3549
   - PBR metal frames in polished gold, double bridge, brown gradient lenses
   - Compressed ~q90 WebP

2. **lens_elvis_pompadour_art.webp** — U-wig (quiff + sideburns + face hole)
   - Source: chroma-keyed 3D render, alpha applied, trimmed
   - Measured: 974×980 pixels, artAspect = 1.0062
   - Glossy black hair with integrated sideburns, face hole in lower-center
   - Compressed ~q90 WebP

## Processing Steps

### 1. Chroma-Key to Alpha

Using ImageMagick or equivalent:

```bash
# Shades
convert elvis-shades-hq.png \
  -fuzz 5% -transparent "#00FF00" \
  -trim +repage \
  lens_elvis_shades_art.png

# Hair (includes sideburns)
convert elvis-pompadour-hq.png \
  -fuzz 5% -transparent "#00FF00" \
  -trim +repage \
  lens_elvis_pompadour_art.png
```

**Critical:**

- `-fuzz 5%` handles green fringe/antialiasing
- `-trim +repage` crops to opaque bounds, removes transparent padding
- Keep hair wisps and fine details
- Clean edges without halo artifacts

### 2. Measure Opaque Bounds

After chroma-key + trim, measure the actual PNG dimensions:

```bash
identify -format "%w x %h" lens_elvis_shades_art.png
identify -format "%w x %h" lens_elvis_pompadour_art.png
```

Calculate `artAspect = height / width` for each. These values go into `Lens.Elvis`.

### 3. Compress for APK Size

Convert to WebP with quality tuning:

```bash
cwebp -q 90 lens_elvis_shades_art.png -o lens_elvis_shades_art.webp
cwebp -q 90 lens_elvis_pompadour_art.png -o lens_elvis_pompadour_art.webp
```

Target: < 200 KB per asset while maintaining photoreal quality on device.

### 4. Android Placement

Place in:

- `app/src/main/res/drawable-nodpi/` (density-independent, exact pixels)
- OR `app/src/main/res/drawable-xxhdpi/` (if sized for ~420dpi baseline)

Use WebP if available, fallback to PNG if WebP isn't supported (API 14+ for lossy WebP with transparency).

## Quality Gates

Before committing assets:

1. No green fringe/halo on edges
2. Hair wisps preserved (not destroyed by over-aggressive keying)
3. Metallic sheen visible on gold frames
4. Lens gradient smooth (no banding)
5. File size reasonable (each asset < 200 KB ideally)
6. Looks like the 3D reference on a phone preview

## Code Integration

`Lens.kt` updated to reference bitmaps via `R.drawable`:

- `R.drawable.lens_elvis_pompadour_art` (hair with sideburns)
- `R.drawable.lens_elvis_shades_art` (gold aviators)

Draw order: hair first, shades LAST (glasses on top).

Placement solved from anatomy table using measured artAspect from processed PNGs.

## Apache 2.0 Compliance

Assets are original 3D renders provided by repo owner, not licensed Ray-Ban/brand content.
No logos, no trademarked designs, no network dependencies.
