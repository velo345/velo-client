#!/usr/bin/env python3
"""Draws the auras' texture atlas (textures/cosmetics/aura/atlas.png): an 8x8 grid of 256px cells (rows 7-8 partly free), white
with the shape in alpha (the game tints it per particle). Every cell fades to nothing before its border,
so the linear filtering (atlas.png.mcmeta) never bleeds a neighbour in.

    cell 0 glow, 1 star, 2 beam (soft across u), 3 heart, 4 leaf, 5 curtain (bright foot, fading up),
    6 halo (thin ring), 7 smoke puff, 8-23 code-rain glyphs (AuraEffects.GLYPHS, mirrored like the film),
    24 feather (tip up), 25 sakura petal, 26 crystal shard, 27 flame tongue, 28 sparkle,
    29 butterfly wing (hinge on the left edge, front at the top), 30 ribbon profile (bright edges across u),
    31 yin-yang (the light half solid, the dark half faint, inside a bright rim),
    32 koi (seen from above, head up), 33 sword (tip up), 34 paper lantern, 35 gear, 36 lily pad, 37 clock dial,
    38 pixel (a soft-edged square), 39 slash (a crescent, its bright edge outwards), 40 ghost, 41 starburst (rays),
    42 ground cracks, 43 tombstone (R.I.P.), 44 coin, 45 bird wing (hinge on the left edge, leading edge at the top,
    feathers fanning out to the right and down), 46 crescent moon (horns to the right), 47 sleepy "Z",
    48 isometric lucky block body, 49 its "?" marks and corner studs (drawn on top of 48)
    - the kill effects (cosmetics/killfx) draw with these too

    python3 tools/cosmetics/make_aura_atlas.py
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/cosmetics/aura/atlas.png"
C = 256
GLYPHS = "0123457ｱｶｻﾀﾅﾊﾏﾗﾜ"
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"

# Pixel centres in -1..1; Y grows downwards (image rows), U = -Y is up.
_axis = (np.arange(C) + 0.5) / C * 2 - 1
X, Y = np.meshgrid(_axis, _axis)
U = -Y
D = np.hypot(X, Y)


def cell(index):
    return (index % 8) * C, (index // 8) * C


def to_image(a):
    return Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8), "L")


def border_fade(a, inner=0.9):
    """Fades a field to zero towards the cell's border (no bleed under linear filtering)."""
    edge = np.maximum(np.abs(X), np.abs(Y))
    return a * np.clip((0.985 - edge) / (0.985 - inner), 0, 1)


def smooth(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0, 1)
    return t * t * (3 - 2 * t)


def with_halo(img, radius, strength):
    halo = img.filter(ImageFilter.GaussianBlur(radius)).point(lambda a: a * strength)
    return Image.composite(img, halo, img)


# ---- The original shapes, redrawn at the higher resolution ----

def glow():
    d = D / 0.97
    a = (np.exp(-d * d * 9) * 0.85 + np.exp(-d * d * 2.5) * 0.35) * np.sqrt(np.clip(1 - d, 0, 1))
    return to_image(np.where(d >= 1, 0, a))


def star():
    ax, ay = np.abs(X), np.abs(Y)
    ray = np.maximum(np.exp(-ay * ay * 1800) * (1 - ax), np.exp(-ax * ax * 1800) * (1 - ay))
    diag = np.where(D < 0.7, np.exp(-((ax - ay) ** 2) * 1200) * (1 - D) * 0.35, 0)
    a = np.minimum(1, ray * 0.95 + diag + np.exp(-D * D * 40))
    return to_image(border_fade(np.where(D >= 0.97, 0, a)))


def beam():
    # Constant along v, so it may run to the top and bottom edge; u still fades out before the border.
    a = np.where(np.abs(X) < 0.96, np.exp(-X * X * 6), 0)
    return to_image(a * np.clip((0.985 - np.abs(Y)) / 0.03, 0, 1))


def curtain():
    v = (Y + 1) / 2  # 1 at the bottom
    side = np.exp(-(np.maximum(0, np.abs(X) - 0.55) ** 2) * 40)
    a = (v ** 2.2 * 0.75 + np.exp(-((0.97 - v) ** 2) * 220) * 0.6) * side
    return to_image(border_fade(a, 0.94))


def shape(draw_fn, blur=1.2):
    """A filled shape, drawn big and scaled down (smooth edges), plus a soft halo."""
    big = Image.new("L", (C * 4, C * 4), 0)
    draw_fn(ImageDraw.Draw(big), C * 4)
    small = big.resize((C, C), Image.LANCZOS)
    return with_halo(small, 14, 0.55).filter(ImageFilter.GaussianBlur(blur * 0.8))


def heart(d, s):
    pts = []
    for i in range(400):
        t = i / 400 * 2 * math.pi
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((s / 2 + x * s / 40, s / 2 - y * s / 40 - s * 0.03))
    d.polygon(pts, fill=255)


