#!/usr/bin/env python3
"""Draws the emote effects' texture atlas (textures/cosmetics/emote/fx.png): an 8x4 grid of 128 px cells.

Two kinds of cell (EmoteFx.SOLID says which):
  glow  - white, the shape in alpha; drawn additively (sparks, rings, beams...), tinted per particle
  solid - white fill with a dark outline; drawn alpha-blended so it reads on snow and sky alike (smoke, bats,
          ghosts, letters, confetti...). The tint multiplies the fill and leaves the outline dark.

    python3 tools/cosmetics/emotes/make_emote_atlas.py
"""
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/cosmetics/emote/fx.png"
C = 128
S = C * 4  # drawn big, scaled down: smooth edges
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
OUTLINE = (34, 30, 44)

# index -> name, in EmoteFx's order
CELLS = ["glow", "star", "beam", "heart", "note", "notes", "sparkle", "smoke", "ring", "snowflake", "bat", "ghost", "Z", "drop",
         "flame", "square", "skull", "bolt", "anger", "swirl", "candy", "bubble", "L", "Y", "M", "C", "A", "G", "O", "!", "?", "X"]
SOLID = {"heart", "note", "notes", "smoke", "bat", "ghost", "Z", "drop", "square", "skull", "anger", "swirl", "candy", "L", "Y", "M",
         "C", "A", "G", "O", "!", "?", "X"}


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
    ray = max(math.exp(-ay * ay * 700) * (1 - ax), math.exp(-ax * ax * 700) * (1 - ay))
    return min(1, ray + math.exp(-d * d * 30) * 0.9)


def sparkle(dx, dy):
    d = math.hypot(dx, dy)
    if d >= 0.97:
        return 0
    a = math.atan2(dy, dx)
    rays = sum(math.exp(-((math.sin(a - k * math.pi / 4) * d) ** 2) * (1400 if k % 2 == 0 else 2600)) * (1 - d) ** (1 if k % 2 == 0 else 2)
               for k in range(4))
    return min(1, rays * 0.9 + math.exp(-d * d * 45))


def beam(dx, dy):
    return math.exp(-dx * dx * 6) if abs(dx) < 0.96 else 0


def ring(dx, dy):
    d = math.hypot(dx, dy)
    return math.exp(-((d - 0.8) ** 2) * 220) + 0.25 * math.exp(-((d - 0.8) ** 2) * 30) * (d < 0.8)


def bubble(dx, dy):
    d = math.hypot(dx, dy)
    rim = math.exp(-((d - 0.78) ** 2) * 500)
    shine = math.exp(-((dx + 0.32) ** 2 + (dy + 0.32) ** 2) * 60) * 0.9
    return min(1, rim + shine + 0.08 * (d < 0.78))


def flame(d, s):
    """A teardrop flame: a soft outer body and a hot core (glow cell, so brightness is alpha)."""
    def tear(scale, fill):
        cx, cy, r = 0.5, 0.64, 0.3 * scale
        d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=fill)
        top = cy - r * 3.0
        d.polygon([(cx * s, top * s), ((cx - r * 0.97) * s, (cy - r * 0.25) * s), ((cx + r * 0.97) * s, (cy - r * 0.25) * s)], fill=fill)
    tear(1.0, 150)
    tear(0.55, 255)


def snowflake(d, s):
    c = s / 2
    w = int(s * 0.045)
    for k in range(6):
        a = k * math.pi / 3
        ex, ey = c + math.cos(a) * s * 0.44, c + math.sin(a) * s * 0.44
        d.line([(c, c), (ex, ey)], fill=255, width=w)
        for f in (0.45, 0.7):
            bx, by = c + math.cos(a) * s * 0.44 * f, c + math.sin(a) * s * 0.44 * f
            for side in (-1, 1):
                b = a + side * math.pi / 4
                d.line([(bx, by), (bx + math.cos(b) * s * 0.12, by + math.sin(b) * s * 0.12)], fill=255, width=w)
    d.ellipse([c - s * 0.06, c - s * 0.06, c + s * 0.06, c + s * 0.06], fill=255)


def bolt(d, s):
    pts = [(0.58, 0.02), (0.22, 0.52), (0.46, 0.52), (0.36, 0.98), (0.8, 0.4), (0.55, 0.4), (0.72, 0.02)]
    d.polygon([(x * s, y * s) for x, y in pts], fill=255)


def heart(d, s):
    pts = []
    for i in range(200):
        t = i / 200 * 2 * math.pi
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((s / 2 + x * s / 38, s / 2 - y * s / 38 - s * 0.03))
    d.polygon(pts, fill=255)


