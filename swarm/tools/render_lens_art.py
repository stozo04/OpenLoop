#!/usr/bin/env python
"""Render the shipped Cowboy and Vampire WebP lens art from committed sources.

Elvis's photoreal assets arrived as opaque binaries: nobody can re-derive them, and a change means
re-running a pipeline that is not in this repo. Cowboy's are **generated here**, so the art has the
same provenance as the code — the silhouettes in `swarm/art/` carry the tracing that produced them
(`docs/PRD-camera-lenses.md` §16.1), and everything below turns them into lit, textured surfaces.

Vampire's image-generated sources are committed beside those silhouettes. This tool removes either
near-invisible alpha or the border-connected light backdrop, crops the useful pixels, fits each
layer under the renderer's 1024 px cap, and composes the carousel chip from the shipped layers.

Run it from the repo root; it overwrites the seven assets in `drawable-nodpi/`:

    python swarm/tools/render_lens_art.py

## Why render instead of source a photograph

§11.2's rule is that a public Apache 2.0 repo ships only art it can redistribute. Generated art is
the cleanest possible answer to that — there is no licence to check, no attribution to carry, and
no provenance question at all.

## How the shading works

No 3D renderer. Each region gets a **height field**, normals come from its gradient, and a single
Lambert + Blinn-Phong evaluation lights it:

* the **crown** is a cylinder (`z = sqrt(1 - u^2)` across its own width, measured per row) with a
  broad cattleman dent down the middle and the pinched felt ridge either side of it;
* the **brim** is a rolled edge (a distance transform gives the roll) on a saddle that lifts toward
  the tips, and its lower band darkens as a *fraction of local thickness* — as an absolute band it
  turned the thick middle into a slab;
* the **band** and **buckle** are the same bevel with leather and brass response;
* the **moustache** is ~34k individual hairs traced through a flow field that runs steeply down at
  the philtrum, levels off, then hooks up at the tips. Hairs that escape the silhouette fade out,
  which is what stops the edge reading as a die-cut sticker.

Encoding follows §11.2: WebP quality 90, which libwebp stores with a **lossless alpha channel**, so
the cut-out edges are bit-exact while the photographic interior compresses (verified: max alpha
delta 0, mean colour delta ~1/255 inside the visible region).
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import (
    binary_dilation,
    binary_erosion,
    binary_propagation,
    distance_transform_edt,
    gaussian_filter,
    gaussian_filter1d,
    shift as ndshift,
)

_COMMANDS = ("M", "L", "C", "Z")   # the absolute subset the silhouettes use
TOKEN = re.compile("[%s]|-?\\d*\\.?\\d+" % "".join(_COMMANDS + tuple(c.lower() for c in _COMMANDS)))

ROOT = Path(__file__).resolve().parents[2]
SILHOUETTES = ROOT / "swarm/art"
DRAWABLES = ROOT / "app/src/main/res/drawable-nodpi"
SS = 3          # supersample factor; the alpha edge is what needs it
OUT_W = 1024    # LensSurfaceProcessor.MAX_ART_PX
QUALITY = 90
ALPHA_FLOOR = 8


# ------------------------------------------------------------------ silhouette rasterising

def _cubic(p0, p1, p2, p3, n=48):
    t = np.linspace(0, 1, n + 1)[1:]
    s = 1 - t
    return list(zip(s**3 * p0[0] + 3 * s * s * t * p1[0] + 3 * s * t * t * p2[0] + t**3 * p3[0],
                    s**3 * p0[1] + 3 * s * s * t * p1[1] + 3 * s * t * t * p2[1] + t**3 * p3[1]))


def _subpaths(data: str):
    """The absolute M/L/C/Z subset this repo's silhouettes use."""
    tokens = TOKEN.findall(data)
    polys, cur, start, pos, i, cmd = [], [], None, (0.0, 0.0), 0, None
    while i < len(tokens):
        token = tokens[i]
        if token.isalpha():
            cmd = token.upper()
            i += 1
            if cmd == "Z":
                if len(cur) > 2:
                    polys.append(cur)
                cur = [start] if start else []
            continue
        need = {"M": 2, "L": 2, "C": 6}[cmd]
        nums = []
        while len(nums) < need and i < len(tokens) and not tokens[i].isalpha():
            nums.append(float(tokens[i]))
            i += 1
        if cmd == "M":
            if len(cur) > 2:
                polys.append(cur)
            pos = start = (nums[0], nums[1])
            cur = [pos]
        elif cmd == "L":
            pos = (nums[0], nums[1])
            cur.append(pos)
        else:
            cur.extend(_cubic(pos, (nums[0], nums[1]), (nums[2], nums[3]), (nums[4], nums[5])))
            pos = (nums[4], nums[5])
    if len(cur) > 2:
        polys.append(cur)
    return polys