def leaf(d, s):
    pts = []
    for i in range(400):
        t = i / 400 * 2 * math.pi
        x = math.sin(t) * 0.36 * (1 - 0.35 * math.cos(t))
        y = math.cos(t) * 0.86
        pts.append((s / 2 + x * s / 2, s / 2 - y * s / 2))
    d.polygon(pts, fill=255)
    d.line([(s / 2, s * 0.1), (s / 2, s * 0.92)], fill=120, width=int(s * 0.02))


def glyph(ch):
    img = Image.new("L", (C, C), 0)
    font = ImageFont.truetype(FONT, 184)
    d = ImageDraw.Draw(img)
    box = d.textbbox((0, 0), ch, font=font)
    d.text(((C - (box[2] - box[0])) / 2 - box[0], (C - (box[3] - box[1])) / 2 - box[1]), ch, font=font, fill=255)
    img = img.transpose(Image.FLIP_LEFT_RIGHT)
    return with_halo(img, 10, 0.5)


# ---- New shapes ----

def halo():
    """A thin bright ring with a soft glow either side (halos, shockwaves)."""
    r = 0.74
    a = np.exp(-(((D - r) / 0.03) ** 2)) + 0.45 * np.exp(-(((D - r) / 0.13) ** 2)) + 0.08 * np.exp(-((D / 0.6) ** 2))
    return to_image(border_fade(np.where(D >= 0.97, 0, np.minimum(1, a))))


def value_noise(freq, seed):
    rng = np.random.default_rng(seed)
    grid = rng.random((freq + 1, freq + 1))
    gx = (X + 1) / 2 * freq
    gy = (Y + 1) / 2 * freq
    x0 = np.clip(np.floor(gx).astype(int), 0, freq - 1)
    y0 = np.clip(np.floor(gy).astype(int), 0, freq - 1)
    fx = gx - x0
    fy = gy - y0
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    top = grid[y0, x0] * (1 - fx) + grid[y0, x0 + 1] * fx
    bottom = grid[y0 + 1, x0] * (1 - fx) + grid[y0 + 1, x0 + 1] * fx
    return top * (1 - fy) + bottom * fy


def smoke():
    """A soft, billowy puff: a few octaves of noise inside a round falloff."""
    n = sum(value_noise(f, 7 + f) * w for f, w in ((3, 0.5), (6, 0.28), (12, 0.14), (24, 0.08)))
    body = np.clip(1 - D / 0.92, 0, 1) ** 1.3
    a = body * smooth(0.25, 0.85, n + body * 0.45) * 0.95
    return to_image(border_fade(a))


def feather():
    """A feather, tip at the top: an uneven vane of fine barbs either side of a bright quill."""
    y = U
    t = np.clip((y + 0.9) / 1.82, 0, 1)
    # The vane: widest just below the middle, a rounded tip, narrowing to the quill at the bottom.
    width = 0.4 * np.sin(np.pi * t) ** 0.7 * (1 - 0.25 * t)
    curve = 0.06 * np.sin(t * np.pi)  # a slight bend
    x = X - curve
    side = np.where(x < 0, 1.0, 0.78)
    inside = smooth(0.0, 0.035, width * side - np.abs(x))
    # Barbs: fine lines slanting up and out, and a few gaps where the vane has split.
    barbs = 0.72 + 0.28 * np.sin((y - np.abs(x) * 1.1) * 150)
    splits = 1 - 0.6 * smooth(0.985, 0.999, np.sin((y + np.abs(x) * 1.1) * 9 + np.sign(x) * 1.3)) * smooth(0.45, 0.8, np.abs(x) / np.maximum(width, 1e-3))
    downy = np.where(t < 0.22, 0.55 + 0.45 * t / 0.22, 1)  # the fluffy, fainter base
    vane = inside * barbs * splits * downy * 0.82
    quill = np.exp(-((x / 0.018) ** 2)) * ((y > -0.97) & (y < 0.86))
    a = np.maximum(vane, quill)
    img = with_halo(to_image(a), 6, 0.45)
    return to_image(border_fade(np.asarray(img) / 255.0))


def petal():
    """A cherry-blossom petal: a soft rounded teardrop with the notch at its tip, bright edge, fine veins."""
    y = U
    t = np.clip((y + 0.88) / 1.7, 0, 1)
    width = 0.62 * np.sin(np.pi * np.clip(t * 0.92 + 0.04, 0, 1)) ** 0.85 * (0.55 + 0.45 * t)
    notch = 0.16 * np.exp(-((X / 0.11) ** 2)) * smooth(0.7, 1.0, t)
    inside = smooth(0.0, 0.04, width - np.abs(X)) * smooth(0.0, 0.03, 0.82 - notch - y)
    edge = 1 - smooth(0.0, 0.22, np.minimum(width - np.abs(X), 0.82 - notch - y))
    veins = 0.9 + 0.1 * np.cos(np.arctan2(X, y + 0.95) * 46)
    a = inside * (0.62 + 0.38 * edge) * veins
    img = with_halo(to_image(a), 8, 0.45)
    return to_image(border_fade(np.asarray(img) / 255.0))


