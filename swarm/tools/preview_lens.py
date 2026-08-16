#!/usr/bin/env python
"""
Schematic lens preview — a geometry check, NOT a face test.

**Read this before believing anything it draws.** The emulator's virtual scene has no face and
neither of the building agents can see a lens land on one. This tool does not change that. What it
does is render a lens's art at its declared face-unit geometry against a *schematic* head built from
the same reference table `Lens.kt` documents, so the questions that are pure arithmetic get answered
before hardware:

  * does an opaque character actually cover crown-to-jaw, **including at the sides** where the
    silhouette has narrowed?  (PRD 4b: a visible forehead is a failed character.)
  * does a prop sit where its KDoc claims, and does it cover an eye it should not?
  * is the art the right way up, and is `artAspect` consistent with the encoded file?

It answers none of: tracking, roll, mirroring, jitter, or whether it is funny.

Mirrors `LensAnchor.sticker()` for an upright face: origin at the eye midpoint, `unit` = eye-to-mouth
distance, `up` straight up. Because the face is upright the rotation terms drop out, which is exactly
why this is a geometry check and not a substitute for the real thing.

Usage
-----
    python swarm/tools/preview_lens.py --art app/src/main/res/drawable-nodpi/lens_football_art.webp \\
        --width 4.7 --aspect 0.572 --up 0.10 --out preview.png
    python swarm/tools/preview_lens.py --vector app/src/main/res/drawable/lens_dog.xml \\
        --width 2.40 --aspect 1.008 --up 0.59 --out preview.png
    # add --features "eyeSpacing,eyeUp,eyeWidth,mouthUp,mouthWidth" to mark a character's features
"""

from __future__ import annotations

import argparse
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Reference table from Lens.kt's KDoc. One unit = eye-to-mouth distance.
HEAD_HALF_WIDTH = 1.55 / 2
CROWN_ABOVE_EYES = 1.25
CHIN_BELOW_EYES = 1.00
MOUTH_HALF_WIDTH = 0.8 / 2
# Interpupillary is ~0.8 of eye-to-mouth (the Lens.kt KDoc records that assuming otherwise made
# every lens ~20% oversized), so an eye sits ~0.4 units off the centre line.
EYE_OFFSET = 0.40


# --------------------------------------------------------------------- vector rasterising

def _flatten_cubic(p0, p1, p2, p3, n=24):
    out = []
    for i in range(1, n + 1):
        t = i / n
        s = 1 - t
        out.append((
            s * s * s * p0[0] + 3 * s * s * t * p1[0] + 3 * s * t * t * p2[0] + t * t * t * p3[0],
            s * s * s * p0[1] + 3 * s * s * t * p1[1] + 3 * s * t * t * p2[1] + t * t * t * p3[1],
        ))
    return out


def _subpaths(d: str):
    """Parse the M/C/A/Z subset used by this repo's vector art into filled polygons."""
    tokens = re.findall(r"[MCLAZmclaz]|-?\d*\.?\d+", d)
    polys, cur, start, pos, i = [], [], None, (0.0, 0.0), 0
    cmd = None
    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                if len(cur) > 2:
                    polys.append(cur)
                cur = [] if start is None else [start]
                pos = start or pos
            continue
        nums = []
        need = {"M": 2, "L": 2, "C": 6, "A": 7}.get((cmd or "M").upper(), 2)
        while len(nums) < need and i < len(tokens) and not tokens[i].isalpha():
            nums.append(float(tokens[i]))
            i += 1
        c = (cmd or "M").upper()
        if c == "M":
            if len(cur) > 2:
                polys.append(cur)
            pos = (nums[0], nums[1])
            start = pos
            cur = [pos]
        elif c == "L":
            pos = (nums[0], nums[1])
            cur.append(pos)
        elif c == "C":
            p1, p2, p3 = (nums[0], nums[1]), (nums[2], nums[3]), (nums[4], nums[5])
            cur.extend(_flatten_cubic(pos, p1, p2, p3))
            pos = p3
        elif c == "A":
            # Only used here for the small circular dimples; approximate as an ellipse about the
            # midpoint of the current point and the endpoint.
            rx, ry, end = nums[0], nums[1], (nums[5], nums[6])
            cx, cy = (pos[0] + end[0]) / 2, (pos[1] + end[1]) / 2
            import math
            cur.extend([(cx + rx * math.cos(a * math.pi / 12), cy + ry * math.sin(a * math.pi / 12))
                        for a in range(25)])
            pos = end
    if len(cur) > 2:
        polys.append(cur)
    return polys


