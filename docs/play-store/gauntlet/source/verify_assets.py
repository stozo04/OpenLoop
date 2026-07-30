from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageEnhance, ImageOps, ImageStat

ROOT = Path(__file__).resolve().parents[1]
TESTS = ROOT / "tests"
TESTS.mkdir(parents=True, exist_ok=True)


def technical_report(path: Path) -> dict[str, object]:
    with Image.open(path) as source:
        image = source.convert("RGB")
        gray = ImageOps.grayscale(image)
        stat = ImageStat.Stat(gray)
        histogram = gray.histogram()
        pixel_count = image.width * image.height
        dark = sum(histogram[:45]) / pixel_count
        bright = sum(histogram[201:]) / pixel_count
        spread = stat.stddev[0]
        extrema = gray.getextrema()

        half = image.resize((512, 250), Image.Resampling.LANCZOS)
        thumb = image.resize((320, 156), Image.Resampling.LANCZOS)
        store_crop_width = round(image.height * 16 / 9)
        store_crop_left = (image.width - store_crop_width) // 2
        store_card = image.crop(
            (
                store_crop_left,
                0,
                store_crop_left + store_crop_width,
                image.height,
            )
        ).resize((416, 234), Image.Resampling.LANCZOS)
        grayscale = ImageOps.grayscale(image).convert("RGB")

        stem = path.stem
        half.save(TESTS / f"{stem}-50.png", optimize=True)
        thumb.save(TESTS / f"{stem}-thumb.png", optimize=True)
        store_card.save(TESTS / f"{stem}-store-card.png", optimize=True)
        grayscale.save(TESTS / f"{stem}-gray.png", optimize=True)

        return {
            "file": path.name,
            "format": source.format,
            "source_mode": source.mode,
            "dimensions": [source.width, source.height],
            "bytes": path.stat().st_size,
            "opaque_rgb_export_required": source.mode != "RGB",
            "mean_luma": round(stat.mean[0], 2),
            "luma_stddev": round(spread, 2),
            "dark_pixel_share": round(dark, 4),
            "bright_pixel_share": round(bright, 4),
            "luma_extrema": list(extrema),
            "passes_feature_graphic_dimensions": source.size == (1024, 500),
            "passes_file_size_15mb": path.stat().st_size <= 15 * 1024 * 1024,
            "passes_hierarchy_spread": spread >= 30,
        }


def run(paths: list[Path]) -> None:
    report = [technical_report(path) for path in paths]
    (TESTS / "verification.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    import sys

    selected = [Path(argument).resolve() for argument in sys.argv[1:]]
    if not selected:
        selected = sorted((ROOT / "concepts").glob("*-g2.png"))
    run(selected)
