"""Hue-targeted recolor for composite launcher PNGs.

The card has a slight dark-purple tint (sat ~0.18). The infinity uses
the same purple family + coral. We need to recolor the infinity + its
halo without disturbing the card's overall darkness.

Strategy: detect "logo-hue" pixels (originally purple ~270° or coral ~5°)
AND require either some chroma OR meaningfully-above-card luminance.
Tint weight ramps with how confidently we think the pixel is "logo".
"""
from __future__ import annotations
import sys
from pathlib import Path
from PIL import Image
import numpy as np

LIME = np.array([205, 255, 79], dtype=np.float32)
AQUA = np.array([52, 225, 213], dtype=np.float32)

def recolor(src_path: Path, dst_path: Path) -> None:
    src = Image.open(src_path).convert("RGBA")
    arr = np.asarray(src, dtype=np.float32)
    h, w, _ = arr.shape
    rgb = arr[..., :3]
    alpha = arr[..., 3:4]
    r = rgb[..., 0]; g = rgb[..., 1]; b = rgb[..., 2]

    lum = (0.2126*r + 0.7152*g + 0.0722*b) / 255.0

    # Compute saturation
    mx = rgb.max(axis=2); mn = rgb.min(axis=2)
    sat = np.where(mx > 1e-6, (mx - mn) / mx, 0.0)

    # Detect "logo-ness": pixel is either bright OR strongly chromatic in a
    # red/purple direction. The card is dark grey-purple — moderate sat but
    # very low brightness. The infinity body + halo is brighter.
    # Gate combines luminance lift above card baseline + saturation.
    bright = np.clip((lum - 0.18) / 0.20, 0.0, 1.0)          # 0 at card, 1 by lum~0.38
    chroma = np.clip((sat - 0.30) / 0.30, 0.0, 1.0)          # 0 at card sat ~0.18, 1 at body
    tint_weight = np.maximum(bright, chroma)                  # either signal is enough
    tint_weight = tint_weight[..., None]

    # Horizontal gradient based on chromatic bounding box
    chrom_mask = (sat > 0.30) & (lum > 0.2)
    xs = np.where(chrom_mask.any(axis=0))[0]
    x_min, x_max = (int(xs.min()), int(xs.max())) if len(xs) else (0, w-1)
    x_coords = np.arange(w, dtype=np.float32)
    t = np.clip((x_coords - x_min) / max(1.0, (x_max - x_min)), 0.0, 1.0)
    tint = LIME[None, :] + (AQUA - LIME)[None, :] * t[:, None]
    tint = np.broadcast_to(tint[None, :, :], (h, w, 3))

    lum3 = lum[..., None]
    base = tint * lum3
    highlight_mix = np.clip((lum3 - 0.55) / 0.45, 0.0, 1.0)
    tinted = base + (255.0 - base) * (highlight_mix * 0.85)

    out_rgb = rgb * (1.0 - tint_weight) + tinted * tint_weight
    out_rgb = np.clip(out_rgb, 0, 255)

    out = np.concatenate([out_rgb, alpha], axis=2).astype(np.uint8)
    Image.fromarray(out, "RGBA").save(dst_path, "PNG")
    print(f"wrote {dst_path}")

if __name__ == "__main__":
    recolor(Path(sys.argv[1]), Path(sys.argv[2]))