def note(d, s, double=False):
    w = int(s * 0.07)
    if double:
        heads = [(0.3, 0.78), (0.72, 0.68)]
        for hx, hy in heads:
            d.ellipse([(hx - 0.13) * s, (hy - 0.09) * s, (hx + 0.13) * s, (hy + 0.09) * s], fill=255)
            d.line([((hx + 0.11) * s, hy * s), ((hx + 0.11) * s, (hy - 0.55) * s)], fill=255, width=w)
        d.polygon([(0.41 * s, 0.2 * s), (0.83 * s, 0.1 * s), (0.83 * s, 0.22 * s), (0.41 * s, 0.32 * s)], fill=255)
    else:
        d.ellipse([0.22 * s, 0.62 * s, 0.5 * s, 0.84 * s], fill=255)
        d.line([(0.48 * s, 0.72 * s), (0.48 * s, 0.12 * s)], fill=255, width=w)
        d.polygon([(0.48 * s, 0.12 * s), (0.78 * s, 0.32 * s), (0.72 * s, 0.42 * s), (0.48 * s, 0.26 * s)], fill=255)


def smoke(d, s):
    for cx, cy, r in ((0.5, 0.55, 0.3), (0.32, 0.6, 0.2), (0.68, 0.6, 0.22), (0.42, 0.38, 0.2), (0.6, 0.4, 0.18)):
        d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=255)


def bat(d, s):
    pts = [(0.5, 0.42), (0.6, 0.36), (0.66, 0.24), (0.74, 0.34), (0.98, 0.3), (0.86, 0.46), (0.9, 0.62), (0.76, 0.54), (0.66, 0.66),
           (0.56, 0.56), (0.5, 0.66), (0.44, 0.56), (0.34, 0.66), (0.24, 0.54), (0.1, 0.62), (0.14, 0.46), (0.02, 0.3), (0.26, 0.34),
           (0.34, 0.24), (0.4, 0.36)]
    d.polygon([(x * s, y * s) for x, y in pts], fill=255)
    d.polygon([(0.43 * s, 0.4 * s), (0.45 * s, 0.3 * s), (0.48 * s, 0.4 * s)], fill=255)
    d.polygon([(0.52 * s, 0.4 * s), (0.55 * s, 0.3 * s), (0.57 * s, 0.4 * s)], fill=255)


def ghost(d, s):
    d.ellipse([0.2 * s, 0.08 * s, 0.8 * s, 0.68 * s], fill=255)
    d.rectangle([0.2 * s, 0.38 * s, 0.8 * s, 0.8 * s], fill=255)
    for k in range(4):
        x0 = 0.2 + k * 0.15
        d.ellipse([x0 * s, 0.72 * s, (x0 + 0.15) * s, 0.92 * s], fill=255)


def ghost_face(d, s):
    d.ellipse([0.34 * s, 0.3 * s, 0.44 * s, 0.44 * s], fill=0)
    d.ellipse([0.56 * s, 0.3 * s, 0.66 * s, 0.44 * s], fill=0)
    d.ellipse([0.44 * s, 0.5 * s, 0.56 * s, 0.64 * s], fill=0)


def skull(d, s):
    d.ellipse([0.18 * s, 0.1 * s, 0.82 * s, 0.7 * s], fill=255)
    d.rounded_rectangle([0.32 * s, 0.55 * s, 0.68 * s, 0.88 * s], radius=int(0.05 * s), fill=255)


def skull_face(d, s):
    d.ellipse([0.28 * s, 0.32 * s, 0.46 * s, 0.52 * s], fill=0)
    d.ellipse([0.54 * s, 0.32 * s, 0.72 * s, 0.52 * s], fill=0)
    d.polygon([(0.5 * s, 0.54 * s), (0.45 * s, 0.64 * s), (0.55 * s, 0.64 * s)], fill=0)
    for k in range(3):
        x = 0.4 + k * 0.1
        d.line([(x * s, 0.74 * s), (x * s, 0.88 * s)], fill=0, width=int(0.025 * s))


def drop(d, s):
    d.ellipse([0.28 * s, 0.4 * s, 0.72 * s, 0.86 * s], fill=255)
    d.polygon([(0.5 * s, 0.06 * s), (0.29 * s, 0.6 * s), (0.71 * s, 0.6 * s)], fill=255)


def square(d, s):
    d.rectangle([0.2 * s, 0.3 * s, 0.8 * s, 0.7 * s], fill=255)


