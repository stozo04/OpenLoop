"""Build the 512x512 Play Store listing icon.

Spec (Play Console, 2026):
- 512x512 PNG, 32-bit color
- NO transparency (solid background)
- < 1024 KB
- Play auto-applies a 30% corner radius at display time -> we ship a square.
- Safe area: keep critical art well inside the central ~70% (Play's display crop +
  rounded corners can clip near the edges on some surfaces).
"""
from __future__ import annotations
from pathlib import Path
from PIL import Image

# OpenLoop brand surface (matches the SurfaceContainerHigh / Canvas tokens in Color.kt)
CANVAS_BG = (10, 10, 12)        # #0A0A0C — matches Canvas token

SIZE = 512
SAFE_FRACTION = 0.78            # logo occupies ~78% of canvas width

src_foreground = Path("/sessions/busy-brave-cannon/mnt/OpenRang/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png")
dst = Path("/sessions/busy-brave-cannon/mnt/outputs/play_store_icon_512.png")

# 1) Solid dark square background — no transparency, no rounded corners
bg = Image.new("RGB", (SIZE, SIZE), CANVAS_BG)

# 2) Load the recolored foreground (1080x1080 with transparent bg) and downscale
fg = Image.open(src_foreground).convert("RGBA")
target_w = int(SIZE * SAFE_FRACTION)
fg_resized = fg.resize((target_w, target_w), Image.LANCZOS)

# 3) Center it
offset = ((SIZE - target_w) // 2, (SIZE - target_w) // 2)
# Composite RGBA over RGB by converting bg to RGBA first
bg_rgba = bg.convert("RGBA")
bg_rgba.alpha_composite(fg_resized, dest=offset)

# 4) Flatten back to RGB (no alpha) and save
out = bg_rgba.convert("RGB")
out.save(dst, "PNG", optimize=True)

# 5) Sanity check size / format
import os
size_kb = os.path.getsize(dst) / 1024
print(f"wrote {dst}  ({SIZE}x{SIZE}, {size_kb:.1f} KB, mode={out.mode})")
assert size_kb < 1024, f"Over 1024 KB: {size_kb:.1f}"