def rasterise_vector(path: str, target_w: int) -> Image.Image:
    root = ET.parse(path).getroot()
    vw = float(root.get(ANDROID + "viewportWidth"))
    vh = float(root.get(ANDROID + "viewportHeight"))
    scale = target_w / vw
    W, H = target_w, max(1, round(vh * scale))
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for p in root.findall("path"):
        colour = p.get(ANDROID + "fillColor") or "#FF000000"
        alpha_mul = float(p.get(ANDROID + "fillAlpha") or 1.0)
        c = colour.lstrip("#")
        if len(c) == 8:
            a, r, g, b = int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), int(c[6:8], 16)
        else:
            a, r, g, b = 255, int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)
        a = int(a * alpha_mul)
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        for poly in _subpaths(p.get(ANDROID + "pathData")):
            d.polygon([(x * scale, y * scale) for (x, y) in poly], fill=(r, g, b, a))
        img.alpha_composite(layer)
    print(f"vector      : {path} viewport {vw:g}x{vh:g} aspect {vh / vw:.4f} -> raster {W}x{H}")
    return img


# --------------------------------------------------------------------- preview

def main() -> None:
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--art")
    src.add_argument("--vector")
    ap.add_argument("--width", type=float, required=True, help="widthInUnits")
    ap.add_argument("--aspect", type=float, required=True, help="artAspect (height/width)")
    ap.add_argument("--up", type=float, required=True, help="upInUnits")
    ap.add_argument("--features", help="eyeSpacing,eyeUp,eyeWidth,mouthUp,mouthWidth")
    ap.add_argument("--out", required=True)
    ap.add_argument("--px-per-unit", type=int, default=150)
    a = ap.parse_args()

    U = a.px_per_unit
    W, H = 8 * U, 8 * U
    cx, cy = W // 2, H // 2  # eye midpoint at the canvas centre

    canvas = Image.new("RGBA", (W, H), (24, 26, 32, 255))
    d = ImageDraw.Draw(canvas)

    def P(ux, uy):  # face units -> pixels (uy positive = up)
        return (cx + ux * U, cy - uy * U)

    # Schematic head: ellipse spanning the documented crown/chin/width.
    top, bot = CROWN_ABOVE_EYES, -CHIN_BELOW_EYES
    d.ellipse([P(-HEAD_HALF_WIDTH, top)[0], P(0, top)[1],
               P(HEAD_HALF_WIDTH, bot)[0], P(0, bot)[1]],
              fill=(196, 158, 130, 255), outline=(255, 90, 90, 255), width=3)
    for s in (-1, 1):
        d.ellipse([*P(s * EYE_OFFSET - 0.09, 0.07), *P(s * EYE_OFFSET + 0.09, -0.07)],
                  fill=(40, 40, 48, 255))
    d.line([*P(-MOUTH_HALF_WIDTH, -1.0), *P(MOUTH_HALF_WIDTH, -1.0)], fill=(120, 40, 40, 255), width=4)

    # Reference lines: crown and chin are the PRD 4b pass/fail lines for a character.
    for u, lab in ((CROWN_ABOVE_EYES, "crown +1.25"), (0.0, "eye line 0"), (-1.0, "mouth -1.00")):
        d.line([0, P(0, u)[1], W, P(0, u)[1]], fill=(255, 90, 90, 90), width=1)
        d.text((6, P(0, u)[1] + 3), lab, fill=(255, 140, 140, 255))

    # The sticker quad, straight out of LensAnchor.sticker() with an upright face.
    half_w = a.width / 2
    half_h = half_w * a.aspect
    art = rasterise_vector(a.vector, round(a.width * U)) if a.vector else Image.open(a.art).convert("RGBA")
    art = art.resize((max(1, round(a.width * U)), max(1, round(a.width * a.aspect * U))), Image.LANCZOS)
    canvas.alpha_composite(art, (round(cx - half_w * U), round(cy - (a.up + half_h) * U)))

    d2 = ImageDraw.Draw(canvas)
    d2.rectangle([*P(-half_w, a.up + half_h), *P(half_w, a.up - half_h)],
                 outline=(90, 200, 255, 200), width=2)

    if a.features:
        es, eu, ew, mu, mw = (float(v) for v in a.features.split(","))
        for s in (-1, 1):
            d2.ellipse([*P(s * es - ew / 2, eu + ew * 0.31), *P(s * es + ew / 2, eu - ew * 0.31)],
                       outline=(120, 255, 120, 255), width=3)
        d2.ellipse([*P(-mw / 2, mu + mw * 0.31), *P(mw / 2, mu - mw * 0.31)],
                   outline=(120, 255, 120, 255), width=3)

    canvas.convert("RGB").save(a.out)

    print(f"quad        : x +/-{half_w:.3f}u  y {a.up + half_h:+.3f}u .. {a.up - half_h:+.3f}u")
    print(f"crown +1.25 : {'COVERED' if a.up + half_h >= CROWN_ABOVE_EYES else 'EXPOSED — fails PRD 4b'}"
          f"  (top edge {a.up + half_h:+.3f})")
    print(f"chin  -1.00 : {'covered' if a.up - half_h <= -CHIN_BELOW_EYES else 'exposed'}"
          f"  (bottom edge {a.up - half_h:+.3f})")
    print(f"wrote       : {a.out}")


if __name__ == "__main__":
    main()
