# Elvis Lens — Bitmap Assets Required

**Status:** Code updated, awaiting photoreal PNG processing

## Required Files

Place these in `app/src/main/res/drawable-nodpi/`:

### 1. `lens_elvis_pompadour_art.webp`
- Source: `elvis-pompadour-hq.png` (user-provided 3D render on green)
- Processing: Chroma-key #00FF00 to alpha, trim to opaque bounds, convert to WebP
- Content: Glossy black pompadour quiff **with sideburns already integrated**
- Target size: ~150-200 KB (see lens_broccoli_art.webp: 149K, lens_football_art.webp: 187K)

### 2. `lens_elvis_shades_art.webp`
- Source: `elvis-shades-hq.png` (user-provided 3D render on green)
- Processing: Chroma-key #00FF00 to alpha, trim to opaque bounds, convert to WebP
- Content: Gold aviators (PBR metal, double bridge, brown gradient lenses)
- Target size: ~150-200 KB

### 3. `lens_elvis.webp` (thumbnail)
- Tight crop showing finished look (hair + glasses)
- Target size: ~15-25 KB (see existing thumbnails: 15-22K)

## Processing Commands

See `docs/elvis-asset-processing.md` for full workflow.

Quick version:
```bash
# Chroma-key and trim
convert elvis-pompadour-hq.png -fuzz 5% -transparent "#00FF00" -trim +repage temp_hair.png
convert elvis-shades-hq.png -fuzz 5% -transparent "#00FF00" -trim +repage temp_shades.png

# Measure for artAspect
identify -format "%w x %h (aspect: %[fx:h/w])" temp_hair.png
identify -format "%w x %h (aspect: %[fx:h/w])" temp_shades.png

# Compress to WebP
cwebp -q 90 temp_hair.png -o lens_elvis_pompadour_art.webp
cwebp -q 90 temp_shades.png -o lens_elvis_shades_art.webp

# Move to drawable-nodpi
mv lens_elvis_*.webp app/src/main/res/drawable-nodpi/
```

## Code Update Required

After processing, update `Lens.kt` with measured dimensions:

```kotlin
// Hair layer
widthInUnits = ???f,  // Solve from anatomy table + desired coverage
artAspect = ???f,     // MEASURED: height / width from trimmed PNG
upInUnits = ???f,     // Derive from (top + bottom) / 2

// Shades layer  
widthInUnits = 2.1f,  // Slightly wider than Sunglasses (1.9)
artAspect = ???f,     // MEASURED: height / width from trimmed PNG
upInUnits = 0.06f,    // On bridge, same as Sunglasses
```

Current values are PLACEHOLDERS pending asset processing.

## Quality Gate

Before marking PR ready:
1. Assets processed and in `drawable-nodpi/`
2. artAspect values updated from measured PNGs
3. Device test confirms: shades on eye line, hair on hairline, mouth uncovered
4. Looks like the 3D reference on device (photoreal quality bar met)
