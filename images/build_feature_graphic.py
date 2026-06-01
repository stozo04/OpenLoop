"""Build the 1024x500 Play Store Feature Graphic for OpenLoop."""
from __future__ import annotations
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1024, 500
CANVAS = (10, 10, 12)
TEXT_PRIMARY = (243, 243, 246)
TEXT_SECONDARY = (173, 173, 184)
LIME = (205, 255, 79)
AQUA = (52, 225, 213)

FONT_SPACE_GROTESK = "/sessions/busy-brave-cannon/mnt/OpenRang/app/src/main/res/font/space_grotesk.ttf"
FONT_INTER = "/sessions/busy-brave-cannon/mnt/OpenRang/app/src/main/res/font/inter.ttf"
LOGO_FOREGROUND = "/sessions/busy-brave-cannon/mnt/OpenRang/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
OUT = Path("/sessions/busy-brave-cannon/mnt/outputs/feature_graphic_1024x500.png")

def load_variable_font(path: str, size: int, weight: int) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(path, size)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f

# Background
bg = Image.new("RGB", (W, H), CANVAS)
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse((-120, 70, 520, 700), fill=LIME + (28,))
gd.ellipse((520, -150, 1180, 480), fill=AQUA + (22,))
glow = glow.filter(ImageFilter.GaussianBlur(radius=80))
bg_rgba = bg.convert("RGBA")
bg_rgba.alpha_composite(glow)

# Logo
logo = Image.open(LOGO_FOREGROUND).convert("RGBA")
LOGO_SIZE = 420
logo_resized = logo.resize((LOGO_SIZE, LOGO_SIZE), Image.LANCZOS)
bg_rgba.alpha_composite(logo_resized, dest=(30, (H - LOGO_SIZE) // 2))

# Text
draw = ImageDraw.Draw(bg_rgba)
wordmark_font = load_variable_font(FONT_SPACE_GROTESK, 96, 700)  # Bold
tagline_font  = load_variable_font(FONT_INTER, 32, 500)           # Medium

text_x = 490
wm_bbox = draw.textbbox((0, 0), "OpenLoop", font=wordmark_font)
wm_h = wm_bbox[3] - wm_bbox[1]
tag_bbox = draw.textbbox((0, 0), "Point. Tap. Loop.", font=tagline_font)
tag_h = tag_bbox[3] - tag_bbox[1]
block_h = wm_h + 24 + tag_h
block_top = (H - block_h) // 2 - 16

draw.text((text_x, block_top - wm_bbox[1]), "OpenLoop",
          font=wordmark_font, fill=TEXT_PRIMARY)

under_y = block_top + wm_h + 14
under_x0, under_x1 = text_x, text_x + 220
for i in range(under_x1 - under_x0):
    t = i / max(1, (under_x1 - under_x0 - 1))
    r = int(LIME[0] + (AQUA[0] - LIME[0]) * t)
    g = int(LIME[1] + (AQUA[1] - LIME[1]) * t)
    b = int(LIME[2] + (AQUA[2] - LIME[2]) * t)
    draw.rectangle((under_x0 + i, under_y, under_x0 + i + 1, under_y + 6), fill=(r, g, b))

tag_y = under_y + 6 + 20
draw.text((text_x, tag_y - tag_bbox[1]), "Point. Tap. Loop.",
          font=tagline_font, fill=TEXT_SECONDARY)

final = bg_rgba.convert("RGB")
final.save(OUT, "PNG", optimize=True)

import os
print(f"wrote {OUT}  ({W}x{H}, {os.path.getsize(OUT)/1024:.1f} KB, mode={final.mode})")