def path_masks(name: str, width: int):
    """One boolean mask per `<path>`, in the order they are drawn.

    The silhouettes carry NO xmlns: they are input to this tool, never packaged, and the
    `android:` namespace made every IDE outside `res/` report "URI is not registered" on a file
    that is not an Android resource. Plain attribute names, identical geometry.
    """
    root = ET.parse(SILHOUETTES / name).getroot()
    vw = float(root.get("viewportWidth"))
    vh = float(root.get("viewportHeight"))
    scale = width / vw
    height = int(round(vh * scale))
    out = []
    for node in root.findall("path"):
        img = Image.new("L", (width, height), 0)
        draw = ImageDraw.Draw(img)
        for poly in _subpaths(node.get("pathData")):
            draw.polygon([(x * scale, y * scale) for x, y in poly], fill=255)
        out.append(np.asarray(img) > 127)
    return out, width, height


# ------------------------------------------------------------------ shading

def bevel(mask, radius):
    t = np.clip(distance_transform_edt(mask) / max(radius, 1e-6), 0, 1)
    return np.sqrt(np.clip(1 - (1 - t) ** 2, 0, 1))


def normals(height, strength):
    gy, gx = np.gradient(height * strength)
    n = np.stack([-gx, -gy, np.ones_like(height)], -1)
    return n / np.linalg.norm(n, axis=-1, keepdims=True)


def light(n, albedo, ambient=0.34, diffuse=0.78, spec=0.0, shininess=28.0,
          tint=(1.0, 1.0, 1.0), L=(-0.40, -0.60, 0.69)):
    L = np.array(L, float)
    L /= np.linalg.norm(L)
    rgb = albedo * (ambient + diffuse * np.clip((n * L).sum(-1), 0, 1))[..., None]
    if spec:
        half = L + np.array([0.0, 0.0, 1.0])
        half /= np.linalg.norm(half)
        rgb = rgb + spec * (np.clip((n * half).sum(-1), 0, 1) ** shininess)[..., None] * np.array(tint)
    return rgb