def shard():
    """A crystal: a long hexagonal point top and bottom, its facets lit differently, bright edges."""
    w = 0.3
    tip = 0.95
    shoulder = 0.5
    ax = np.abs(X)
    ay = np.abs(U)
    # Width at each height: straight sides, then the points.
    width = np.where(ay < shoulder, w, w * np.clip((tip - ay) / (tip - shoulder), 0, 1))
    inside = smooth(0.0, 0.02, width - ax)
    facet = np.where(X < -w * 0.33, 0.5, np.where(X > w * 0.33, 0.72, 0.95))
    facet = facet * np.where(U > shoulder, 1.15, 1.0)
    rim = np.exp(-(((width - ax) / 0.02) ** 2)) + np.exp(-(((ax - w * 0.33) / 0.012) ** 2)) * 0.6 * (ay < tip - 0.05)
    glint = np.exp(-(((X - 0.02) ** 2 + (U - 0.3) ** 2) / 0.006)) * 0.7
    a = inside * (facet * 0.7 + rim * 0.5 + glint)
    img = with_halo(to_image(a), 10, 0.5)
    return to_image(border_fade(np.asarray(img) / 255.0))


def flame():
    """A flame tongue, tip up: a bright round root narrowing into a flickering point."""
    y = U
    t = np.clip((y + 0.82) / 1.75, 0, 1)
    width = 0.44 * np.sin(np.pi * np.clip(t ** 0.6, 0, 1)) * (1 - t) ** 0.35
    sway = 0.07 * np.sin(t * 5.5) * t
    x = X - sway
    inside = smooth(0.0, 0.12, width - np.abs(x))
    core = np.exp(-((x / 0.16) ** 2 + ((y + 0.42) / 0.36) ** 2))
    a = inside * (0.55 + 0.45 * (1 - t)) + core * 0.5
    img = with_halo(to_image(np.minimum(1, a)), 12, 0.55)
    return to_image(border_fade(np.asarray(img) / 255.0))


def sparkle():
    """A crisp lens sparkle: four long rays, four short diagonal ones, a hot core and a faint bloom."""
    ax, ay = np.abs(X), np.abs(Y)
    long_ray = np.maximum(np.exp(-((ay / 0.012) ** 2)) * (1 - ax) ** 2.2, np.exp(-((ax / 0.012) ** 2)) * (1 - ay) ** 2.2)
    dx = (X + Y) / math.sqrt(2)
    dy = (X - Y) / math.sqrt(2)
    short_ray = np.maximum(np.exp(-((dy / 0.01) ** 2)) * np.clip(1 - np.abs(dx) / 0.5, 0, 1) ** 2,
                           np.exp(-((dx / 0.01) ** 2)) * np.clip(1 - np.abs(dy) / 0.5, 0, 1) ** 2) * 0.6
    core = np.exp(-((D / 0.07) ** 2))
    bloom = np.exp(-((D / 0.32) ** 2)) * 0.28
    return to_image(border_fade(np.minimum(1, long_ray + short_ray + core + bloom)))


def wing():
    """A butterfly's wing, hinge on the left edge, front at the top: big forewing, smaller hindwing,
    a bright rim, veins fanning out from the hinge and two eye spots."""
    hx, hy = -0.92, 0.05

    def ellipse(cx, cy, rx, ry, angle):
        c, s = math.cos(angle), math.sin(angle)
        px, py = X - cx, U - cy
        qx = (px * c + py * s) / rx
        qy = (-px * s + py * c) / ry
        return np.hypot(qx, qy)

    fore = ellipse(-0.12, 0.36, 0.84, 0.5, math.radians(22))
    hind = ellipse(-0.38, -0.42, 0.6, 0.44, math.radians(-28))
    e = np.minimum(fore, hind)
    inside = smooth(1.0, 0.94, e)
    rim = np.exp(-(((e - 0.93) / 0.045) ** 2))
    ang = np.arctan2(U - hy, X - hx)
    dist = np.hypot(X - hx, U - hy)
    veins = np.exp(-((np.sin(ang * 7.5) / 0.09) ** 2)) * smooth(0.12, 0.4, dist) * 0.5
    spots = np.exp(-(((X - 0.32) ** 2 + (U - 0.48) ** 2) / 0.012)) + 0.8 * np.exp(-(((X + 0.12) ** 2 + (U + 0.5) ** 2) / 0.008))
    body_fade = smooth(-1.0, -0.82, X)  # don't touch the hinge edge itself
    a = inside * (0.42 + 0.5 * rim + veins + 0.55 * spots) * body_fade
    img = with_halo(to_image(np.minimum(1, a)), 8, 0.5)
    return to_image(border_fade(np.asarray(img) / 255.0, 0.94))


