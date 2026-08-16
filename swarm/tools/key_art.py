#!/usr/bin/env python
"""
The ONE character-art pipeline for the lens swarm.

Kayley's ruling (2026-08-15): *"Same for art pipeline: one key/autocrop/cwebp path, no
football-only encoder."* So this is generic. Football, Pizza and Dog all go through it; nothing
here knows the name of a lens.

    key -> autocrop -> downscale -> WebP q90 (lossy body, LOSSLESS alpha) -> thumbnail

Usage
-----
    python swarm/tools/key_art.py IN.png OUT.webp [--thumb OUT_THUMB.webp]
                                 [--max 1024] [--thumb-max 256]
                                 [--dark 100] [--sat 30] [--erode 2] [--feather 1.0]
                                 [--pad 0.02] [--debug DIR]

Why it is built this way
------------------------
**Colour thresholds cannot key this class of image.** Measured on `football.jpg`: the drop shadow
runs luminance 116-140 while parts of the ball run 170 — the shadow is *darker than the subject*.
Any global "remove light pixels" rule either keeps the shadow or eats the subject. And a naive
"remove white" rule punches holes straight through the football's white laces.

So the key is **connectivity**, in two flood fills:

  1. `core`     = flood from an interior seed across "definitely subject" pixels
                  (dark OR saturated). Isolated compression specks in the shadow are not
                  connected to it, so they are dropped for free.
  2. `exterior` = flood from every border pixel across `NOT core`.
     `subject` = `NOT exterior`, which fills every enclosed hole — the laces come back solid,
     without a single colour rule ever having to recognise them.

Edge handling: the subject's outer pixels are blended with the white backdrop, so a binary mask
leaves a bright fringe. We erode by `--erode` px to cut it, then feather with a small blur so the
edge is anti-aliased rather than jagged. Lesson 032 notes that a hard-edged quad reads as pasted-on;
the same is true of a hard-edged cut-out.

Encoding: WebP `quality=90`, `exact=False`. libwebp stores the **alpha plane losslessly even in
lossy mode**, which is what PRD 11.2 relies on — the cut-out edge that makes a character read stays
bit-exact while the photographic body compresses. Verified by the script itself (`--debug` reports
the max alpha delta between the pre-encode mask and the decoded file).
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def _flood(mask: np.ndarray, seeds, name: str) -> np.ndarray:
    """
    Connected region (4-way) of `mask` reachable from any of `seeds`.

    Hand-rolled scanline fill rather than `ImageDraw.floodfill`: that function is a documented
    *experimental* PIL API and on Pillow 12.1.1 it silently fills nothing — verified with a 10x10
    repro before this was written. A key that quietly returns an empty mask would have shipped a
    fully-opaque square, so this primitive is ours.

    Scanline rather than per-pixel BFS: each stack entry consumes a whole horizontal run, which
    keeps a 1320x1320 image well under a second in pure Python.
    """
    h, w = mask.shape
    filled = np.zeros((h, w), dtype=bool)
    stack = [(int(x), int(y)) for (x, y) in seeds
             if 0 <= x < w and 0 <= y < h and mask[y, x] and not filled[y, x]]
    while stack:
        x, y = stack.pop()
        if filled[y, x] or not mask[y, x]:
            continue
        row_m, row_f = mask[y], filled[y]
        left = x
        while left > 0 and row_m[left - 1] and not row_f[left - 1]:
            left -= 1
        right = x
        while right < w - 1 and row_m[right + 1] and not row_f[right + 1]:
            right += 1
        row_f[left:right + 1] = True
        for ny in (y - 1, y + 1):
            if 0 <= ny < h:
                nm, nf = mask[ny], filled[ny]
                span = nm[left:right + 1] & ~nf[left:right + 1]
                if span.any():
                    # push one seed per contiguous run in the neighbouring row
                    idx = np.flatnonzero(span)
                    breaks = np.flatnonzero(np.diff(idx) > 1)
                    starts = np.concatenate(([idx[0]], idx[breaks + 1])) if len(idx) else idx
                    for s in starts:
                        stack.append((left + int(s), ny))
    if not filled.any():
        print(f"  !! {name}: flood reached nothing — check the seeds/threshold", file=sys.stderr)
    return filled


def _save_atomic(img: Image.Image, path: str, **kw) -> None:
    """
    Encode to a temp file in the same directory, then replace.

    In a shared checkout another agent — or the judge — can read this path at any moment. An
    in-place re-encode leaves an observable window where the file is truncated: during this run
    Kayley read `lens_football.webp` mid-write and correctly reported it as 0 bytes, then had to be
    talked back off a stop-the-gate ruling. `os.replace` is atomic on the same filesystem, so the
    path only ever shows the old file or the new one.
    """
    tmp = path + ".tmp"
    img.save(tmp, "WEBP", **kw)
    os.replace(tmp, path)


def _morph(img: Image.Image, radius: int, grow: bool) -> Image.Image:
    """Dilate (grow) or erode the white region. MaxFilter/MinFilter cap at 9, so we iterate."""
    while radius > 0:
        step = min(radius, 4)
        f = ImageFilter.MaxFilter if grow else ImageFilter.MinFilter
        img = img.filter(f(2 * step + 1))
        radius -= step
    return img


def key(rgb: np.ndarray, dark: int, sat_min: int, erode: int, feather: float,
        close: int = 0, edge_white: int = 0, edge_band: int = 0, debug=None):
    a = rgb.astype(np.int16)
    lum = 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]
    sat = a.max(2) - a.min(2)
    h, w = lum.shape

    # 1. "Definitely the subject": dark, or coloured. Deliberately conservative — this only has to
    #    catch a connected skeleton, never the whole silhouette. Holes are recovered in step 3.
    solid = (lum < dark) | (sat > sat_min)

    # Seed from the densest interior area rather than the geometric centre, so an off-centre or
    # hollow subject still seeds correctly.
    ys, xs = np.nonzero(solid)
    if len(xs) == 0:
        raise SystemExit("No subject pixels found — loosen --dark / --sat.")
    seed = (int(np.median(xs)), int(np.median(ys)))
    if not solid[seed[1], seed[0]]:
        seed = (int(xs[len(xs) // 2]), int(ys[len(ys) // 2]))
    core = _flood(solid, [seed], "core")

    # 2. Everything reachable from the border without crossing the subject.
    border = (
        [(x, 0) for x in range(w)] + [(x, h - 1) for x in range(w)]
        + [(0, y) for y in range(h)] + [(w - 1, y) for y in range(h)]
    )
    exterior = _flood(~core, border, "exterior")

    # 3. Subject = everything the outside cannot reach. Fills the laces; drops loose specks.
    subject = ~exterior

    alpha = np.where(subject, 255, 0).astype(np.uint8)
    img_a = Image.fromarray(alpha, mode="L")

    # Morphological CLOSE (dilate then erode by the same radius) before anything else.
    #
    # The `solid` predicate is deliberately conservative, so wherever the subject's rim fades into
    # the backdrop — on the football, the underside where the ball meets its own drop shadow — the
    # core stops short and the exterior flood bites into the silhouette. Measured on the first
    # football run: a smooth ellipse came out with a crumbly 5-15px boundary.
    #
    # Closing fills those bites without assuming a shape: it restores concave nicks up to the
    # radius while leaving the overall outline where it was. Note this is NOT a convex hull —
    # a hull would be perfect for a ball and wrong for dog ears, and this file is the shared
    # pipeline for every character.
    if close > 0:
        img_a = _morph(img_a, close, grow=True)
        img_a = _morph(img_a, close, grow=False)

    # Edge decontamination — remove backdrop that the close pass swallowed.
    #
    # Closing dilates before it erodes, so wherever the subject's true edge is *straighter than the
    # closing radius* the dilation steps out into the backdrop and the erosion cannot fully pull it
    # back. On the football this happened along the flat left tip (the source photo clips the ball
    # at x=0), leaving a white sliver of backdrop inside the silhouette — clearly visible once the
    # art was composited on magenta.
    #
    # The fix has to be surgical, because the subject legitimately contains near-white pixels: the
    # laces. So this only touches a narrow BAND along the silhouette edge. The laces sit deep in the
    # interior, far outside the band, and are untouched by construction.
    if edge_white > 0 and edge_band > 0:
        cur = np.asarray(img_a) > 127
        inner = np.asarray(_morph(Image.fromarray(np.where(cur, 255, 0).astype(np.uint8), "L"),
                                  edge_band, grow=False)) > 127
        band = cur & ~inner
        contaminated = band & (lum > edge_white) & (sat < 40)
        if contaminated.any():
            print(f"  edge decontamination: cleared {int(contaminated.sum())} backdrop px "
                  f"from a {edge_band}px edge band (lum>{edge_white})")
            img_a = Image.fromarray(np.where(cur & ~contaminated, 255, 0).astype(np.uint8), "L")

    # Erode: MinFilter shrinks the white region, cutting the backdrop-blended fringe.
    if erode > 0:
        img_a = _morph(img_a, erode, grow=False)
    if feather > 0:
        img_a = img_a.filter(ImageFilter.GaussianBlur(feather))

    stats = {
        "solid_px": int(solid.sum()),
        "core_px": int(core.sum()),
        "subject_px": int(subject.sum()),
        "specks_dropped": int((solid & ~core & ~exterior).sum()),
    }
    if debug:
        os.makedirs(debug, exist_ok=True)
        Image.fromarray(np.where(solid, 255, 0).astype(np.uint8), "L").save(f"{debug}/01_solid.png")
        Image.fromarray(np.where(core, 255, 0).astype(np.uint8), "L").save(f"{debug}/02_core.png")
        Image.fromarray(np.where(exterior, 255, 0).astype(np.uint8), "L").save(f"{debug}/03_exterior.png")
        img_a.save(f"{debug}/04_alpha.png")
    return np.asarray(img_a), stats


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("src")
    p.add_argument("dst")
    p.add_argument("--thumb")
    p.add_argument("--max", type=int, default=1024,
                   help="long side cap. 1024 is MANDATORY: LensSurfaceProcessor.loadTexture() "
                        "clamps width and height INDEPENDENTLY at MAX_ART_PX, so a non-square "
                        "asset over the cap is silently squashed (finding A1).")
    p.add_argument("--thumb-max", type=int, default=256)
    p.add_argument("--dark", type=int, default=100)
    p.add_argument("--sat", type=int, default=30)
    p.add_argument("--erode", type=int, default=2)
    p.add_argument("--close", type=int, default=8,
                   help="morphological closing radius, px. Repairs concave bites where the "
                        "subject rim fades into the backdrop. Not a convex hull — safe for "
                        "concave art like dog ears.")
    p.add_argument("--feather", type=float, default=1.0)
    p.add_argument("--edge-white", type=int, default=238, dest="edge_white",
                   help="clear backdrop brighter than this from the silhouette edge band. 0 disables.")
    p.add_argument("--edge-band", type=int, default=14, dest="edge_band",
                   help="width of that edge band in px. Keep well under the distance from the "
                        "silhouette to any legitimately bright interior detail (e.g. the laces).")
    p.add_argument("--pad", type=float, default=0.0, help="transparent margin as a fraction of the crop")
    p.add_argument("--quality", type=int, default=90)
    p.add_argument("--debug")
    args = p.parse_args()

    src = Image.open(args.src).convert("RGB")
    print(f"source      : {args.src} {src.size[0]}x{src.size[1]} {Image.open(args.src).format}")
    rgb = np.asarray(src)

    alpha, stats = key(rgb, args.dark, args.sat, args.erode, args.feather, args.close,
                       args.edge_white, args.edge_band, args.debug)
    print(f"key         : solid={stats['solid_px']} core={stats['core_px']} "
          f"subject={stats['subject_px']} specks_dropped={stats['specks_dropped']}")

    rgba = np.dstack([rgb, alpha])
    im = Image.fromarray(rgba, "RGBA")

    # autocrop to the alpha bounding box
    bbox = im.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    if bbox is None:
        raise SystemExit("Alpha is empty after keying.")
    im = im.crop(bbox)
    print(f"autocrop    : {bbox} -> {im.size[0]}x{im.size[1]}")

    if args.pad > 0:
        pad = int(round(max(im.size) * args.pad))
        padded = Image.new("RGBA", (im.size[0] + 2 * pad, im.size[1] + 2 * pad), (0, 0, 0, 0))
        padded.paste(im, (pad, pad))
        im = padded
        print(f"pad         : +{pad}px -> {im.size[0]}x{im.size[1]}")

    def resized(target: int) -> Image.Image:
        if max(im.size) <= target:
            return im.copy()
        s = target / max(im.size)
        return im.resize((max(1, round(im.size[0] * s)), max(1, round(im.size[1] * s))), Image.LANCZOS)

    art = resized(args.max)
    _save_atomic(art, args.dst, quality=args.quality, method=6)
    w, h = art.size
    print(f"art         : {args.dst} {w}x{h} artAspect={h / w:.4f} "
          f"({os.path.getsize(args.dst) / 1024:.1f} KB)")

    # Verify the alpha really did survive losslessly (PRD 11.2's claim, checked not assumed).
    back = Image.open(args.dst).convert("RGBA")
    d = int(np.abs(np.asarray(back)[:, :, 3].astype(int) - np.asarray(art)[:, :, 3].astype(int)).max())
    print(f"alpha check : max delta after encode = {d} ({'LOSSLESS' if d == 0 else 'LOSSY — investigate'})")

    if args.thumb:
        th = resized(args.thumb_max)
        _save_atomic(th, args.thumb, quality=args.quality, method=6)
        print(f"thumb       : {args.thumb} {th.size[0]}x{th.size[1]} "
              f"({os.path.getsize(args.thumb) / 1024:.1f} KB)")

    print(f"\nartAspect for Lens.kt = {h / w:.3f}f   (encoded height/width, per finding A2)")


if __name__ == "__main__":
    main()
