"""Builds the AimTune icon files from the master image (dev-only, needs Pillow).

Usage, from the repository root:  python tools/icon/make_icons.py

Writes the window icons (PNG, on the classpath) and the Windows icon for jpackage (ICO). The README
banner (docs/screenshots/banner.svg) embeds aimtune-128.png as base64; update it by hand. The outputs are committed, so the build does not need Python.
"""

from __future__ import annotations

import logging
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
MASTER = ROOT / "docs" / "design" / "icon" / "aimtune-icon.png"
WINDOW_DIR = ROOT / "app" / "src" / "main" / "resources" / "assets" / "icon"
WINDOW_SIZES = (16, 32, 48, 128)
ICO = ROOT / "app" / "packaging" / "AimTune.ico"
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)

log = logging.getLogger("make_icons")


def square(image: Image.Image) -> Image.Image:
    """Crops the transparent border and centres the tile on a transparent square."""
    tile = image.crop(image.getchannel("A").getbbox())
    side = max(tile.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(tile, ((side - tile.width) // 2, (side - tile.height) // 2))
    return canvas


def scaled(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    master = square(Image.open(MASTER).convert("RGBA"))
    WINDOW_DIR.mkdir(parents=True, exist_ok=True)
    for size in WINDOW_SIZES:
        path = WINDOW_DIR / f"aimtune-{size}.png"
        scaled(master, size).save(path, optimize=True)
        log.info("wrote %s", path.relative_to(ROOT))
    ICO.parent.mkdir(parents=True, exist_ok=True)
    scaled(master, max(ICO_SIZES)).save(ICO, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    log.info("wrote %s", ICO.relative_to(ROOT))


if __name__ == "__main__":
    main()