def ribbon():
    """A silk band seen flat: bright hems along both edges, a faint sheen in between (u across, constant along v)."""
    ax = np.abs(X)
    a = np.exp(-(((ax - 0.8) / 0.07) ** 2)) * 0.95 + 0.35 * np.exp(-((X / 0.75) ** 2)) + 0.25 * np.exp(-(((ax - 0.35) / 0.12) ** 2))
    a = np.where(ax < 0.96, np.minimum(1, a), 0)
    return to_image(a * np.clip((0.985 - np.abs(Y)) / 0.03, 0, 1))


def taiji():
    """The yin-yang symbol: its light half (with the dark half's dot cut out) solid, the dark half faint, a bright rim."""
    big = 0.8
    small = big / 2
    upper = np.hypot(X, U - small) < small
    lower = np.hypot(X, U + small) < small
    light = lower | ((X > 0) & ~upper)
    light = np.where(np.hypot(X, U - small) < big / 7, True, light)
    light = np.where(np.hypot(X, U + small) < big / 7, False, light)
    inside = D < big
    a = np.where(inside, np.where(light, 0.95, 0.12), 0)
    a = a + np.exp(-(((D - big) / 0.025) ** 2)) * 0.9
    img = to_image(np.minimum(1, a)).resize((C * 2, C * 2), Image.BICUBIC).filter(ImageFilter.GaussianBlur(1.5)).resize((C, C), Image.LANCZOS)
    img = with_halo(img, 8, 0.4)
    return to_image(border_fade(np.asarray(img) / 255.0))


def polygon_mask(points, blur=1.5):
    """A filled polygon from points in -1..1 (y up), drawn big and scaled down for smooth edges."""
    big = Image.new("L", (C * 4, C * 4), 0)
    ImageDraw.Draw(big).polygon([((x + 1) / 2 * C * 4, (1 - y) / 2 * C * 4) for x, y in points], fill=255)
    return np.asarray(big.resize((C, C), Image.LANCZOS).filter(ImageFilter.GaussianBlur(blur))) / 255.0


def koi():
    """A koi seen from above, head at the top: a plump body, flowing forked tail, pectoral fins, patches."""
    y = U
    t = np.clip((0.7 - y) / 1.12, 0, 1)  # 0 at the nose, 1 where the body meets the tail
    width = 0.19 * np.sin(np.pi * np.clip(t * 0.82, 0, 1)) ** 0.55 * (1 - 0.35 * t) * (y > -0.44)
    body = smooth(0.0, 0.025, width - np.abs(X))
    # The tail: two soft lobes fanning out below the body.
    ty = -0.4 - y
    tail_w = 0.06 + ty * 0.55
    lobes = np.exp(-(((np.abs(X) - ty * 0.32) / np.maximum(0.05, tail_w * 0.45)) ** 2))
    tail = (ty > 0) * (ty < 0.52) * lobes * (1 - smooth(0.35, 0.52, ty)) * 0.75
    fin = lambda sx: np.exp(-(((X - sx * 0.24) / 0.09) ** 2 + ((y - 0.18 + np.abs(X) * 0.5) / 0.06) ** 2))
    fins = (fin(1) + fin(-1)) * 0.6
    patches = 0.72 + 0.28 * smooth(0.45, 0.6, value_noise(5, 3))
    eyes = np.exp(-(((np.abs(X) - 0.085) ** 2 + (y - 0.5) ** 2) / 0.0010))
    a = np.maximum(body * patches, np.maximum(tail, fins)) - body * eyes * 0.6
    img = with_halo(to_image(np.clip(a, 0, 1)), 6, 0.45)
    return to_image(border_fade(np.asarray(img) / 255.0))


def sword():
    """A spectral sword, tip at the top: a ridged blade with bright edges, crossguard, grip and pommel."""
    y = U
    blade_w = np.where(y > 0.72, 0.085 * np.clip((0.97 - y) / 0.25, 0, 1), 0.085)
    blade = smooth(0.0, 0.012, blade_w - np.abs(X)) * (y > -0.38) * (y < 0.97)
    ridge = np.exp(-((X / 0.012) ** 2)) * 0.5
    edges = np.exp(-(((blade_w - np.abs(X)) / 0.012) ** 2)) * 0.5 * (y > -0.38)
    blade_a = blade * (0.6 + ridge + edges)
    guard = smooth(0.0, 0.015, 0.3 - np.abs(X) - np.abs(y + 0.43) * 1.5) * (np.abs(y + 0.43) < 0.05)
    grip = smooth(0.0, 0.01, 0.04 - np.abs(X)) * (y < -0.47) * (y > -0.82) * (0.7 + 0.3 * np.sin(y * 90))
    pommel = smooth(0.0, 0.015, 0.07 - np.hypot(X, y + 0.87))
    a = np.maximum.reduce([blade_a, guard * 0.95, grip * 0.8, pommel * 0.95])
    img = with_halo(to_image(np.minimum(1, a)), 10, 0.5)
    return to_image(border_fade(np.asarray(img) / 255.0))


