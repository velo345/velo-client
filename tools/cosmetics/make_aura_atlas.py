#!/usr/bin/env python3
"""Draws the auras' texture atlas (textures/cosmetics/aura/atlas.png): an 8x4 grid of 128px cells, white
with the shape in alpha (the game tints it per particle).

    cell 0 glow, 1 star, 2 beam (soft across u), 3 heart, 4 leaf, 5 curtain (bright foot, fading up),
    8-23 code-rain glyphs (AuraEffects.GLYPHS, mirrored like the film)

    python3 tools/cosmetics/make_aura_atlas.py
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/cosmetics/aura/atlas.png"
C = 128
GLYPHS = "0123457ｱｶｻﾀﾅﾊﾏﾗﾜ"
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"


def cell(index):
    return (index % 8) * C, (index // 8) * C


def field(fn):
    img = Image.new("L", (C, C), 0)
    px = img.load()
    for y in range(C):
        for x in range(C):
            px[x, y] = max(0, min(255, int(fn((x + 0.5) / C * 2 - 1, (y + 0.5) / C * 2 - 1) * 255)))
    return img


def glow(dx, dy):
    d = math.hypot(dx, dy) / 0.97
    return 0 if d >= 1 else (math.exp(-d * d * 9) * 0.85 + math.exp(-d * d * 2.5) * 0.35) * (1 - d) ** 0.5


def star(dx, dy):
    d = math.hypot(dx, dy)
    if d >= 0.97:
        return 0
    ax, ay = abs(dx), abs(dy)
    ray = max(math.exp(-ay * ay * 900) * (1 - ax), math.exp(-ax * ax * 900) * (1 - ay))
    diag = math.exp(-((ax - ay) ** 2) * 600) * (1 - d) * 0.35 if d < 0.7 else 0
    return min(1, ray * 0.95 + diag + math.exp(-d * d * 40))


def beam(dx, dy):
    return math.exp(-dx * dx * 6) if abs(dx) < 0.96 else 0


def curtain(dx, dy):
    # v = 1 at the bottom (dy = 1): a bright foot fading upwards, soft side edges so segments blend.
    v = (dy + 1) / 2
    side = math.exp(-(max(0, abs(dx) - 0.55) ** 2) * 40)
    return (v ** 2.2 * 0.75 + math.exp(-((1 - v) ** 2) * 220) * 0.6) * side


def shape(draw_fn, blur=1.2):
    """A filled shape, drawn big and scaled down (smooth edges), plus a soft halo."""
    big = Image.new("L", (C * 4, C * 4), 0)
    draw_fn(ImageDraw.Draw(big), C * 4)
    small = big.resize((C, C), Image.LANCZOS)
    halo = small.filter(ImageFilter.GaussianBlur(7)).point(lambda a: a * 0.55)
    return Image.composite(small, halo, small).filter(ImageFilter.GaussianBlur(blur * 0.4))


def heart(d, s):
    pts = []
    for i in range(200):
        t = i / 200 * 2 * math.pi
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((s / 2 + x * s / 40, s / 2 - y * s / 40 - s * 0.03))
    d.polygon(pts, fill=255)


def leaf(d, s):
    pts = []
    for i in range(200):
        t = i / 200 * 2 * math.pi
        x = math.sin(t) * 0.36 * (1 - 0.35 * math.cos(t))
        y = math.cos(t) * 0.86
        pts.append((s / 2 + x * s / 2, s / 2 - y * s / 2))
    d.polygon(pts, fill=255)
    d.line([(s / 2, s * 0.1), (s / 2, s * 0.92)], fill=120, width=int(s * 0.02))


def glyph(ch):
    img = Image.new("L", (C, C), 0)
    font = ImageFont.truetype(FONT, 96)
    d = ImageDraw.Draw(img)
    box = d.textbbox((0, 0), ch, font=font)
    d.text(((C - (box[2] - box[0])) / 2 - box[0], (C - (box[3] - box[1])) / 2 - box[1]), ch, font=font, fill=255)
    img = img.transpose(Image.FLIP_LEFT_RIGHT)
    halo = img.filter(ImageFilter.GaussianBlur(5)).point(lambda a: a * 0.5)
    return Image.composite(img, halo, img)


def main():
    atlas = Image.new("L", (C * 8, C * 4), 0)
    cells = {0: field(glow), 1: field(star), 2: field(beam), 3: shape(heart), 4: shape(leaf), 5: field(curtain)}
    for i, ch in enumerate(GLYPHS):
        cells[8 + i] = glyph(ch)
    for index, img in cells.items():
        atlas.paste(img, cell(index))
    rgba = Image.merge("RGBA", (Image.new("L", atlas.size, 255),) * 3 + (atlas,))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    rgba.save(OUT, optimize=True)
    print("wrote", OUT.relative_to(ROOT), rgba.size)


if __name__ == "__main__":
    main()
