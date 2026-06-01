"""Render the OpenLoop neon-infinity launcher icon in the new color schema.

Silhouette: two open circles overlapping slightly so they read as a figure-8 with
open lobe interiors (matches Steven's POC). Per-pixel gradient places Electric
Lime on the left circle, Aqua on the right, blended through the crossover.
"""
from __future__ import annotations
import math, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

LIME = (205, 255, 79)   # #CDFF4F
AQUA = (52, 225, 213)   # #34E1D5

def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return (int(a[0]+(b[0]-a[0])*t), int(a[1]+(b[1]-a[1])*t), int(a[2]+(b[2]-a[2])*t))

def circle_points(cx, cy, r, n):
    return [(cx + r*math.cos(2*math.pi*i/n), cy + r*math.sin(2*math.pi*i/n)) for i in range(n)]

def stroke_tube(size, pts, base_radius, sample_color):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    r = base_radius
    for x, y in pts:
        rgb = sample_color(x, y)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=rgb + (255,))
    return img

def render(size: int) -> Image.Image:
    cx = cy = size / 2
    ring_r = size * 0.20          # radius of each lobe circle
    offset = ring_r * 0.95        # horizontal offset of lobe centers (slight overlap)
    tube_radius = size * 0.045    # tube thickness

    left_c = (cx - offset, cy)
    right_c = (cx + offset, cy)
    left_edge = cx - offset - ring_r
    right_edge = cx + offset + ring_r

    def grad_color(x, _y):
        t = (x - left_edge) / (right_edge - left_edge)
        return lerp(LIME, AQUA, t)

    n = int(size * 4)
    pts = circle_points(*left_c, ring_r, n) + circle_points(*right_c, ring_r, n)

    # Wide soft outer halo
    halo_wide = stroke_tube(size, pts, tube_radius * 3.0, grad_color)
    halo_wide = halo_wide.filter(ImageFilter.GaussianBlur(radius=size * 0.06))
    hw = halo_wide.split()[3].point(lambda a: int(a * 0.55))
    halo_wide.putalpha(hw)

    # Tight bright halo
    halo_tight = stroke_tube(size, pts, tube_radius * 1.6, grad_color)
    halo_tight = halo_tight.filter(ImageFilter.GaussianBlur(radius=size * 0.018))
    ht = halo_tight.split()[3].point(lambda a: min(255, int(a * 1.15)))
    halo_tight.putalpha(ht)

    # Saturated tube body
    body = stroke_tube(size, pts, tube_radius, grad_color)
    body = body.filter(ImageFilter.GaussianBlur(radius=size * 0.0015))

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas = Image.alpha_composite(canvas, halo_wide)
    canvas = Image.alpha_composite(canvas, halo_tight)
    canvas = Image.alpha_composite(canvas, body)
    return canvas

def render_preview_on_black(logo):
    black = Image.new("RGBA", logo.size, (0, 0, 0, 255))
    return Image.alpha_composite(black, logo)

if __name__ == "__main__":
    size = int(sys.argv[1]) if len(sys.argv) > 1 else 1080
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("/sessions/busy-brave-cannon/mnt/outputs")
    out_dir.mkdir(parents=True, exist_ok=True)
    logo = render(size)
    logo.save(out_dir / f"ic_launcher_foreground_preview_{size}.png", "PNG")
    render_preview_on_black(logo).save(out_dir / f"ic_launcher_on_black_preview_{size}.png", "PNG")
    print("done", size)