def lantern():
    """A paper sky lantern: a body wider at the top, ribs, a dark rim at the top and the flame glowing low inside."""
    y = U
    t = np.clip((y + 0.62) / 1.3, 0, 1)
    width = 0.3 + 0.12 * np.sin(np.pi * t) ** 0.6 * (0.6 + 0.4 * t)
    inside = smooth(0.0, 0.02, width - np.abs(X)) * smooth(0.0, 0.02, 0.68 - y) * smooth(0.0, 0.02, y + 0.62)
    ribs = 0.85 + 0.15 * np.cos(X / np.maximum(width, 1e-3) * np.pi * 3)
    light = 0.45 + 0.55 * np.exp(-((X / 0.3) ** 2 + ((y + 0.35) / 0.45) ** 2))
    rim = np.where(y > 0.58, 0.55, 1.0)
    flame = np.exp(-((X / 0.05) ** 2 + ((y + 0.52) / 0.08) ** 2))
    a = np.maximum(inside * ribs * light * rim, flame)
    img = with_halo(to_image(np.minimum(1, a)), 14, 0.6)
    return to_image(border_fade(np.asarray(img) / 255.0))


def gear():
    """A gear: twelve teeth, a rim, four spokes and a hub."""
    ang = np.arctan2(U, X)
    teeth = 0.78 + 0.1 * (np.cos(ang * 12) > 0.15)
    outer = smooth(0.0, 0.02, teeth - D)
    ring = outer * smooth(0.0, 0.02, D - 0.6)
    spokes = np.maximum(smooth(0.0, 0.015, 0.06 - np.abs(np.sin(ang * 2) * D)), 0) * (D < 0.62)
    hub = smooth(0.0, 0.02, 0.2 - D) * smooth(0.0, 0.02, D - 0.09)
    a = np.maximum.reduce([ring, spokes * 0.85, hub]) * 0.9
    img = with_halo(to_image(np.minimum(1, a)), 8, 0.45)
    return to_image(border_fade(np.asarray(img) / 255.0))


def lily():
    """A lily pad: a round leaf with a notch cut to its middle, veins fanning out, a brighter rim."""
    ang = np.arctan2(U, X)
    notch = np.abs(np.angle(np.exp(1j * (ang - np.pi / 2)))) < 0.22
    inside = smooth(0.0, 0.02, 0.86 - D) * ~notch
    veins = 0.8 + 0.2 * np.exp(-((np.sin(ang * 9) / 0.12) ** 2)) * (D > 0.1)
    rim = np.exp(-(((0.86 - D) / 0.04) ** 2)) * 0.3
    a = inside * (0.55 * veins + rim)
    img = with_halo(to_image(np.minimum(1, a)), 6, 0.4)
    return to_image(border_fade(np.asarray(img) / 255.0))


def dial():
    """A clock dial: a double rim, sixty minute ticks, twelve long hour ticks with dots, a faint face."""
    ang = np.arctan2(U, X)
    rims = np.exp(-(((D - 0.9) / 0.012) ** 2)) + 0.7 * np.exp(-(((D - 0.84) / 0.008) ** 2)) + 0.5 * np.exp(-(((D - 0.5) / 0.008) ** 2))
    minute = (np.abs(np.sin(ang * 30)) < 0.06) * (D > 0.76) * (D < 0.83) * 0.7
    hour = (np.abs(np.sin(ang * 6)) < 0.03) * (np.cos(ang * 6) > 0) * (D > 0.62) * (D < 0.83)
    dots = np.zeros_like(D)
    for k in range(12):
        a = k * np.pi / 6
        dots += np.exp(-(((X - 0.56 * np.cos(a)) ** 2 + (U - 0.56 * np.sin(a)) ** 2) / 0.0006))
    face = 0.1 * (D < 0.9)
    a = np.minimum(1, rims + minute + hour + dots + face)
    img = to_image(a).resize((C * 2, C * 2), Image.BICUBIC).filter(ImageFilter.GaussianBlur(1.2)).resize((C, C), Image.LANCZOS)
    img = with_halo(img, 6, 0.4)
    return to_image(border_fade(np.asarray(img) / 255.0))


def pixel():
    """A small square with a bright core and a soft edge (glitch pixels, voxel debris)."""
    m = np.maximum(np.abs(X), np.abs(Y))
    a = smooth(0.0, 0.06, 0.62 - m) * (0.75 + 0.25 * smooth(0.0, 0.3, 0.62 - m)) + 0.12 * np.exp(-((D / 0.75) ** 2))
    return to_image(border_fade(np.minimum(1, a)))


