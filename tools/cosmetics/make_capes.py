#!/usr/bin/env python3
"""Renders the animated Velo capes (seamless loops) into cape GIFs for the server catalog.

    python3 tools/cosmetics/make_capes.py [id ...]      (all when none given)

Each cape is drawn as the art of the cape's outside (10 x 16 cape pixels, here 80 x 128 at 8x, drawn at
2x and scaled down), placed into the vanilla 64 x 32 cape layout at 512 x 256: the outside, a dimmer
copy on the inside, matching edges. Written to server/cosmetics-seed/capes/<id>.gif (+ a contact sheet
in build/cape-previews/ to look at). Every animation is a function of the loop phase, so the last frame
runs straight into the first.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "server/cosmetics-seed/capes"
PREVIEW = ROOT / "build/cape-previews"
SCALE = 8                      # texture pixels per cape pixel
W, H = 10 * SCALE, 16 * SCALE  # the outside face
SS = 2                         # supersampling
FRAMES = 40
DELAY_MS = 60

rng = np.random.default_rng(7)


# ---- helpers ----

def tile_noise(h, w, cells, seed, octaves=4):
    """Tileable fractal noise in [0, 1] (wraps in both directions)."""
    r = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        ch, cw = cells * 2 ** o, max(2, int(cells * w / h)) * 2 ** o
        grid = r.random((ch, cw))
        ys = np.linspace(0, ch, h, endpoint=False)
        xs = np.linspace(0, cw, w, endpoint=False)
        y0, x0 = np.floor(ys).astype(int), np.floor(xs).astype(int)
        fy, fx = ys - y0, xs - x0
        fy, fx = fy * fy * (3 - 2 * fy), fx * fx * (3 - 2 * fx)
        y1, x1 = (y0 + 1) % ch, (x0 + 1) % cw
        a = grid[y0][:, x0]
        b = grid[y0][:, x1]
        c = grid[y1][:, x0]
        d = grid[y1][:, x1]
        top = a + (b - a) * fx[None, :]
        bot = c + (d - c) * fx[None, :]
        out += amp * (top + (bot - top) * fy[:, None])
        total += amp
        amp *= 0.5
    return out / total


def blur(arr, radius):
    img = Image.fromarray(np.clip(arr * 255, 0, 255).astype(np.uint8))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(radius)), dtype=float) / 255


def gradient(value, stops):
    """Maps value (0..1, any shape) through colour stops [(pos, (r, g, b)), ...] -> (..., 3) in 0..1."""
    value = np.clip(value, 0, 1)
    out = np.zeros(value.shape + (3,))
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (value >= p0) & (value <= p1)
        f = ((value - p0) / max(1e-6, p1 - p0))[m][:, None]
        out[m] = np.array(c0) / 255 * (1 - f) + np.array(c1) / 255 * f
    return out


def coords(w=W * SS, h=H * SS):
    y, x = np.mgrid[0:h, 0:w]
    return x / w, y / h  # 0..1


# ---- 1. Velo Neon: the logo traced in neon, a spark running round it, a synthwave grid ----

LOGO = None


def logo_masks():
    global LOGO
    if LOGO is None:
        size = int(W * SS * 0.94)
        img = Image.open(ROOT / "Logo.png").convert("RGBA").resize((size, size), Image.LANCZOS)
        mask = np.zeros((H * SS, W * SS))
        oy, ox = int(H * SS * 0.2), (W * SS - size) // 2
        mask[oy:oy + size, ox:ox + size] = np.asarray(img)[:, :, 3] / 255
        eroded = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(5)), dtype=float) / 255
        edge = np.clip(mask - eroded, 0, 1)
        cy, cx = oy + size / 2, ox + size / 2
        yy, xx = np.mgrid[0:H * SS, 0:W * SS]
        angle = (np.arctan2(yy - cy, xx - cx) / (2 * math.pi)) % 1
        LOGO = (mask, edge, angle)
    return LOGO


def velo_neon(phase):
    x, y = coords()
    mask, edge, angle = logo_masks()
    red = np.array([255, 42, 58]) / 255
    # Background: deep black-violet, a perspective grid scrolling towards you at the bottom.
    img = np.zeros((H * SS, W * SS, 3)) + np.array([8, 5, 12]) / 255
    horizon = 0.62
    below = y > horizon
    depth = np.where(below, (y - horizon) / (1 - horizon), 0) + 1e-3
    gz = (1 / depth + phase * 2) % 1
    gx = ((x - 0.5) / depth * 1.2) % 0.25 / 0.25
    lines = (np.exp(-((gz - 0.5) ** 2) * 900 * depth) + np.exp(-((gx - 0.5) ** 2) * 400)) * below * np.clip(depth * 2, 0, 1)
    img += lines[..., None] * np.array([0.55, 0.05, 0.25]) * 0.55
    img += (np.exp(-((y - horizon) ** 2) * 900))[..., None] * np.array([1, 0.2, 0.4]) * 0.35
    # Logo body: a dim red fill breathing slowly.
    breathe = 0.5 + 0.5 * math.sin(phase * 2 * math.pi)
    img = img * (1 - mask[..., None]) + mask[..., None] * (red * (0.18 + 0.1 * breathe))
    # Neon outline with a soft glow, flickering on twice per loop.
    flicker = 1.0 if not (0.10 < phase < 0.13 or 0.16 < phase < 0.18) else 0.35
    glow = blur(edge, 6) * 2.2 + blur(edge, 2) * 1.2
    img += glow[..., None] * red * flicker
    img += edge[..., None] * np.array([1, 0.75, 0.78]) * 0.9 * flicker
    # A white-hot spark running round the outline (two, opposite).
    for offset in (0, 0.5):
        d = ((angle - phase - offset + 0.5) % 1) - 0.5
        spark = np.exp(-(d ** 2) * 900) * edge
        img += blur(spark, 3)[..., None] * 3 + spark[..., None] * 2
    return img


# ---- 2. Wildfire: flames rising up the cape, embers ----

FIRE = None


def wildfire(phase):
    global FIRE
    h, w = H * SS, W * SS
    if FIRE is None:
        FIRE = (tile_noise(h, w, 4, 11, 5), tile_noise(h, w, 8, 12, 4))
    n1, n2 = FIRE
    shift1 = int(phase * h) % h
    shift2 = int(phase * 2 * h) % h
    turbulence = np.roll(n1, shift1, axis=0) * 0.6 + np.roll(n2, shift2, axis=0) * 0.4
    x, y = coords()
    height = y  # 0 top .. 1 bottom
    heat = np.clip(turbulence * 1.25 - (1 - height) * 0.95 + 0.25, 0, 1)
    heat = heat ** 1.4
    img = gradient(heat, [(0, (6, 2, 2)), (0.25, (90, 8, 4)), (0.5, (220, 50, 10)), (0.75, (255, 160, 30)), (0.92, (255, 235, 140)), (1, (255, 255, 230))])
    # Embers drifting up.
    for i in range(26):
        ex = (rng.random() + 0.08 * math.sin(phase * 2 * math.pi * 2 + i)) % 1
        ey = (rng.random() - phase * (1 + i % 3)) % 1
        rng.random()
        d2 = (x - ex) ** 2 * (w / h) ** 2 + (y - ey) ** 2
        img += np.exp(-d2 * 90000)[..., None] * np.array([1, 0.7, 0.3]) * 1.4
    rng.bit_generator.state = np.random.default_rng(7).bit_generator.state
    return img


# ---- 3. Glacier: ice crystals, a shimmer sweeping across, twinkles ----

ICE = None


def crystal_field(h, w, seed):
    r = np.random.default_rng(seed)
    img = Image.new("L", (w, h), 0)
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)

    def branch(x, y, angle, length, depth):
        if depth == 0 or length < 3:
            return
        x2, y2 = x + math.cos(angle) * length, y + math.sin(angle) * length
        d.line([(x, y), (x2, y2)], fill=int(120 + 40 * depth), width=max(1, depth))
        for side in (-1, 1):
            branch(x + (x2 - x) * 0.55, y + (y2 - y) * 0.55, angle + side * math.radians(60), length * 0.45, depth - 1)
        branch(x2, y2, angle + r.uniform(-0.2, 0.2), length * 0.7, depth - 1)

    for _ in range(9):
        cx, cy = r.uniform(0, w), r.uniform(0, h)
        for k in range(6):
            branch(cx, cy, k * math.pi / 3 + r.uniform(-0.1, 0.1), r.uniform(14, 34), 4)
    return np.asarray(img, dtype=float) / 255


def glacier(phase):
    global ICE
    h, w = H * SS, W * SS
    if ICE is None:
        ICE = (crystal_field(h, w, 21), tile_noise(h, w, 3, 22, 4), np.random.default_rng(23).random((40, 3)))
    crystals, frost, stars = ICE
    x, y = coords()
    base = gradient(frost * 0.6 + y * 0.4, [(0, (10, 30, 70)), (0.5, (20, 70, 130)), (1, (60, 150, 210))])
    lines = blur(crystals, 1.2) * 0.8 + blur(crystals, 4) * 0.6
    sweep = np.exp(-(((x * 0.6 + y * 0.8) - (phase * 2.2 - 0.6)) ** 2) * 60)
    img = base + lines[..., None] * np.array([0.65, 0.88, 1.0]) * (0.6 + 1.4 * sweep[..., None])
    img += sweep[..., None] * np.array([0.25, 0.35, 0.45]) * 0.5
    for sx, sy, sp in stars:
        tw = max(0, math.sin((phase + sp) * 2 * math.pi)) ** 6
        if tw < 0.02:
            continue
        d = np.exp(-(((x - sx) * w) ** 2 + ((y - sy) * h) ** 2) / 6)
        cross = np.exp(-(((x - sx) * w) ** 2) / 1.5) * np.exp(-(((y - sy) * h) ** 2) / 60) + \
            np.exp(-(((y - sy) * h) ** 2) / 1.5) * np.exp(-(((x - sx) * w) ** 2) / 60)
        img += (d + cross * 0.8)[..., None] * tw * 1.6
    # Frosty edges.
    edge = np.clip(1 - np.minimum(np.minimum(x, 1 - x) * 10, np.minimum(y, 1 - y) * 6), 0, 1)
    img += edge[..., None] * np.array([0.5, 0.7, 0.85]) * 0.5
    return img


# ---- 4. Thunderstrike: storm clouds rolling, forked lightning lighting them up ----

STORM = None


def bolt(h, w, seed):
    r = np.random.default_rng(seed)
    from PIL import ImageDraw
    img = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(img)

    def segment(x1, y1, x2, y2, width, depth):
        points = [(x1, y1), (x2, y2)]
        for _ in range(6):
            new = [points[0]]
            for (ax, ay), (bx, by) in zip(points, points[1:]):
                mx, my = (ax + bx) / 2, (ay + by) / 2
                off = math.hypot(bx - ax, by - ay) * 0.22
                new += [(mx + r.uniform(-off, off), my + r.uniform(-off * 0.3, off * 0.3)), (bx, by)]
            points = new
        d.line(points, fill=255, width=width)
        if depth > 0:
            for _ in range(2):
                i = r.integers(len(points) // 4, len(points) * 3 // 4)
                px, py = points[i]
                segment(px, py, px + r.uniform(-w * 0.35, w * 0.35), py + r.uniform(h * 0.12, h * 0.3), max(1, width - 2), depth - 1)

    segment(r.uniform(w * 0.3, w * 0.7), 0, r.uniform(w * 0.2, w * 0.8), h * r.uniform(0.75, 1.0), 4, 2)
    return np.asarray(img, dtype=float) / 255


# Colour schemes: cloud gradient stops, the glow around bolts / flash tint, distant flicker, rain.
STORM_COLOURS = {
    "purple": ([(0, (12, 6, 26)), (0.5, (40, 22, 74)), (0.8, (80, 50, 128)), (1, (138, 100, 190))],
               np.array([0.75, 0.45, 1.0]), np.array([0.65, 0.45, 0.95]), np.array([0.5, 0.4, 0.65])),
    "red": ([(0, (18, 4, 6)), (0.5, (64, 14, 20)), (0.8, (118, 30, 38)), (1, (180, 70, 76))],
            np.array([1.0, 0.25, 0.3]), np.array([0.95, 0.3, 0.3]), np.array([0.65, 0.35, 0.38])),
}


def thunderstrike(phase, scheme="purple"):
    global STORM
    h, w = H * SS, W * SS
    if STORM is None:
        streaks = np.asarray(Image.fromarray((tile_noise(h, w * 8, 2, 35, 3) * 255).astype(np.uint8)).resize((w, h)), dtype=float) / 255
        STORM = (tile_noise(h, w, 3, 31, 5), tile_noise(h, w, 6, 32, 4), bolt(h, w, 33), bolt(h, w, 34), bolt(h, w, 36),
                 np.clip((tile_noise(h, w * 6, 24, 37, 2)[:, ::6] - 0.62) * 4, 0, 1))
    c1, c2, b1, b2, b3, rain = STORM
    clouds = np.roll(c1, int(phase * w), axis=1) * 0.65 + np.roll(c2, int(phase * 2 * w), axis=1) * 0.35
    x, y = coords()
    stops, glow_tint, far_tint, rain_tint = STORM_COLOURS[scheme]
    img = gradient(clouds * 0.85 + (1 - y) * 0.15, stops)
    # Sheet lightning flickering deep in the clouds.
    distant = max(0.0, math.sin(phase * 2 * math.pi * 7)) ** 12 * 0.5
    img += (clouds ** 3)[..., None] * far_tint * distant
    # Rain falling (scrolls twice per loop).
    img += np.roll(rain, int(phase * 2 * h), axis=0)[..., None] * rain_tint * 0.5
    # Three strikes per loop: a flash, the bolt, a fading afterglow.
    for start, b in ((0.05, b1), (0.38, b2), (0.7, b3)):
        t = (phase - start) % 1
        if t > 0.28:
            continue
        flash = math.exp(-t * 16)
        strobe = 1.0 if t < 0.05 or 0.08 < t < 0.12 else 0.45
        light = flash * strobe
        img = img + (clouds ** 2)[..., None] * glow_tint * light * 1.8
        img += blur(b, 5)[..., None] * glow_tint * light * 2.2
        # A white-hot core, tinted at the edges.
        img += (b * light * 2.5)[..., None] * (0.55 + 0.45 * glow_tint)
    return img


# ---- 5. Hieroglyph: gold glyphs on obsidian, lighting up in a wave ----

GLYPHS = None


def glyph_sheet(h, w, seed):
    r = np.random.default_rng(seed)
    from PIL import ImageDraw
    cols, rows = 3, 6
    cw, chh = w / cols, h / rows
    masks = []
    for gy in range(rows):
        for gx in range(cols):
            img = Image.new("L", (w, h), 0)
            d = ImageDraw.Draw(img)
            ox, oy = gx * cw + cw * 0.18, gy * chh + chh * 0.18
            s = min(cw, chh) * 0.64
            nodes = [(ox + s * (i % 3) / 2, oy + s * (i // 3) / 2) for i in range(9)]
            # 4-6 strokes between grid nodes, maybe a ring or an eye.
            for _ in range(r.integers(4, 7)):
                a, b = r.choice(9, 2, replace=False)
                d.line([nodes[a], nodes[b]], fill=255, width=SS * 2)
            if r.random() < 0.5:
                cx, cy = nodes[r.integers(9)]
                rad = s * 0.16
                d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], outline=255, width=SS * 2)
            masks.append((np.asarray(img, dtype=float) / 255, gx / cols, gy / rows))
    return masks


def hieroglyph(phase):
    global GLYPHS
    h, w = H * SS, W * SS
    if GLYPHS is None:
        GLYPHS = (glyph_sheet(h, w, 41), tile_noise(h, w, 5, 42, 4))
    glyphs, marble = GLYPHS
    x, y = coords()
    img = gradient(marble * 0.5 + 0.1, [(0, (6, 5, 8)), (0.6, (16, 14, 20)), (1, (34, 30, 40))])
    gold = np.array([1.0, 0.76, 0.32])
    for mask, gx, gy in glyphs:
        # A wave of light runs down the cape; each glyph glows as it passes, then cools to dim gold.
        wave = ((phase - (gy + gx * 0.15)) % 1)
        lit = math.exp(-wave * 7) + 0.12
        img += blur(mask, 4)[..., None] * gold * lit * 1.4 + mask[..., None] * gold * (0.35 + lit)
    # A thin gold border with a highlight running round it.
    border = ((x < 0.04) | (x > 0.96) | (y < 0.025) | (y > 0.975)).astype(float)
    around = np.where(y < 0.5, x * 0.25, 0.5 + (1 - x) * 0.25) % 1
    run = np.exp(-(((around - phase + 0.5) % 1 - 0.5) ** 2) * 300)
    img += border[..., None] * gold * (0.45 + run[..., None] * 1.2)
    return img


# ---- 6. Obsidian: glossy black liquid, an oily sheen gliding over it ----

OBSIDIAN = None


def obsidian(phase):
    global OBSIDIAN
    h, w = H * SS, W * SS
    if OBSIDIAN is None:
        smooth = blur(tile_noise(h, w, 2, 61, 3), 6)
        veins_field = blur(tile_noise(h, w, 3, 62, 4), 3)
        OBSIDIAN = (smooth, veins_field)
    smooth, veins_field = OBSIDIAN
    x, y = coords()
    # Glossy black with a soft gradient (lit from the top left).
    img = np.zeros((h, w, 3)) + (0.018 + 0.05 * (1 - (x * 0.4 + y * 0.6)) + smooth * 0.03)[..., None]
    # Faint silver marble veins: thin lines along contours of the noise.
    veins = np.exp(-(np.sin(veins_field * 38) ** 2) * 60) * 0.5
    img += veins[..., None] * np.array([0.22, 0.22, 0.25])
    # An oil-slick sheen sweeping diagonally across, colours shifting with the surface.
    band = np.exp(-(((x * 0.75 + y * 0.55) - (phase * 1.8 - 0.4)) ** 2) * 40)
    hue = (smooth * 3 + x * 0.5 + phase) % 1
    oil = np.stack([0.5 + 0.5 * np.cos(2 * np.pi * (hue + k / 3)) for k in range(3)], axis=-1)
    img += oil * band[..., None] * 0.2 + band[..., None] * 0.07
    img += (veins * band)[..., None] * 0.6
    return img


# ---- 7. Eclipse: a black sun, its corona pulsing, flares, faint stars ----

ECLIPSE = None


def eclipse(phase):
    global ECLIPSE
    h, w = H * SS, W * SS
    if ECLIPSE is None:
        ECLIPSE = (np.random.default_rng(71).random((60, 3)), tile_noise(h, w, 6, 72, 3))
    stars, wisps = ECLIPSE
    yy, xx = np.mgrid[0:h, 0:w]
    cx, cy = w * 0.5, h * 0.42
    r = np.hypot(xx - cx, yy - cy) / w
    angle = np.arctan2(yy - cy, xx - cx)
    sun = 0.3
    img = np.zeros((h, w, 3)) + np.array([3, 3, 6]) / 255
    for sx, sy, sp in stars:
        tw = 0.4 + 0.6 * max(0, math.sin((phase + sp) * 2 * math.pi)) ** 3
        d = ((xx / w - sx) ** 2 * w * w + (yy / h - sy) ** 2 * h * h)
        img += np.exp(-d / 3)[..., None] * tw * 0.6
    pulse = 0.5 + 0.5 * math.sin(phase * 2 * math.pi)
    # Corona: bright at the rim, streaming outwards in rays that slowly turn.
    rays = 0.6 + 0.4 * np.cos(angle * 9 + phase * 2 * math.pi) * np.cos(angle * 5 - phase * 4 * math.pi)
    outside = np.clip(r - sun, 0, None)
    corona = np.exp(-outside * (14 - 4 * pulse)) * rays * (r > sun - 0.005)
    ring = np.exp(-((r - sun) ** 2) * 9000)
    img += corona[..., None] * np.array([1.0, 0.82, 0.55]) * 1.1
    img += ring[..., None] * np.array([1.0, 0.97, 0.9]) * 1.6
    # A flare licking off the rim, travelling round.
    flare_angle = phase * 2 * math.pi
    da = np.angle(np.exp(1j * (angle - flare_angle)))
    flare = np.exp(-da ** 2 * 60) * np.exp(-outside * 18) * (r > sun)
    img += flare[..., None] * np.array([1.0, 0.6, 0.3]) * 1.3
    # The black disc in front.
    img *= (r > sun)[..., None]
    img += (r <= sun)[..., None] * np.array([1, 1, 2]) / 255
    return img


# ---- 8. Phantom: dark smoke flowing up a black cape, a faint violet glow on its edges ----

PHANTOM = None


def phantom(phase):
    global PHANTOM
    h, w = H * SS, W * SS
    if PHANTOM is None:
        # Noise squashed sideways and stretched up: streaky, like smoke rising.
        def streaky(seed, cells):
            n = tile_noise(h, w * 3, cells, seed, 4)
            return np.asarray(Image.fromarray((n * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR), dtype=float) / 255
        PHANTOM = (streaky(81, 2), streaky(82, 3), tile_noise(h, w, 2, 83, 3))
    n1, n2, warp = PHANTOM
    x, y = coords()
    a = np.roll(n1, -int(phase * h), axis=0)
    b = np.roll(n2, -int(phase * 2 * h), axis=0)
    # Curl: shift each row sideways by a slowly rising warp field.
    shift = (np.roll(warp, -int(phase * h), axis=0) - 0.5) * 16 * SS
    cols = (np.arange(w)[None, :] + shift.astype(int)) % w
    rows = np.arange(h)[:, None]
    n = a[rows, cols] * 0.6 + b[rows, cols] * 0.4
    # Ridged: thin bright filaments where the noise crosses the middle - wisps of smoke.
    wisps = (1 - np.abs(n * 2 - 1)) ** 7
    wisps = blur(wisps, 1.2)
    fade = 0.15 + 0.7 * y
    img = np.zeros((h, w, 3)) + np.array([0.01, 0.008, 0.016])
    img += (wisps * fade)[..., None] * np.array([0.55, 0.54, 0.6])
    glow = blur(wisps, 6) * fade
    pulse = 0.75 + 0.25 * math.sin(phase * 2 * math.pi)
    img += glow[..., None] * np.array([0.45, 0.2, 0.85]) * 0.42 * pulse
    return img


# ---- 9. Kintsugi: white porcelain with gold-filled cracks, light flowing through them ----

KINTSUGI = None


def kintsugi(phase):
    global KINTSUGI
    h, w = H * SS, W * SS
    if KINTSUGI is None:
        from PIL import ImageDraw
        r = np.random.default_rng(91)
        cracks = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(cracks)
        # Cracks wander across the porcelain and branch.
        def crack(x, y, angle, length, width):
            pts = [(x, y)]
            for _ in range(int(length / 4)):
                angle += r.uniform(-0.35, 0.35)
                x, y = x + math.cos(angle) * 4 * SS, y + math.sin(angle) * 4 * SS
                pts.append((x, y))
                if r.random() < 0.06 and width > 1:
                    crack(x, y, angle + r.choice([-1, 1]) * r.uniform(0.6, 1.1), length * 0.55, width - 1)
            d.line(pts, fill=255, width=width)
        # Long cracks running across the whole cape, branching.
        for k in range(3):
            crack(r.uniform(w * 0.1, w * 0.9), -2, r.uniform(1.2, 1.9), r.uniform(150, 220), 2)
        mask = np.asarray(cracks, dtype=float) / 255
        # Distance along the cracks ~ position on a diagonal, for the flowing light.
        KINTSUGI = (mask, blur(tile_noise(h, w, 3, 92, 3), 4))
    mask, glaze = KINTSUGI
    x, y = coords()
    img = np.zeros((h, w, 3)) + np.array([0.93, 0.92, 0.9]) - glaze[..., None] * 0.08 - (y * 0.06)[..., None]
    gold = np.array([0.82, 0.6, 0.24])
    # A glint of light travels down the cracks.
    flow = np.exp(-(((y + x * 0.3) - (phase * 1.6 - 0.3)) ** 2) * 40)
    img = img * (1 - mask[..., None]) + mask[..., None] * gold * (0.8 + 0.7 * flow[..., None])
    img += blur(mask * flow, 3)[..., None] * np.array([1.0, 0.8, 0.4]) * 0.5
    # A soft glaze highlight drifting down.
    img += np.exp(-((y - (phase * 1.4 - 0.2)) ** 2) * 30)[..., None] * 0.06
    return img


# ---- 10. Heaven: white clouds drifting, golden light rays sweeping ----

HEAVEN = None


def heaven(phase):
    global HEAVEN
    h, w = H * SS, W * SS
    if HEAVEN is None:
        HEAVEN = (tile_noise(h, w, 3, 101, 5), tile_noise(h, w, 5, 102, 4))
    c1, c2 = HEAVEN
    x, y = coords()
    clouds = np.roll(c1, int(phase * w), axis=1) * 0.65 + np.roll(c2, -int(phase * 2 * w), axis=1) * 0.35
    sky = np.array([0.62, 0.74, 0.92]) * (1 - y[..., None] * 0.1) + np.array([0.2, 0.14, 0.04]) * (1 - y[..., None]) ** 3
    puff = np.clip((clouds - 0.45) * 3.2, 0, 1)
    # Clouds lit from above: bright tops, soft grey-blue undersides.
    shade = np.clip(np.roll(puff, -3 * SS, axis=0) - puff, 0, 1)
    cloud_colour = np.array([1.0, 0.99, 0.97]) - shade[..., None] * np.array([0.25, 0.22, 0.12])
    img = sky * (1 - puff[..., None]) + puff[..., None] * cloud_colour
    # God rays fanning from the top, turning gently.
    angle = np.arctan2(y + 0.25, x - 0.5)
    rays = (0.5 + 0.5 * np.cos(angle * 22 + math.sin(phase * 2 * math.pi) * 1.5)) ** 4 * np.clip(1 - y * 1.1, 0, 1)
    img += rays[..., None] * np.array([1.0, 0.85, 0.45]) * 0.35
    img += np.exp(-(((x - 0.5) * 2) ** 2 + ((y + 0.05) * 3) ** 2))[..., None] * np.array([1.0, 0.9, 0.6]) * 0.35
    return img


# ---- 11. Pearl: iridescent white, pastel sheen shifting, sparkles ----

PEARL = None


def pearl(phase):
    global PEARL
    h, w = H * SS, W * SS
    if PEARL is None:
        PEARL = (blur(tile_noise(h, w, 2, 111, 3), 5), np.random.default_rng(112).random((30, 3)))
    field, sparks = PEARL
    x, y = coords()
    img = np.zeros((h, w, 3)) + np.array([0.94, 0.94, 0.95])
    # Pastel interference colours drifting with the surface and the light.
    hue = (field * 1.6 + x * 0.4 + y * 0.6 + phase) % 1
    pastel = np.stack([0.5 + 0.5 * np.cos(2 * np.pi * (hue + k / 3)) for k in range(3)], axis=-1)
    img = img * 0.9 + pastel * 0.1
    shine = np.exp(-(((x * 0.6 - y * 0.8) - (phase * 2.2 - 1.2)) ** 2) * 16)
    img += shine[..., None] * 0.25
    for sx, sy, sp in sparks:
        tw = max(0, math.sin((phase + sp) * 2 * math.pi)) ** 8
        if tw < 0.02:
            continue
        dx, dy = (x - sx) * w, (y - sy) * h
        cross = np.exp(-dx ** 2 / 1.2) * np.exp(-dy ** 2 / 40) + np.exp(-dy ** 2 / 1.2) * np.exp(-dx ** 2 / 40)
        img += (cross + np.exp(-(dx ** 2 + dy ** 2) / 4))[..., None] * tw * np.array([1.0, 0.95, 1.0])
    return img


CAPES = {
    "velo_neon": velo_neon,
    "wildfire": wildfire,
    "glacier": glacier,
    "thunderstrike": thunderstrike,
    "crimson_storm": lambda phase: thunderstrike(phase, "red"),
    "hieroglyph": hieroglyph,
    "obsidian": obsidian,
    "eclipse": eclipse,
    "phantom": phantom,
    "kintsugi": kintsugi,
    "heaven": heaven,
    "pearl": pearl,
}


# ---- cape layout ----

def to_texture(art):
    """Outside art (H*SS x W*SS x 3, 0..1+) -> 512 x 256 RGB cape texture."""
    art = np.clip(art, 0, 1)
    face = Image.fromarray((art * 255).astype(np.uint8)).resize((W, H), Image.LANCZOS)
    tex = Image.new("RGB", (64 * SCALE, 32 * SCALE), (0, 0, 0))
    edge_colour = tuple(int(c) for c in np.asarray(face).reshape(-1, 3).mean(0) * 0.6)
    s = SCALE
    tex.paste(face, (1 * s, 1 * s))                                     # outside
    inside = Image.fromarray((np.asarray(face.transpose(Image.FLIP_LEFT_RIGHT), dtype=float) * 0.45).astype(np.uint8))
    tex.paste(inside, (12 * s, 1 * s))                                   # inside (towards your back)
    for box in ((1 * s, 0, 11 * s, 1 * s), (11 * s, 0, 21 * s, 1 * s), (0, 1 * s, 1 * s, 17 * s), (11 * s, 1 * s, 12 * s, 17 * s)):
        tex.paste(edge_colour, box)                                      # top, bottom, sides
    return tex


def render(cape_id):
    fn = CAPES[cape_id]
    frames = [to_texture(fn(i / FRAMES)) for i in range(FRAMES)]
    paletted = [f.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG) for f in frames]
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{cape_id}.gif"
    paletted[0].save(path, save_all=True, append_images=paletted[1:], duration=DELAY_MS, loop=0, disposal=1, optimize=False)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (W * 8 + 70, H + 20), (20, 20, 26))
    for k, i in enumerate(range(0, FRAMES, FRAMES // 8)):
        sheet.paste(frames[i].crop((SCALE, SCALE, 11 * SCALE, 17 * SCALE)), (10 + k * (W + 7), 10))
    sheet.save(PREVIEW / f"{cape_id}.png")
    print(f"{cape_id}: {path.relative_to(ROOT)} ({path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    for cape in sys.argv[1:] or CAPES:
        render(cape)
