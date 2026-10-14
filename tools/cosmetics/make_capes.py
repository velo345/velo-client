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


# ---- Halloween (Afterlife) ----

def _poly_mask(h, w, draw):
    """A smooth mask from a PIL drawing done at 4x and scaled down."""
    from PIL import ImageDraw
    big = Image.new("L", (w * 4, h * 4), 0)
    draw(ImageDraw.Draw(big), 4)
    return np.asarray(big.resize((w, h), Image.LANCZOS), dtype=float) / 255


def ghost_shape(size, wobble):
    """A ghost sprite (alpha), {size} px tall: round head, wavy hem, two eyes and a mouth (cut out)."""
    s = size
    w = int(s * 0.7)

    def draw(d, k):
        S, W2 = s * k, w * k
        d.ellipse([0, 0, W2, W2], fill=255)
        hem = [(0, W2 / 2), (W2, W2 / 2)]
        pts = [(W2, W2 / 2), (W2, S * 0.82)]
        for i in range(7):
            fx = 1 - i / 6
            pts.append((W2 * fx, S * (0.82 + 0.1 * math.sin(i * 2.1 + wobble * 2 * math.pi) + (0.08 if i % 2 else 0))))
        pts.append((0, W2 / 2))
        d.polygon(pts, fill=255)
        for ex in (0.32, 0.68):
            d.ellipse([W2 * ex - W2 * 0.08, W2 * 0.38, W2 * ex + W2 * 0.08, W2 * 0.6], fill=0)
        d.ellipse([W2 * 0.44, W2 * 0.68, W2 * 0.56, W2 * 0.84], fill=0)
    return _poly_mask(s, w, draw)


HAUNTED = None


def haunted(phase):
    global HAUNTED
    h, w = H * SS, W * SS
    if HAUNTED is None:
        r = np.random.default_rng(121)
        ghosts = [(r.uniform(0.1, 0.9), r.uniform(0, 1), r.uniform(0.18, 0.3), r.uniform(0, 1), 1 + r.integers(0, 2)) for _ in range(5)]
        HAUNTED = (ghosts, tile_noise(h, w, 3, 122, 4))
    ghosts, mist = HAUNTED
    x, y = coords()
    img = gradient(y, [(0, (10, 14, 34)), (0.6, (26, 32, 60)), (1, (44, 50, 78))])
    # The moon, top right, with a soft halo.
    d = np.hypot((x - 0.72) * w, (y - 0.13) * h) / w
    img += (np.exp(-np.clip(d - 0.13, 0, None) * 22) * 0.35)[..., None] * np.array([0.8, 0.85, 1.0])
    img = np.where((d < 0.13)[..., None], np.array([0.92, 0.94, 0.86]) - blur(mist, 2)[..., None] * 0.15, img)
    # Gravestones along the bottom.
    def stones(dr, k):
        for gx, gw, gh in ((0.12, 0.2, 0.12), (0.5, 0.16, 0.09), (0.82, 0.22, 0.14)):
            cx, base = gx * w * k, h * k
            dr.rounded_rectangle([cx - gw * w * k / 2, base - gh * h * k, cx + gw * w * k / 2, base + 10], radius=gw * w * k / 2, fill=255)
            dr.rectangle([cx - gw * w * k / 2, base - gh * h * k * 0.5, cx + gw * w * k / 2, base + 10], fill=255)
    graves = _poly_mask(h, w, stones)
    img = img * (1 - graves[..., None]) + graves[..., None] * np.array([0.08, 0.08, 0.12])
    # Ghosts drifting up, swaying, fading in and out.
    for gx, gy, gs, gp, speed in ghosts:
        t = (gy + phase * speed) % 1
        size = int(gs * h)
        sprite = ghost_shape(size, phase * speed + gp)
        cx = int((gx + 0.06 * math.sin((phase * speed + gp) * 2 * math.pi)) * w)
        cy = int((1.1 - t * 1.3) * h)
        alpha = math.sin(t * math.pi) * 0.85
        sh, sw = sprite.shape
        x0, y0 = cx - sw // 2, cy - sh // 2
        ys, xs = slice(max(0, y0), min(h, y0 + sh)), slice(max(0, x0), min(w, x0 + sw))
        sub = sprite[ys.start - y0:ys.stop - y0, xs.start - x0:xs.stop - x0] * alpha
        if sub.size == 0:
            continue
        glow = np.zeros((h, w))
        glow[ys, xs] = sub
        img += blur(glow, 6)[..., None] * np.array([0.5, 0.8, 0.75]) * 0.45
        img = img * (1 - glow[..., None]) + glow[..., None] * np.array([0.88, 0.97, 0.95])
    # Ground mist rolling sideways.
    fog = np.roll(mist, int(phase * w), axis=1) * np.clip((y - 0.55) * 2.5, 0, 1)
    img += fog[..., None] * np.array([0.35, 0.4, 0.5]) * 0.6
    return img