def slash():
    """A crescent slash: a thin bright outer edge, tapering to points at both ends, fading inwards."""
    d1 = np.hypot(X, U + 0.35)
    ang = np.arctan2(U + 0.35, X)
    taper = np.clip(np.sin(np.clip((ang - 0.25) / (np.pi - 0.5), 0, 1) * np.pi), 0, 1) ** 0.8
    edge = np.exp(-(((d1 - 0.95) / 0.03) ** 2))
    inner = np.clip(1 - (0.95 - d1) / 0.42, 0, 1) ** 1.5 * (d1 < 0.95)
    a = (edge * 1.0 + inner * 0.7) * taper * (U + 0.35 > 0)
    img = with_halo(to_image(np.minimum(1, a)), 8, 0.5)
    return to_image(border_fade(np.asarray(img) / 255.0))


def ghost():
    """A little ghost: a round head, a body flowing into a wavy hem, two dark eyes."""
    y = U
    head = np.hypot(X, y - 0.25) < 0.42
    body = (np.abs(X) < 0.42) & (y < 0.25) & (y > -0.6 + 0.12 * np.sin(X * 14))
    shape = (head | body).astype(float)
    big = to_image(shape).resize((C * 2, C * 2), Image.BICUBIC).filter(ImageFilter.GaussianBlur(3)).resize((C, C), Image.LANCZOS)
    a = np.asarray(big) / 255.0
    fade = 0.55 + 0.45 * smooth(-0.6, 0.2, y)
    eyes = np.exp(-(((np.abs(X) - 0.15) ** 2) / 0.004 + ((y - 0.28) ** 2) / 0.012))
    a = np.clip(a * fade - eyes * 0.9, 0, 1)
    img = with_halo(to_image(a), 10, 0.5)
    return to_image(border_fade(np.asarray(img) / 255.0))


def starburst():
    """Light rays bursting out in every direction from a hot centre."""
    ang = np.arctan2(U, X)
    rays = np.zeros_like(D)
    rng = np.random.default_rng(5)
    for k in range(28):
        a0 = rng.random() * 2 * np.pi
        length = 0.45 + rng.random() * 0.5
        width = 0.012 + rng.random() * 0.02
        da = np.angle(np.exp(1j * (ang - a0)))
        rays = np.maximum(rays, np.exp(-((da * D / width) ** 2)) * np.clip(1 - D / length, 0, 1) ** 1.5)
    core = np.exp(-((D / 0.12) ** 2)) + 0.3 * np.exp(-((D / 0.4) ** 2))
    return to_image(border_fade(np.minimum(1, rays + core)))


def cracks():
    """Cracks in the ground running out from a scorched middle (seen from above)."""
    img = Image.new("L", (C * 4, C * 4), 0)
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(11)
    cx = cy = C * 2
    for k in range(9):
        ang = k / 9 * 2 * math.pi + rng.random() * 0.5
        x, y = cx, cy
        length = C * (1.5 + rng.random() * 0.4)
        steps = 9
        width = 14
        for s in range(steps):
            ang += (rng.random() - 0.5) * 0.7
            nx = x + math.cos(ang) * length / steps
            ny = y + math.sin(ang) * length / steps
            d.line([(x, y), (nx, ny)], fill=255, width=max(2, int(width)))
            if rng.random() < 0.3:
                ba = ang + (rng.random() - 0.5) * 1.6
                d.line([(nx, ny), (nx + math.cos(ba) * length / 6, ny + math.sin(ba) * length / 6)], fill=200, width=max(2, int(width * 0.6)))
            x, y = nx, ny
            width *= 0.8
    small = np.asarray(img.resize((C, C), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.8))) / 255.0
    scorch = 0.45 * np.exp(-((D / 0.3) ** 2))
    a = np.minimum(1, small + scorch) * smooth(0.95, 0.6, D)
    img = with_halo(to_image(a), 5, 0.4)
    return to_image(border_fade(np.asarray(img) / 255.0))


