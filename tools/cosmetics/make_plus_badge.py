#!/usr/bin/env python3
"""Draws the Velo+ badge: the Velo logo with a red plus behind it, 192 x 128 (the logo cell plus a
64 px plus cell) -> textures/font/badge_plus_glyph.png (a chat / nametag font glyph, and drawn directly in
the tab list)."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/font/badge_plus_glyph.png"


def main():
    logo = Image.open(ROOT / "Logo.png").convert("RGBA").resize((128, 128), Image.LANCZOS)
    big = Image.new("RGBA", (192 * 4, 128 * 4), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    cx, cy, arm, thick = 160 * 4, 64 * 4, 24 * 4, 15 * 4
    for outline, colour, grow in (((70, 6, 12, 255), None, 5 * 4), (None, (255, 45, 60, 255), 0)):
        fill = outline or colour
        d.rounded_rectangle([cx - arm - grow, cy - thick / 2 - grow, cx + arm + grow, cy + thick / 2 + grow], radius=6 * 4, fill=fill)
        d.rounded_rectangle([cx - thick / 2 - grow, cy - arm - grow, cx + thick / 2 + grow, cy + arm + grow], radius=6 * 4, fill=fill)
    # A lighter top edge on the red.
    d.rounded_rectangle([cx - arm + 3 * 4, cy - thick / 2 + 2 * 4, cx + arm - 3 * 4, cy - 2 * 4], radius=4 * 4, fill=(255, 130, 140, 255))
    plus = big.resize((192, 128), Image.LANCZOS)
    out = Image.new("RGBA", (192, 128), (0, 0, 0, 0))
    out.alpha_composite(plus)
    out.alpha_composite(logo, (0, 0))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    out.save(OUT, optimize=True)
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