PUMPKIN = None


def jack_o_lantern(phase):
    global PUMPKIN
    h, w = H * SS, W * SS
    if PUMPKIN is None:
        cx, cy, pw, ph = 0.5, 0.52, 0.86, 0.46

        def body(d, k):
            W2, H2 = w * k, h * k
            for off in (-0.28, 0.28, -0.14, 0.14, 0):
                d.ellipse([W2 * (cx + off * pw - pw * 0.34), H2 * (cy - ph / 2), W2 * (cx + off * pw + pw * 0.34), H2 * (cy + ph / 2)], fill=255)

        def face(d, k):
            W2, H2 = w * k, h * k
            def P(px, py):
                return (W2 * (cx + px * pw), H2 * (cy + py * ph))
            d.polygon([P(-0.3, -0.12), P(-0.14, -0.12), P(-0.22, -0.3)], fill=255)   # eyes
            d.polygon([P(0.14, -0.12), P(0.3, -0.12), P(0.22, -0.3)], fill=255)
            d.polygon([P(-0.05, 0.0), P(0.05, 0.0), P(0, -0.1)], fill=255)          # nose
            mouth = [P(-0.36, 0.08), P(-0.24, 0.16), P(-0.18, 0.1), P(-0.1, 0.18), P(-0.02, 0.1), P(0.06, 0.18),
                     P(0.14, 0.1), P(0.22, 0.18), P(0.36, 0.08), P(0.24, 0.3), P(-0.24, 0.3)]
            d.polygon(mouth, fill=255)

        def stem(d, k):
            W2, H2 = w * k, h * k
            d.polygon([(W2 * 0.46, H2 * (cy - ph / 2 + 0.02)), (W2 * 0.54, H2 * (cy - ph / 2 + 0.02)),
                       (W2 * 0.58, H2 * (cy - ph / 2 - 0.08)), (W2 * 0.5, H2 * (cy - ph / 2 - 0.1))], fill=255)
        ribs = np.abs(np.sin((coords()[0] - cx) / pw * math.pi * 3.2)) ** 0.6
        PUMPKIN = (_poly_mask(h, w, body), _poly_mask(h, w, face), _poly_mask(h, w, stem), ribs, tile_noise(h, w, 4, 131, 3))
    body, face, stem, ribs, smoke = PUMPKIN
    x, y = coords()
    img = gradient(y, [(0, (14, 6, 20)), (1, (34, 12, 30))])
    # Candle flicker: a few beats per loop, never quite steady.
    flicker = 0.78 + 0.12 * math.sin(phase * 2 * math.pi * 5) + 0.1 * math.sin(phase * 2 * math.pi * 13 + 1)
    # Orange glow on the background around the pumpkin.
    img += blur(body, 14)[..., None] * np.array([1.0, 0.45, 0.08]) * 0.35 * flicker
    skin = np.array([0.95, 0.42, 0.06]) * (0.55 + 0.45 * (1 - ribs[..., None])) * (0.7 + 0.3 * (1 - y[..., None]))
    img = img * (1 - body[..., None]) + body[..., None] * skin
    img = img * (1 - stem[..., None]) + stem[..., None] * np.array([0.25, 0.32, 0.1])
    # The carved face, lit from inside: yellow-white core, orange edges.
    inner = blur(face, 2)
    lit = np.array([1.0, 0.85, 0.35]) * flicker
    img = img * (1 - face[..., None]) + face[..., None] * lit * (0.75 + 0.35 * inner[..., None])
    img += blur(face, 8)[..., None] * np.array([1.0, 0.6, 0.15]) * 0.6 * flicker
    # Wisps of smoke rising from the top.
    wisp = np.roll(smoke, -int(phase * h), axis=0) * np.clip((0.3 - y) * 3, 0, 1)
    img += (np.clip(wisp - 0.45, 0, 1) * 1.5)[..., None] * np.array([0.35, 0.3, 0.35])
    return img