def anger(d, s):
    """The anime anger mark: four arcs bowing in towards the middle."""
    w = int(s * 0.1)
    for cx, cy, start in ((0, 0, 0), (1, 0, 90), (1, 1, 180), (0, 1, 270)):
        r = 0.36
        d.arc([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], start=start + 8, end=start + 82, fill=255, width=w)


def swirl(d, s):
    w = s * 0.035
    for i in range(1200):
        t = i / 1200 * 4 * math.pi
        r = 0.04 + 0.36 * i / 1200
        x, y = s / 2 + math.cos(t) * r * s, s / 2 + math.sin(t) * r * s
        d.ellipse([x - w, y - w, x + w, y + w], fill=255)


def candy(d, s):
    w = int(s * 0.16)
    d.arc([0.3 * s, 0.08 * s, 0.7 * s, 0.48 * s], start=180, end=360, fill=255, width=w)
    d.line([(0.62 * s, 0.28 * s), (0.62 * s, 0.94 * s)], fill=255, width=w)


def candy_stripes(d, s):
    for k in range(6):
        y = 0.3 + k * 0.11
        d.line([(0.52 * s, (y + 0.05) * s), (0.72 * s, (y - 0.03) * s)], fill=0, width=int(s * 0.04))


def drawn(fn, extra=None):
    big = Image.new("L", (S, S), 0)
    fn(ImageDraw.Draw(big), S)
    hole = None
    if extra is not None:
        hole = Image.new("L", (S, S), 255)
        extra(ImageDraw.Draw(hole), S)
    return big, hole


def letter(ch):
    big = Image.new("L", (S, S), 0)
    font = ImageFont.truetype(FONT, int(S * 0.78))
    d = ImageDraw.Draw(big)
    box = d.textbbox((0, 0), ch, font=font)
    d.text(((S - (box[2] - box[0])) / 2 - box[0], (S - (box[3] - box[1])) / 2 - box[1]), ch, font=font, fill=255)
    return big, None


def solid_cell(big, hole):
    """White fill, dark outline, alpha = the shape plus the outline."""
    k = max(3, int(S * 0.045)) | 1
    outline = big.filter(ImageFilter.MaxFilter(k)).filter(ImageFilter.MaxFilter(k))
    fill = big if hole is None else ImageChops.multiply(big, hole)
    rgb = Image.new("RGB", (S, S), OUTLINE)
    rgb.paste((255, 255, 255), mask=fill)
    alpha = outline
    img = Image.merge("RGBA", (*rgb.split(), alpha)).resize((C, C), Image.LANCZOS)
    return img


def glow_cell(alpha):
    if alpha.size != (C, C):
        alpha = alpha.resize((C, C), Image.LANCZOS)
        halo = alpha.filter(ImageFilter.GaussianBlur(6)).point(lambda a: a * 0.5)
        alpha = ImageChops.lighter(alpha, halo)
    return Image.merge("RGBA", (Image.new("L", (C, C), 255),) * 3 + (alpha,))


def main():
    sources = {
        "glow": field(glow), "star": field(star), "beam": field(beam), "sparkle": field(sparkle), "ring": field(ring),
        "bubble": field(bubble), "flame": drawn(flame)[0].filter(ImageFilter.GaussianBlur(S * 0.02)),
        "snowflake": drawn(snowflake)[0], "bolt": drawn(bolt)[0],
        "heart": drawn(heart), "note": drawn(note), "notes": drawn(lambda d, s: note(d, s, True)), "smoke": drawn(smoke),
        "bat": drawn(bat), "ghost": drawn(ghost, ghost_face), "skull": drawn(skull, skull_face), "drop": drawn(drop),
        "square": drawn(square), "anger": drawn(anger), "swirl": drawn(swirl), "candy": drawn(candy, candy_stripes),
    }
    for ch in "ZLYMCAGO!?X":
        sources[ch] = letter(ch)
    atlas = Image.new("RGBA", (C * 8, C * 4), (0, 0, 0, 0))
    for index, name in enumerate(CELLS):
        src = sources[name]
        img = solid_cell(*src) if name in SOLID else glow_cell(src)
        atlas.paste(img, ((index % 8) * C, (index // 8) * C))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(OUT, optimize=True)
    print("wrote", OUT.relative_to(ROOT), atlas.size)
    # what EmoteFx needs to know
    print("SOLID =", [i for i, n in enumerate(CELLS) if n in SOLID])


if __name__ == "__main__":
    main()