def grain(shape, seed, octaves=(4, 10, 26, 64), amps=(0.55, 0.28, 0.13, 0.06)):
    rng = np.random.default_rng(seed)
    out = np.zeros(shape)
    for o, a in zip(octaves, amps):
        small = rng.random((max(2, shape[0] // o), max(2, shape[1] // o)))
        up = np.asarray(Image.fromarray((small * 255).astype(np.uint8)).resize(
            (shape[1], shape[0]), Image.BICUBIC), float) / 255.0
        out += a * (up - 0.5)
    return out / sum(amps)


def row_extent(mask, height, smooth):
    lo = np.full(height, np.nan)
    hi = np.full(height, np.nan)
    for y in np.unique(np.nonzero(mask)[0]):
        row = np.nonzero(mask[y])[0]
        lo[y], hi[y] = row.min(), row.max()
    ok = ~np.isnan(lo)
    # smoothed along y, or per-row rasterising jitter shows up as horizontal banding
    lo[ok] = gaussian_filter1d(lo[ok], smooth)
    hi[ok] = gaussian_filter1d(hi[ok], smooth)
    return lo, hi, ok


def save(rgb, alpha, out_w, path: Path):
    img = Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8))
    img.putalpha(Image.fromarray((np.clip(alpha, 0, 1) * 255).astype(np.uint8)))
    img = img.resize((out_w, int(round(img.height / SS))), Image.LANCZOS)
    img.save(path, format="WEBP", quality=QUALITY, method=6)
    print(f"  {path.name}  {img.size[0]}x{img.size[1]}  {path.stat().st_size / 1024:.1f} KB")
    return img


def cut_out_connected_light_background(image: Image.Image) -> Image.Image:
    """Turn a generated light checkerboard backdrop into alpha without touching enclosed whites."""
    rgb = np.array(image.convert("RGB"))
    channel_spread = rgb.max(axis=2) - rgb.min(axis=2)
    light_neutral = (channel_spread <= 24) & (rgb.min(axis=2) >= 170)
    seeds = np.zeros(light_neutral.shape, dtype=bool)
    seeds[0] = light_neutral[0]
    seeds[:, 0] = light_neutral[:, 0]
    seeds[:, -1] = light_neutral[:, -1]
    background = binary_propagation(seeds, mask=light_neutral)

    # Eat two neutral antialias pixels at the garment boundary, then rebuild a soft alpha edge.
    neutral_fringe = (channel_spread <= 30) & (rgb.min(axis=2) >= 70)
    for _ in range(2):
        background |= binary_dilation(background) & neutral_fringe
    foreground = ~binary_dilation(background)
    alpha = np.clip(gaussian_filter(foreground.astype(float), 0.65), 0, 1)

    rgba = np.dstack((rgb, np.round(alpha * 255).astype(np.uint8)))
    return Image.fromarray(rgba, "RGBA")


def check_light_background_cutout() -> None:
    """Prove that a white shirt reaching the lower edge is not mistaken for background."""
    pixels = np.full((11, 11, 3), 240, dtype=np.uint8)
    pixels[3, 3:8] = 0
    pixels[3:, 3] = 0
    pixels[3:, 7] = 0
    pixels[4:, 4:7] = 255
    alpha = np.array(cut_out_connected_light_background(Image.fromarray(pixels)).getchannel("A"))
    assert alpha[0, 0] == 0
    assert alpha[-1, 5] > 240


def prepare_generated(
    source: str,
    output: str,
    *,
    cutout_light_background: bool = False,
) -> Image.Image:
    """Crop and encode an image-generated source without inventing costume pixels."""
    image = Image.open(SILHOUETTES / source)
    if cutout_light_background:
        image = cut_out_connected_light_background(image)
    pixels = np.array(image.convert("RGBA"))
    pixels[..., 3] = np.where(pixels[..., 3] >= ALPHA_FLOOR, pixels[..., 3], 0)
    image = Image.fromarray(pixels)
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError(f"{source} has no visible pixels")
    left, top, right, bottom = bbox
    pad = 8
    image = image.crop((max(0, left - pad), max(0, top - pad),
                        min(image.width, right + pad), min(image.height, bottom + pad)))
    scale = min(1.0, OUT_W / max(image.size))
    if scale < 1.0:
        image = image.resize((round(image.width * scale), round(image.height * scale)), Image.LANCZOS)
    out = DRAWABLES / output
    image.save(out, format="WEBP", quality=QUALITY, method=6)
    print(f"  {out.name}  {image.width}x{image.height}  {out.stat().st_size / 1024:.1f} KB")
    return image


# ------------------------------------------------------------------ the hat

def render_hat() -> Image.Image:
    masks, W, H = path_masks("lens_cowboy_hat_silhouette.xml", OUT_W * SS)
    crown_p, _highlight, _crease, band_p, buckle_p, brim_p, _underside = masks
    brim = brim_p
    buckle = buckle_p & ~brim
    band = band_p & ~brim & ~buckle
    crown = crown_p & ~brim & ~band & ~buckle
    silhouette = crown_p | band_p | buckle_p | brim_p
    rgb = np.zeros((H, W, 3))
    xg = np.arange(W)[None, :].repeat(H, 0)

    lo, hi, ok = row_extent(crown_p, H, 6 * SS)
    centre = np.nan_to_num((lo + hi) / 2, nan=W / 2)
    half = np.nan_to_num((hi - lo) / 2, nan=1)
    u = np.clip((xg - centre[:, None]) / np.maximum(half[:, None], 1), -1, 1)
    rows = np.nonzero(ok)[0]
    fade = np.clip(1 - (np.arange(H) - rows.min()) / max(1, (rows.max() - rows.min()) * 0.62), 0, 1)[:, None]
    dent = np.exp(-(u / 0.22) ** 2) * fade * 0.50
    ridge = np.exp(-((np.abs(u) - 0.40) / 0.20) ** 2) * fade * 0.09
    hc = gaussian_filter((np.sqrt(np.clip(1 - u * u, 0, 1)) - dent + ridge) * half[:, None], 4.0 * SS)
    felt = light(normals(hc, 0.80 / SS), np.array([0x7E, 0x5B, 0x3E]) / 255.0,
                 ambient=0.40, diffuse=0.74, spec=0.06, shininess=8.0, tint=(1, .95, .88))
    rgb[crown] = (felt * (1 + 0.05 * grain((H, W), 7))[..., None])[crown]

    roll = bevel(brim, 5 * SS) * 2.0 * SS
    saddle = ((np.abs(xg - W / 2) / (W / 2)) ** 2.4) * 5.5 * SS
    hb = gaussian_filter(roll + saddle * brim, 2.2 * SS)
    brim_rgb = light(normals(hb, 0.80 / SS), np.array([0x88, 0x64, 0x47]) / 255.0,
                     ambient=0.40, diffuse=0.74, spec=0.06, shininess=8.0, tint=(1, .95, .88))
    brim_rgb *= (1 + 0.05 * grain((H, W), 19))[..., None]
    top = np.full(W, np.nan)
    bottom = np.full(W, np.nan)
    for x in np.unique(np.nonzero(brim)[1]):
        col = np.nonzero(brim[:, x])[0]
        top[x], bottom[x] = col.min(), col.max()
    okc = ~np.isnan(top)
    top[okc] = gaussian_filter1d(top[okc], 8 * SS)
    bottom[okc] = gaussian_filter1d(bottom[okc], 8 * SS)
    depth = np.clip((np.arange(H)[:, None] - np.nan_to_num(top)[None, :])
                    / np.maximum(np.nan_to_num(bottom - top)[None, :], 1), 0, 1)
    brim_rgb *= (1 - 0.34 * np.clip((depth - 0.55) / 0.45, 0, 1))[..., None]
    rgb[brim] = brim_rgb[brim]

    occlusion = np.clip(gaussian_filter(ndshift(crown_p.astype(float), (5 * SS, 0), order=1), 6 * SS), 0, 1) * brim
    rgb *= (1 - 0.46 * occlusion)[..., None]

    leather = light(normals(gaussian_filter(bevel(band, 6 * SS) * 2.2 * SS, 1.4 * SS), 0.80 / SS),
                    np.array([0x35, 0x2A, 0x21]) / 255.0, ambient=0.46, diffuse=0.66,
                    spec=0.13, shininess=20.0)
    rgb[band] = (leather * (1 + 0.11 * grain((H, W), 31, (3, 7, 15), (.5, .3, .2)))[..., None])[band]

    brass = light(normals(gaussian_filter(bevel(buckle, 13 * SS) * 7.0 * SS, 1.0 * SS), 0.80 / SS),
                  np.array([0xBC, 0x96, 0x46]) / 255.0, ambient=0.38, diffuse=0.66,
                  spec=0.95, shininess=30.0, tint=(1, .97, .84))
    rgb[buckle] = brass[buckle]

    # the silhouette darkens toward its rim so the hat separates from a bright scene
    rim = np.clip(distance_transform_edt(silhouette) / (8 * SS), 0, 1)
    rgb *= (1 - 0.26 * (1 - rim))[..., None]
    return save(rgb, silhouette.astype(float), OUT_W, DRAWABLES / "lens_cowboy_hat_art.webp")


# ------------------------------------------------------------------ the moustache

def render_mustache() -> Image.Image:
    masks, W, H = path_masks("lens_cowboy_mustache_silhouette.xml", OUT_W * SS)
    mask = masks[0]
    cx = half_w = W / 2.0

    broad = gaussian_filter(bevel(mask, 26 * SS) * 15.0 * SS, 5 * SS)
    lit = np.clip((normals(broad, 0.75 / SS) * np.array([-0.32, -0.72, 0.61])).sum(-1), 0, 1)
    lit = 0.30 + 1.05 * lit

    def flow(x):
        t = np.clip((x - cx) / half_w, -1, 1)
        r = np.deg2rad(55.0 - 100.0 * np.abs(t))
        return np.sign(t + 1e-9) * np.cos(r), np.sin(r)

    rng = np.random.default_rng(11)
    ys, xs = np.nonzero(binary_dilation(mask, iterations=3 * SS))
    n_hairs = 34000
    pick = rng.choice(len(xs), n_hairs, replace=False)
    buckets = 7
    images = [Image.new("L", (W, H), 0) for _ in range(buckets)]
    draws = [ImageDraw.Draw(b) for b in images]
    which = rng.integers(0, buckets, n_hairs)
    reach = rng.uniform(18 * SS, 62 * SS, n_hairs)
    widths = rng.choice([1, 1, 1, 2, 2, 3], n_hairs)
    step = 2.0 * SS

    for direction in (1, -1):
        x = xs[pick].astype(float)
        y = ys[pick].astype(float)
        tracks = [[(x[i], y[i])] for i in range(n_hairs)]
        for s in range(int(reach.max() / step)):
            fx, fy = flow(x)
            curl = 0.16 * np.sin(x * 0.004 + y * 0.011)   # so hairs are not perfectly parallel
            nx = fx * np.cos(curl) - fy * np.sin(curl)
            ny = fx * np.sin(curl) + fy * np.cos(curl)
            x = x + direction * nx * step
            y = y + direction * ny * step
            for i in np.nonzero(s * step < reach)[0]:
                tracks[i].append((x[i], y[i]))
        for i in range(n_hairs):
            if len(tracks[i]) > 1:
                draws[which[i]].line(tracks[i], fill=255, width=int(widths[i]))

    base = np.array([0x3A, 0x2B, 0x1F]) / 255.0
    tip = np.array([0x7A, 0x5B, 0x40]) / 255.0
    rgb = np.zeros((H, W, 3))
    acc = np.zeros((H, W))
    for i, image in enumerate(images):
        a = np.asarray(image, float) / 255.0
        f = i / (buckets - 1)
        colour = base * (1 - 0.35 * f) + tip * 0.35 * f
        rgb = np.where((a > acc)[..., None], colour[None, None, :] * (lit * (0.62 + 0.62 * f))[..., None], rgb)
        acc = np.maximum(acc, a)

    core = binary_erosion(mask, iterations=7 * SS)
    rgb = np.where((acc < 0.5)[..., None] & core[..., None],
                   (base * 0.92)[None, None, :] * lit[..., None], rgb)
    rgb *= (1 + 0.06 * grain((H, W), 5))[..., None]

    # hairs that escape the outline fade, so the edge is hair rather than a die-cut
    wisp = np.exp(-distance_transform_edt(~mask) / (7.0 * SS))
    alpha = np.clip(np.maximum(core.astype(float), gaussian_filter(acc, 0.7 * SS) * wisp) * 1.18, 0, 1)
    return save(rgb, alpha, OUT_W, DRAWABLES / "lens_cowboy_mustache_art.webp")


# ------------------------------------------------------------------ the carousel chip

def render_thumbnail(hat: Image.Image, mustache: Image.Image) -> None:
    """The hat over the moustache, the way the reference frames the pair.

    Composed from the two rendered layers rather than drawn again, so the chip cannot drift away
    from what the lens paints on a face. Square because `LensCarousel` fits each thumbnail inside a
    circle: the wide hat alone letterboxes to a sliver, and the corners must stay clear of the crop.
    """
    size = 320
    chip = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for art, width, x, y in ((hat, 300, 10, 34), (mustache, 224, 48, 190)):
        scaled = art.resize((width, max(1, round(width * art.height / art.width))), Image.LANCZOS)
        chip.alpha_composite(scaled, (x, y))
    out = DRAWABLES / "lens_cowboy.webp"
    chip.save(out, format="WEBP", quality=QUALITY, method=6)
    print(f"  {out.name}  {size}x{size}  {out.stat().st_size / 1024:.1f} KB")


def render_vampire() -> None:
    torso = prepare_generated(
        "lens_vampire_torso_source.png",
        "lens_vampire_torso_art.webp",
        cutout_light_background=True,
    )
    frame = prepare_generated(
        "lens_vampire_frame_source.png",
        "lens_vampire_frame_art.webp",
        cutout_light_background=True,
    )
    fangs = prepare_generated("lens_vampire_fangs_source.png", "lens_vampire_fangs_art.webp")

    size = 320
    chip = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    torso_width = 318
    torso_height = round(torso.height * torso_width / torso.width)
    torso_scaled = torso.resize((torso_width, torso_height), Image.LANCZOS)
    chip.alpha_composite(torso_scaled, ((size - torso_width) // 2, 102))

    frame_height = 244
    frame_width = round(frame.width * frame_height / frame.height)
    frame_scaled = frame.resize((frame_width, frame_height), Image.LANCZOS)
    chip.alpha_composite(frame_scaled, ((size - frame_width) // 2, 8))

    fang_width = 92
    fang_height = round(fangs.height * fang_width / fangs.width)
    fangs_scaled = fangs.resize((fang_width, fang_height), Image.LANCZOS)
    chip.alpha_composite(fangs_scaled, ((size - fang_width) // 2, 177))

    out = DRAWABLES / "lens_vampire.webp"
    chip.save(out, format="WEBP", quality=QUALITY, method=6)
    print(f"  {out.name}  {size}x{size}  {out.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    check_light_background_cutout()
    print("rendering Cowboy art:")
    render_thumbnail(render_hat(), render_mustache())
    print("rendering Vampire art:")
    render_vampire()