def tombstone():
    """A gravestone: a rounded top, R.I.P. carved in (darker), a cross above it, a bright rim."""
    y = U
    w = 0.52
    top = 0.35
    body = (np.abs(X) < w) & (y < top) & (y > -0.9)
    arch = np.hypot(X, y - top) < w
    shape = (body | arch).astype(float)
    big = to_image(shape).resize((C * 2, C * 2), Image.BICUBIC).filter(ImageFilter.GaussianBlur(1.5)).resize((C, C), Image.LANCZOS)
    a = np.asarray(big) / 255.0
    inner = np.asarray(to_image(shape).filter(ImageFilter.GaussianBlur(5))) / 255.0
    rim = np.clip(a - inner * 0.9, 0, 1) * 1.5
    face = a * 0.55
    carve = Image.new("L", (C, C), 0)
    d = ImageDraw.Draw(carve)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 46)
    box = d.textbbox((0, 0), "R.I.P", font=font)
    d.text(((C - (box[2] - box[0])) / 2 - box[0], C * 0.5 - box[1]), "R.I.P", font=font, fill=255)
    d.rectangle([C / 2 - 5, C * 0.16, C / 2 + 5, C * 0.42], fill=255)
    d.rectangle([C / 2 - 26, C * 0.22, C / 2 + 26, C * 0.27], fill=255)
    carved = np.asarray(carve.filter(ImageFilter.GaussianBlur(0.8))) / 255.0
    a = np.clip(face + rim - carved * 0.4 + carved * 0.0, 0, 1)
    a = np.where(carved > 0.3, np.maximum(a, 0.95 * carved), a)
    img = with_halo(to_image(a), 8, 0.4)
    return to_image(border_fade(np.asarray(img) / 255.0))


def coin():
    """A coin face-on: a thick bright rim, a raised star in the middle, a soft sheen."""
    rim = smooth(0.0, 0.02, 0.86 - D) * (0.6 + 0.4 * smooth(0.0, 0.03, D - 0.7))
    ang = np.arctan2(U, X)
    star_r = 0.22 + 0.24 * (np.cos(ang * 5 - np.pi / 2) * 0.5 + 0.5) ** 3
    star = smooth(0.0, 0.03, star_r - D)
    sheen = 0.3 * np.exp(-(((X + U) / 0.25) ** 2)) * (D < 0.8)
    a = np.minimum(1, rim * 0.75 + star * 0.35 + sheen)
    img = with_halo(to_image(a), 6, 0.4)
    return to_image(border_fade(np.asarray(img) / 255.0))


def bird_wing():
    """A great feathered wing: coverts near the hinge, long primaries fanning out to the tip, each feather with a
    bright shaft and edge, the leading edge brightest."""
    hx, hy = -0.95, 0.35
    a = np.zeros_like(D)
    n = 16
    for k in range(n):
        f = k / (n - 1)
        ang = np.radians(8 - 78 * f)  # from along the leading edge (right) down to hanging down
        length = 1.95 - 0.95 * f ** 1.4
        width = 0.15 + 0.05 * f
        dx, dy = np.cos(ang), np.sin(ang)
        px, py = X - hx, U - hy
        along = px * dx + py * dy
        across = -px * dy + py * dx
        t = along / length
        inside = (t > 0.05) & (t < 1)
        w = width * np.clip(np.sin(np.pi * np.clip(t, 0, 1) * 0.92 + 0.12), 0, 1) ** 0.6 * (1 - 0.45 * t ** 2)
        feather = smooth(0.0, 0.02, w - np.abs(across)) * inside
        shaft = np.exp(-((across / 0.008) ** 2)) * inside * 0.5
        edge = np.exp(-(((w - np.abs(across)) / 0.012) ** 2)) * inside * 0.4
        tip = smooth(0.6, 1.0, t) * 0.25
        a = np.maximum(a, feather * (0.5 + 0.15 * (k % 2) + tip) + shaft + edge)
    coverts = smooth(1.0, 0.85, np.hypot((X + 0.35) / 0.75, (U - 0.18) / 0.36))
    scales = 0.8 + 0.2 * np.sin(X * 40) * np.sin(U * 40)
    lead = np.exp(-(((U - (0.42 + 0.06 * (X + 0.95))) / 0.025) ** 2)) * (X < 0.9) * (X > -0.9) * 0.8
    a = np.maximum(a, coverts * 0.7 * scales)
    a = np.minimum(1, a + lead)
    a = a * smooth(-1.0, -0.86, X)
    img = with_halo(to_image(a), 8, 0.5)
    return to_image(border_fade(np.asarray(img) / 255.0, 0.95))


def moon():
    """A crescent moon: a full disc with an offset disc cut out (horns to the right), bright rim, soft glow."""
    disc = smooth(0.70, 0.66, D)
    cut = smooth(0.58, 0.62, np.hypot(X - 0.32, Y + 0.08))
    a = disc * cut
    # A faint texture of craters so it doesn't read as flat paper.
    a = a * (0.88 + 0.12 * value_noise(6, 7))
    img = with_halo(to_image(a), 16, 0.6)
    return to_image(border_fade(np.asarray(img) / 255.0))


def snooze():
    """A rounded bold "Z" (sleep), not mirrored, with a soft halo."""
    img = Image.new("L", (C, C), 0)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 200)
    d = ImageDraw.Draw(img)
    box = d.textbbox((0, 0), "Z", font=font)
    d.text(((C - (box[2] - box[0])) / 2 - box[0], (C - (box[3] - box[1])) / 2 - box[1]), "Z", font=font, fill=255)
    img = with_halo(img.filter(ImageFilter.GaussianBlur(1.2)), 12, 0.55)
    return to_image(border_fade(np.asarray(img) / 255.0))


