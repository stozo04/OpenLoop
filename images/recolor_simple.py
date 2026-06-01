"""Full-tint recolor (no saturation gate) — for images whose background is transparent."""
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
    lum = (0.2126*rgb[..., 0] + 0.7152*rgb[..., 1] + 0.0722*rgb[..., 2]) / 255.0
    lum = lum[..., None]
    mask = arr[..., 3] > 8
    xs = np.where(mask.any(axis=0))[0]
    x_min, x_max = (int(xs.min()), int(xs.max())) if len(xs) else (0, w-1)
    x_coords = np.arange(w, dtype=np.float32)
    t = np.clip((x_coords - x_min) / max(1.0, (x_max - x_min)), 0.0, 1.0)
    tint = LIME[None, :] + (AQUA - LIME)[None, :] * t[:, None]
    tint = np.broadcast_to(tint[None, :, :], (h, w, 3))
    base = tint * lum
    highlight_mix = np.clip((lum - 0.55) / 0.45, 0.0, 1.0)
    out_rgb = base + (255.0 - base) * (highlight_mix * 0.85)
    out_rgb = np.clip(out_rgb, 0, 255)
    out = np.concatenate([out_rgb, alpha], axis=2).astype(np.uint8)
    Image.fromarray(out, "RGBA").save(dst_path, "PNG")
    print(f"wrote {dst_path}")

if __name__ == "__main__":
    recolor(Path(sys.argv[1]), Path(sys.argv[2]))