WITCH = None


def witching_hour(phase):
    global WITCH
    h, w = H * SS, W * SS
    if WITCH is None:
        r = np.random.default_rng(141)

        def tree(d, k):
            W2, H2 = w * k, h * k
            def branch(x0, y0, angle, length, width, depth):
                if depth == 0:
                    return
                x1, y1 = x0 + math.cos(angle) * length, y0 + math.sin(angle) * length
                d.line([(x0, y0), (x1, y1)], fill=255, width=max(1, int(width)))
                for _ in range(2):
                    branch(x1, y1, angle + r.uniform(-0.7, 0.7), length * r.uniform(0.55, 0.75), width * 0.6, depth - 1)
            branch(W2 * 0.12, H2, -math.pi / 2 + 0.15, H2 * 0.3, 7 * k, 6)
            d.rectangle([0, H2 * 0.95, W2, H2], fill=255)
        bats = [(r.uniform(0, 1), r.uniform(0.12, 0.62), r.uniform(0.1, 0.16), r.uniform(0, 1), 1 + r.integers(0, 2)) for _ in range(6)]
        WITCH = (_poly_mask(h, w, tree), bats)
    tree, bats = WITCH
    x, y = coords()
    img = gradient(y, [(0, (40, 10, 50)), (0.55, (110, 30, 40)), (1, (60, 14, 30))])
    # A huge orange moon.
    d = np.hypot((x - 0.55) * w, (y - 0.4) * h) / w
    moon = np.clip((0.33 - d) * 60, 0, 1)
    img += (np.exp(-np.clip(d - 0.33, 0, None) * 9) * 0.45)[..., None] * np.array([1.0, 0.55, 0.15])
    img = img * (1 - moon[..., None]) + moon[..., None] * (np.array([1.0, 0.62, 0.18]) - (np.abs(np.sin(x * 23 + y * 17)) * 0.05)[..., None])
    # Bats flapping across.
    from PIL import ImageDraw
    layer = Image.new("L", (w * 4, h * 4), 0)
    dr = ImageDraw.Draw(layer)
    for bx, by, bs, bp, speed in bats:
        t = (bx + phase * speed) % 1.2 - 0.1
        cx, cy = t * w * 4, (by + 0.03 * math.sin((phase * speed * 3 + bp) * 2 * math.pi)) * h * 4
        flap = math.sin((phase * 8 * speed + bp) * 2 * math.pi)
        S = bs * w * 4
        wing_y = cy - flap * S * 0.35
        dr.polygon([(cx, cy), (cx - S, wing_y), (cx - S * 0.7, cy + S * 0.15), (cx - S * 0.35, cy + S * 0.05), (cx, cy + S * 0.2),
                    (cx + S * 0.35, cy + S * 0.05), (cx + S * 0.7, cy + S * 0.15), (cx + S, wing_y)], fill=255)
        dr.ellipse([cx - S * 0.14, cy - S * 0.12, cx + S * 0.14, cy + S * 0.22], fill=255)
    batmask = np.asarray(layer.resize((w, h), Image.LANCZOS), dtype=float) / 255
    sil = np.clip(tree + batmask, 0, 1)
    img = img * (1 - sil[..., None]) + sil[..., None] * np.array([0.04, 0.02, 0.05])
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
    "haunted": haunted,
    "jack_o_lantern": jack_o_lantern,
    "witching_hour": witching_hour,
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