QMARK = [".XXX.", "XX.XX", "...XX", "..XX.", ".XX..", ".....", ".XX.."]


def _lucky_faces(scale=4):
    """The three visible faces of an isometric lucky block, rendered big: per pixel the face (0 none, 1 top,
    2 left, 3 right) and the face coordinates u (across) and v (down the face), all 0..1."""
    n = C * scale
    ax = (np.arange(n) + 0.5) / n * 2 - 1
    px, py = np.meshgrid(ax, ax)
    k = 0.8
    ex, ey, ez = np.array([0.74, 0.43]) * k, np.array([-0.74, 0.43]) * k, np.array([0.0, 0.86]) * k
    top = np.array([0.0, -0.86]) * k
    left, front = top + ey, top + ex + ey
    faces = [(1, left, -ey, ex), (2, left, ex, ez), (3, front, -ey, ez)]   # origin, u axis, v axis
    face = np.zeros((n, n), int)
    U = np.zeros((n, n))
    V = np.zeros((n, n))
    for fid, o, a, b in faces:
        m = np.linalg.inv(np.array([[a[0], b[0]], [a[1], b[1]]]))
        dx, dy = px - o[0], py - o[1]
        u = m[0, 0] * dx + m[0, 1] * dy
        v = m[1, 0] * dx + m[1, 1] * dy
        inside = (u >= 0) & (u <= 1) & (v >= 0) & (v <= 1) & (face == 0)
        face[inside] = fid
        U[inside] = u[inside]
        V[inside] = v[inside]
    return face, U, V


def _qmark(u, v):
    col = np.floor((u - 0.22) / 0.56 * 5).astype(int)
    row = np.floor((v - 0.14) / 0.72 * 7).astype(int)
    ok = (col >= 0) & (col < 5) & (row >= 0) & (row < 7)
    out = np.zeros_like(u, dtype=bool)
    for r in range(7):
        for c in range(5):
            if QMARK[r][c] == "X":
                out |= ok & (row == r) & (col == c)
    return out


def _studs(u, v):
    near = lambda x: (x > 0.07) & (x < 0.17) | (x > 0.83) & (x < 0.93)
    return near(u) & near(v)


def _shrink(a, scale=4):
    big = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8))
    return np.asarray(big.resize((C, C), Image.LANCZOS)) / 255.0


def lucky_block():
    """An isometric lucky block (tinted gold): top lit, left half, right dim, a darker rim band round every face and
    dark spots where the "?" and the corner studs go - cell 49 lays those on top in lavender."""
    face, u, v = _lucky_faces()
    shade = np.choose(face, [0.0, 1.0, 0.78, 0.56])
    edge = np.minimum(np.minimum(u, 1 - u), np.minimum(v, 1 - v))
    a = shade * np.where(edge < 0.06, 0.55, 1.0)
    q = _qmark(u, v) | _studs(u, v)
    ring = ndimage_dilate(q, 10) & ~q
    a = np.where(ring, a * 0.18, a)
    a = np.where(q, a * 0.3, a)
    a = np.where(face == 0, 0.0, a)
    # the seams between the faces stay dark, so the block reads as a solid cube
    small = _shrink(a)
    img = with_halo(to_image(small), 6, 0.25)
    return to_image(border_fade(np.asarray(img) / 255.0, 0.96))


def lucky_marks():
    """The lucky block's "?" on each face and its corner studs, matching cell 48 (tinted lavender)."""
    face, u, v = _lucky_faces()
    q = (_qmark(u, v) | _studs(u, v)) & (face > 0)
    a = q * np.choose(face, [0.0, 1.0, 0.9, 0.8])
    img = with_halo(to_image(_shrink(a)), 5, 0.35)
    return to_image(border_fade(np.asarray(img) / 255.0, 0.96))


def ndimage_dilate(mask, r):
    img = Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(2 * r + 1))
    return np.asarray(img) > 127


def main():
    atlas = Image.new("L", (C * 8, C * 8), 0)
    cells = {0: glow(), 1: star(), 2: beam(), 3: shape(heart), 4: shape(leaf), 5: curtain(), 6: halo(), 7: smoke(),
             24: feather(), 25: petal(), 26: shard(), 27: flame(), 28: sparkle(), 29: wing(), 30: ribbon(), 31: taiji(),
             32: koi(), 33: sword(), 34: lantern(), 35: gear(), 36: lily(), 37: dial(),
             38: pixel(), 39: slash(), 40: ghost(), 41: starburst(), 42: cracks(), 43: tombstone(), 44: coin(), 45: bird_wing(),
             46: moon(), 47: snooze(), 48: lucky_block(), 49: lucky_marks()}
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
