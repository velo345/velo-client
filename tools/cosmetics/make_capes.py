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
SCALE = 8                      # art pixels per cape pixel (the art itself is drawn at SS x this)
W, H = 10 * SCALE, 16 * SCALE  # the outside face
SS = 2                         # supersampling
OUT_SCALE = SCALE * SS         # texture pixels per cape pixel: the art at full detail, never downscaled
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
    # Black glazed porcelain (a pale sheet disappeared against the skin and the gold had nothing to stand out from).
    img = np.zeros((h, w, 3)) + np.array([0.075, 0.075, 0.10]) + glaze[..., None] * np.array([0.05, 0.05, 0.07]) + (y * 0.03)[..., None]
    # Soft glaze reflections: a broad cool sheen sliding down, and a fine ceramic mottling.
    sheen = np.exp(-((x * 0.7 + y - (phase * 2.0 - 0.5)) ** 2) * 14)
    img += sheen[..., None] * np.array([0.10, 0.11, 0.16])
    gold = np.array([1.0, 0.72, 0.26])
    glow = blur(mask, 3.5)
    # A glint of light travels down the cracks; the cracks also glow softly on their own.
    flow = np.exp(-(((y + x * 0.3) - (phase * 1.6 - 0.3)) ** 2) * 40)
    img += glow[..., None] * gold * 0.28
    img = img * (1 - mask[..., None]) + mask[..., None] * gold * (0.85 + 0.9 * flow[..., None])
    img += blur(mask * flow, 3)[..., None] * np.array([1.0, 0.85, 0.5]) * 0.7
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
    sky = np.array([0.16, 0.34, 0.74]) * (1 - y[..., None] * 0.35) + np.array([0.55, 0.32, 0.12]) * (1 - y[..., None]) ** 3
    puff = np.clip((clouds - 0.52) * 2.6, 0, 1) * 0.85
    # Clouds lit from above: bright tops, soft grey-blue undersides.
    shade = np.clip(np.roll(puff, -3 * SS, axis=0) - puff, 0, 1)
    cloud_colour = np.array([0.93, 0.92, 0.95]) - shade[..., None] * np.array([0.42, 0.34, 0.2]) - (y[..., None] * 0.12)
    img = sky * (1 - puff[..., None]) + puff[..., None] * cloud_colour
    # God rays fanning from the top, turning gently.
    angle = np.arctan2(y + 0.25, x - 0.5)
    rays = (0.5 + 0.5 * np.cos(angle * 22 + math.sin(phase * 2 * math.pi) * 1.5)) ** 4 * np.clip(1 - y * 1.1, 0, 1)
    img += rays[..., None] * np.array([1.0, 0.78, 0.32]) * 0.28
    img += np.exp(-(((x - 0.5) * 2) ** 2 + ((y + 0.05) * 3) ** 2))[..., None] * np.array([1.0, 0.82, 0.45]) * 0.16
    return img


# ---- 11. Pearl: iridescent white, pastel sheen shifting, sparkles ----

PEARL = None


def pearl(phase):
    global PEARL
    h, w = H * SS, W * SS
    if PEARL is None:
        PEARL = (blur(tile_noise(h, w, 2, 111, 3), 5), np.random.default_rng(112).random((30, 3)),
                 blur(tile_noise(h, w, 6, 113, 4), 1.5))
    field, sparks, ridges = PEARL
    x, y = coords()
    # A black pearl: deep blue-grey body, strong thin-film colours (teal, violet, rose) rolling over it - dark enough to read on any skin.
    img = np.zeros((h, w, 3)) + np.array([0.36, 0.37, 0.46]) + (field[..., None] - 0.5) * 0.10
    hue = (field * 1.8 + x * 0.5 + y * 0.8 + ridges * 0.25 + phase) % 1
    film = np.stack([0.5 + 0.5 * np.cos(2 * np.pi * (hue + k / 3)) for k in range(3)], axis=-1)
    film = 0.45 + film * np.array([0.35, 0.40, 0.5])  # soft pastel, leans teal-rose
    strength = 0.18 + 0.30 * smoothstep_arr(ridges)
    img = img * (1 - strength[..., None]) + film * strength[..., None] * 1.0
    # A broad nacre highlight sweeping across.
    shine = np.exp(-(((x * 0.6 - y * 0.8) - (phase * 2.2 - 1.2)) ** 2) * 16)
    img += shine[..., None] * np.array([0.22, 0.22, 0.28])
    for sx, sy, sp in sparks:
        tw = max(0, math.sin((phase + sp) * 2 * math.pi)) ** 8
        if tw < 0.02:
            continue
        dx, dy = (x - sx) * w, (y - sy) * h
        cross = np.exp(-dx ** 2 / 1.2) * np.exp(-dy ** 2 / 40) + np.exp(-dy ** 2 / 1.2) * np.exp(-dx ** 2 / 40)
        img += (cross + np.exp(-(dx ** 2 + dy ** 2) / 4))[..., None] * tw * np.array([1.0, 0.95, 1.0])
    return img


def smoothstep_arr(v):
    t = np.clip((v - 0.3) / 0.4, 0, 1)
    return t * t * (3 - 2 * t)


# ---- 12. Circuit: a dark board with glowing traces and data pulses running along them ----

CIRCUIT = None


def build_circuit():
    from PIL import ImageDraw
    r = np.random.default_rng(401)
    h, w = H * SS, W * SS
    step = 8 * SS // 2 * 2          # routing grid
    cols, rows = w // step, h // step
    traces = []                     # polylines (list of (x, y) pixel points)
    used = set()
    for _ in range(26):
        gx, gy = int(r.integers(0, cols)), int(r.integers(0, rows))
        pts = [(gx, gy)]
        dx, dy = (0, 1) if r.random() < 0.6 else (1, 0)
        for _ in range(int(r.integers(5, 14))):
            run = int(r.integers(2, 6))
            for _ in range(run):
                nx, ny = pts[-1][0] + dx, pts[-1][1] + dy
                if not (0 <= nx < cols and 0 <= ny < rows) or (nx, ny) in used:
                    break
                pts.append((nx, ny))
            # turn 45 / 90 degrees
            if dx == 0:
                dx, dy = (int(r.choice([-1, 1])), 1 if r.random() < 0.5 else 0)
            else:
                dx, dy = (1 if r.random() < 0.5 else 0, int(r.choice([-1, 1])))
            if dx == 0 and dy == 0:
                dx, dy = 0, 1
        if len(pts) >= 4:
            used.update(pts)
            traces.append([(px * step + step // 2, py * step + step // 2) for px, py in pts])
    # chips
    chips = []
    for _ in range(4):
        cx, cy = int(r.integers(2, cols - 2)), int(r.integers(2, rows - 2))
        chips.append((cx * step, cy * step, int(r.integers(2, 4)) * step, int(r.integers(2, 3)) * step))
    static = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(static)
    for t in traces:
        d.line(t, fill=(40, 150, 190), width=2, joint="curve")
        for (px, py) in (t[0], t[-1]):
            d.ellipse((px - 3, py - 3, px + 3, py + 3), outline=(70, 210, 240), width=2)
    for (cx, cy, cw, ch) in chips:
        d.rectangle((cx, cy, cx + cw, cy + ch), fill=(14, 22, 34), outline=(60, 190, 230), width=2)
        for k in range(0, cw, step // 2):
            d.line((cx + k, cy - 3, cx + k, cy), fill=(60, 190, 230), width=1)
            d.line((cx + k, cy + ch, cx + k, cy + ch + 3), fill=(60, 190, 230), width=1)
    lengths = []
    for t in traces:
        seg = [math.dist(t[i], t[i + 1]) for i in range(len(t) - 1)]
        lengths.append(seg)
    return np.asarray(static, dtype=float) / 255, traces, lengths, chips


def point_at(trace, seg, s):
    for i, l in enumerate(seg):
        if s <= l:
            f = s / max(l, 1e-6)
            return (trace[i][0] + (trace[i + 1][0] - trace[i][0]) * f, trace[i][1] + (trace[i + 1][1] - trace[i][1]) * f), i
        s -= l
    return trace[-1], len(seg) - 1


def circuit(phase):
    global CIRCUIT
    from PIL import ImageDraw
    h, w = H * SS, W * SS
    if CIRCUIT is None:
        CIRCUIT = build_circuit()
    static, traces, lengths, chips = CIRCUIT
    x, y = coords()
    img = np.zeros((h, w, 3)) + np.array([0.025, 0.04, 0.06]) + (y * 0.02)[..., None]
    img += static * 0.85
    pulses = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(pulses)
    for k, (t, seg) in enumerate(zip(traces, lengths)):
        total = sum(seg)
        speed = 1 + (k % 2)
        head = ((phase * speed + (k * 0.37)) % 1.0) * (total + 24)
        tail = max(0.0, head - 22)
        head = min(head, total)
        if head <= tail:
            continue
        # sample the pulse as short bright segments, brightest at the head
        n = 14
        prev, _ = point_at(t, seg, tail)
        for j in range(1, n + 1):
            s = tail + (head - tail) * j / n
            pt, _ = point_at(t, seg, s)
            fade = j / n
            col = (int(120 + 135 * fade), int(230 * fade + 25), 255)
            d.line((prev, pt), fill=tuple(int(c * (0.35 + 0.65 * fade)) for c in col), width=3)
            prev = pt
    pa = np.asarray(pulses, dtype=float) / 255
    img += pa * 1.1 + blur_rgb(pa, 3) * 1.4
    # chips glow softly
    img += blur_rgb(static * np.array([0.5, 1.0, 1.0]), 4) * 0.35
    # a slow scan line
    img += np.exp(-((y - (phase % 1.0)) ** 2) * 220)[..., None] * np.array([0.0, 0.18, 0.22])
    return img


def blur_rgb(arr, radius):
    return np.stack([blur(arr[..., c], radius) for c in range(3)], axis=-1)


# ---- 13. Holo Grid: hexagon cells lighting in a travelling wave, cyan to violet, with a scanline ----

def holo_grid(phase):
    h, w = H * SS, W * SS
    x, y = coords()
    size = 9.0 * SS / 2 * 2          # hex radius in pixels
    px, py = x * w, y * h
    # pointy-top hex grid: axial coordinates of the nearest cell centre
    q = (math.sqrt(3) / 3 * px - 1.0 / 3 * py) / size
    rr = (2.0 / 3 * py) / size
    cx, cy, cz = q, -q - rr, rr
    rx, ry, rz = np.round(cx), np.round(cy), np.round(cz)
    dx, dy, dz = np.abs(rx - cx), np.abs(ry - cy), np.abs(rz - cz)
    fix_x = (dx > dy) & (dx > dz)
    fix_y = (~fix_x) & (dy > dz)
    rx = np.where(fix_x, -ry - rz, rx)
    rz = np.where(~fix_x & ~fix_y, -rx - ry, rz)
    q_c, r_c = rx, rz
    # centre of each cell in pixels, distance from the pixel to its centre
    cxp = size * math.sqrt(3) * (q_c + r_c / 2)
    cyp = size * 1.5 * r_c
    dist = np.hypot(px - cxp, py - cyp) / size           # 0 centre .. ~1 corner
    edge = np.clip((dist - 0.80) / 0.2, 0, 1)             # outline weight
    # wave across the cape (and a slow second one) - whole-number cycles so it loops
    wave = 0.5 + 0.5 * np.sin(2 * math.pi * (cyp / h * 1.0 + cxp / w * 0.45 - phase))
    wave2 = 0.5 + 0.5 * np.sin(2 * math.pi * (cyp / h * 2.0 - cxp / w * 0.9 + phase * 2))
    active = np.clip(wave * 0.8 + wave2 * 0.35 - 0.25, 0, 1)
    hue = np.clip(cyp / h * 0.9 + cxp / w * 0.2, 0, 1)
    colour = np.array([0.1, 0.85, 1.0]) * (1 - hue[..., None]) + np.array([0.62, 0.3, 1.0]) * hue[..., None]
    img = np.zeros((h, w, 3)) + np.array([0.02, 0.03, 0.07])
    img += colour * (active ** 1.5 * 0.7 * (1 - dist * 0.6))[..., None]                # cell fill, brighter in the middle
    img += colour * (edge * (0.55 + 0.9 * active))[..., None]                 # outlines flare as the wave passes
    img += np.exp(-((y - (phase % 1.0)) ** 2) * 260)[..., None] * np.array([0.10, 0.22, 0.30])
    img += (np.sin(py * 1.6) * 0.5 + 0.5)[..., None] * 0.015                    # faint scanlines
    return img


# ---- Halloween (Afterlife) - drawn at 4x for detail ----

HS = 4                              # Halloween capes' own supersampling
HH, HW = H * HS, W * HS


def hcoords():
    y, x = np.mgrid[0:HH, 0:HW]
    return x / HW, y / HH


def smoothstep(e0, e1, v):
    t = np.clip((v - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def hnoise(cells, seed, octaves=5):
    return tile_noise(HH, HW, cells, seed, octaves)


def tonemap(img, exposure=1.0):
    """Soft highlight roll-off, so glows bloom instead of clipping."""
    return 1 - np.exp(-np.clip(img, 0, None) * exposure * 1.15)


def vignette(img, strength=0.45):
    x, y = hcoords()
    v = 1 - strength * (((x - 0.5) * 1.6) ** 2 + ((y - 0.5) * 1.1) ** 2)
    return img * np.clip(v, 0, 1)[..., None]


def periodic_flicker(phase, seed, beats=(3, 7, 11, 17)):
    """A smooth, irregular flicker that still loops (sum of whole-number sines)."""
    r = np.random.default_rng(seed)
    v = 0.0
    for b in beats:
        v += math.sin((phase * b + r.random()) * 2 * math.pi) / b
    return v


def moon_disc(cx, cy, radius, seed, tint, x, y):
    """A textured moon: craters from noise, darker limb. Returns (mask, colour)."""
    d = np.hypot((x - cx) * HW, (y - cy) * HH) / (radius * HW)
    mask = smoothstep(1.0, 0.985, d)
    craters = hnoise(4, seed, 5)
    fine = hnoise(10, seed + 1, 3)
    limb = np.sqrt(np.clip(1 - d ** 2, 0, 1))
    shade = (0.72 + 0.28 * limb) * (0.82 + 0.25 * craters - 0.12 * smoothstep(0.55, 0.75, fine))
    return mask, np.array(tint)[None, None, :] * shade[..., None]


# Haunted ----------------------------------------------------------------------------------------

HAUNTED = None


def ghost_layer(phase, gx, gy, size, speed, seed, x, y):
    """One ghost: alpha and an eye mask, in screen space. Head round, body tapering into a waving tail."""
    t = (phase * speed + seed) % 1
    cx = gx + 0.05 * math.sin((phase * speed + seed) * 2 * math.pi)
    cy = gy - 0.03 * math.sin((phase * speed * 2 + seed) * 2 * math.pi)
    r = size
    u = (x - cx) * HW / (r * HW)
    v = (y - cy) * HH / (r * HW)
    wave = phase * speed * 2 * math.pi + seed * 6
    sway = 0.28 * np.clip(v, 0, None) ** 1.4 * np.sin(wave + v * 2.2)
    width = np.where(v < 0, np.sqrt(np.clip(1 - v ** 2, 0, 1)), 1 - 0.38 * np.clip(v / 2.4, 0, 1))
    body = smoothstep(0.06, -0.04, np.abs(u - sway) - width) * (v > -1)
    hem = 2.3 + 0.18 * np.sin(u * 5 + wave * 1.5)
    body = body * smoothstep(hem, hem - 0.9, v)
    # Fade towards the tail, brighter in the head.
    alpha = body * (0.9 - 0.45 * np.clip(v / 2.3, 0, 1))
    eyes = np.zeros_like(alpha)
    for ex in (-0.36, 0.36):
        eyes = np.maximum(eyes, smoothstep(1.0, 0.75, np.hypot((u - ex) / 0.17, (v + 0.12) / 0.27)))
    wail = 0.18 + 0.06 * math.sin(wave * 2)
    eyes = np.maximum(eyes, smoothstep(1.0, 0.75, np.hypot(u / 0.13, (v - 0.45) / wail)))
    return alpha, eyes * (body > 0.3)


def haunted(phase):
    global HAUNTED
    x, y = hcoords()
    if HAUNTED is None:
        r = np.random.default_rng(151)

        def scene(d, k):
            Wk, Hk = HW * k, HH * k
            # Gravestones (mid-ground) and an iron fence (front).
            for gx, gw, gh, cross in ((0.16, 0.2, 0.15, False), (0.5, 0.15, 0.11, True), (0.84, 0.22, 0.17, False)):
                cx, base = gx * Wk, Hk * 0.93
                if cross:
                    d.rectangle([cx - gw * Wk * 0.09, base - gh * Hk * 1.4, cx + gw * Wk * 0.09, base], fill=255)
                    d.rectangle([cx - gw * Wk * 0.4, base - gh * Hk * 1.1, cx + gw * Wk * 0.4, base - gh * Hk * 0.92], fill=255)
                else:
                    d.rounded_rectangle([cx - gw * Wk / 2, base - gh * Hk, cx + gw * Wk / 2, base + 5], radius=gw * Wk / 2, fill=255)
            return None

        def fence(d, k):
            Wk, Hk = HW * k, HH * k
            top = Hk * 0.86
            d.rectangle([0, top + Hk * 0.03, Wk, top + Hk * 0.045], fill=255)
            d.rectangle([0, Hk * 0.97, Wk, Hk * 0.985], fill=255)
            for i in range(9):
                fx = (i + 0.5) / 9 * Wk
                d.rectangle([fx - k * 2.2, top, fx + k * 2.2, Hk], fill=255)
                d.polygon([(fx - k * 6, top), (fx + k * 6, top), (fx, top - k * 14)], fill=255)
        stars = r.random((70, 3))
        HAUNTED = (_hmask(scene), _hmask(fence), stars, hnoise(3, 152, 5), hnoise(5, 153, 4), hnoise(2, 154, 4), hnoise(6, 156, 3))
    graves, fence_m, stars, fogA, fogB, fogC, clouds = HAUNTED
    # Sky: deep navy to a sickly teal at the horizon.
    img = gradient(y, [(0, (6, 10, 26)), (0.55, (14, 34, 52)), (0.85, (30, 62, 70)), (1, (40, 72, 74))])
    for sx, sy, sp in stars:
        if sy > 0.6:
            continue
        tw = 0.35 + 0.65 * max(0, math.sin((phase * 2 + sp) * 2 * math.pi)) ** 2
        img += np.exp(-(((x - sx) * HW) ** 2 + ((y - sy) * HH) ** 2) / 3)[..., None] * tw * 0.7
    # The moon with a cool halo, clouds sliding across it.
    mm, mc = moon_disc(0.68, 0.16, 0.2, 157, (0.95, 0.97, 0.88), x, y)
    md = np.hypot((x - 0.68) * HW, (y - 0.16) * HH) / HW
    img += (np.exp(-np.clip(md - 0.2, 0, None) * 11) * 0.5)[..., None] * np.array([0.55, 0.75, 0.8])
    img = img * (1 - mm[..., None]) + mm[..., None] * mc
    cl = np.roll(clouds, int(phase * HW), axis=1)
    band = smoothstep(0.08, 0.0, np.abs(y - 0.2 - 0.04 * np.sin(x * 6))) * smoothstep(0.45, 0.7, cl)
    img = img * (1 - band[..., None] * 0.8) + band[..., None] * np.array([0.25, 0.32, 0.38]) * (0.6 + mm[..., None] * 0.6)
    # Far fog, behind the graves.
    f3 = np.roll(fogC, int(phase * HW), axis=1)
    img += (smoothstep(0.45, 0.95, y) * f3)[..., None] * np.array([0.3, 0.5, 0.5]) * 0.35
    # Far ghost (small, dim, behind the fog).
    for gx, gy, gs, sp, seed in ((0.25, 0.48, 0.07, 1, 0.3),):
        a, e = ghost_layer(phase, gx, gy, gs, sp, seed, x, y)
        img = img * (1 - a[..., None] * 0.5) + (a * 0.5)[..., None] * np.array([0.7, 0.95, 0.9]) * (1 - e[..., None] * 0.9)
    # Gravestones, rim-lit by the moon on their upper-right edges.
    rim = np.clip(graves - np.roll(np.roll(graves, 3, axis=0), -3, axis=1), 0, 1)
    img = img * (1 - graves[..., None]) + graves[..., None] * (np.array([0.06, 0.09, 0.11]) + 0.04 * hnoise(12, 158, 2)[..., None])
    img += rim[..., None] * np.array([0.55, 0.7, 0.72]) * 0.7
    # Mid fog in front of the graves.
    f2 = np.roll(fogB, int(phase * 2 * HW), axis=1)
    img += (smoothstep(0.65, 1.0, y) * smoothstep(0.4, 0.75, f2))[..., None] * np.array([0.45, 0.65, 0.62]) * 0.45
    # Two near ghosts drifting and swaying, glowing.
    for gx, gy, gs, sp, seed in ((0.36, 0.42, 0.15, 1, 0.0), (0.72, 0.58, 0.12, 1, 0.55)):
        a, e = ghost_layer(phase, gx, gy, gs, sp, seed, x, y)
        glow = blur(a, 7 * HS / 2) * 0.9 + blur(a, 2 * HS)
        img += glow[..., None] * np.array([0.25, 0.75, 0.62]) * 0.45
        body = np.array([0.78, 1.0, 0.95]) * (0.75 + 0.35 * (1 - y[..., None]))
        img = img * (1 - a[..., None]) + a[..., None] * body
        # Hollow eyes and mouth: dark, with a faint inner glow.
        img = img * (1 - e[..., None] * a[..., None]) + (e * a)[..., None] * np.array([0.02, 0.08, 0.07])
    # The fence in front, near fog over everything at the bottom.
    img = img * (1 - fence_m[..., None]) + fence_m[..., None] * np.array([0.015, 0.025, 0.03])
    f1 = np.roll(fogA, int(phase * 3 * HW), axis=1)
    img += (smoothstep(0.75, 1.0, y) * smoothstep(0.35, 0.8, f1))[..., None] * np.array([0.55, 0.75, 0.72]) * 0.5
    return vignette(tonemap(img * 1.25), 0.4)


def _hmask(draw):
    from PIL import ImageDraw
    big = Image.new("L", (HW * 2, HH * 2), 0)
    draw(ImageDraw.Draw(big), 2)
    return np.asarray(big.resize((HW, HH), Image.LANCZOS), dtype=float) / 255


def _hblur(arr, radius):
    return blur(arr, radius)


# Jack o'Lantern ----------------------------------------------------------------------------------

PUMPKIN = None


def jack_o_lantern(phase):
    global PUMPKIN
    x, y = hcoords()
    cx, cy, rx, ry = 0.5, 0.6, 0.47, 0.19
    if PUMPKIN is None:
        def face(d, k):
            Wk, Hk = HW * k, HH * k
            def P(px, py):
                # 70% of the first design, a touch lower on the pumpkin.
                return (Wk * (cx + px * rx * 0.7), Hk * (cy + 0.02 + py * ry * 2 * 0.7))
            d.polygon([P(-0.62, -0.08), P(-0.18, -0.06), P(-0.42, -0.42)], fill=255)
            d.polygon([P(0.18, -0.06), P(0.62, -0.08), P(0.42, -0.42)], fill=255)
            d.polygon([P(-0.1, 0.06), P(0.1, 0.06), P(0, -0.1)], fill=255)
            teeth = [P(-0.7, 0.12)]
            for i in range(9):
                fx = -0.7 + 1.4 * (i + 0.5) / 9
                teeth.append(P(fx, 0.2 if i % 2 == 0 else 0.3))
            teeth += [P(0.7, 0.12), P(0.45, 0.42), P(0.12, 0.36), P(0, 0.44), P(-0.12, 0.36), P(-0.45, 0.42)]
            d.polygon(teeth, fill=255)

        def stem(d, k):
            Wk, Hk = HW * k, HH * k
            top = Hk * (cy - ry * 0.93)
            d.polygon([(Wk * 0.47, top + 8 * k), (Wk * 0.53, top + 8 * k), (Wk * 0.56, top - Hk * 0.05), (Wk * 0.62, top - Hk * 0.09),
                       (Wk * 0.6, top - Hk * 0.1), (Wk * 0.52, top - Hk * 0.06), (Wk * 0.48, top - Hk * 0.06)], fill=255)
        PUMPKIN = (_hmask(face), _hmask(stem), hnoise(14, 161, 3), hnoise(4, 162, 4), hnoise(3, 163, 4),
                   np.random.default_rng(164).random((14, 4)))
    face0, stem0, grain0, smoke, fog, flies = PUMPKIN
    # The pumpkin rocks on its base: sample its masks through a small rotation about the bottom.
    rock = 0.07 * math.sin(phase * 2 * math.pi * 2)
    px0, py0 = cx, cy + ry
    dx, dy = (x - px0) * HW, (y - py0) * HH
    xr = px0 + (dx * math.cos(rock) + dy * math.sin(rock)) / HW
    yr = py0 + (-dx * math.sin(rock) + dy * math.cos(rock)) / HH
    ix = np.clip((xr * HW).astype(int), 0, HW - 1)
    iy = np.clip((yr * HH).astype(int), 0, HH - 1)
    face, stem, grain = face0[iy, ix], stem0[iy, ix], grain0[iy, ix]
    # Candle flicker, and once a loop the flame flares up.
    flare = math.exp(-(((phase - 0.55) % 1) * 9))
    flick = 0.8 + 0.2 * periodic_flicker(phase, 165) + 0.9 * flare
    # Night: dark violet sky (a distant lightning flash once a loop), a low mist, the ground.
    img = gradient(y, [(0, (8, 4, 14)), (0.6, (20, 10, 26)), (0.78, (30, 16, 26)), (1, (16, 8, 10))])
    t_flash = (phase - 0.2) % 1
    flash = (math.exp(-t_flash * 25) + 0.6 * math.exp(-abs(t_flash - 0.06) * 60)) if t_flash < 0.3 else 0
    img += (smoothstep(0.75, 0.0, y) * flash)[..., None] * np.array([0.45, 0.4, 0.7]) * 0.5
    img += (smoothstep(0.55, 0.85, y) * np.roll(fog, int(phase * HW), axis=1))[..., None] * np.array([0.25, 0.18, 0.3]) * 0.4
    # Pumpkin body: an ellipsoid of lobes, shaded.
    # Body: wider than tall (half-width rx of the width, half-height ry of the height), lobed.
    u = (xr - cx) / rx
    v = (yr - cy) / ry
    lobes = 7
    lobe = np.cos(np.arcsin(np.clip(u, -0.999, 0.999)) * lobes) * 0.5 + 0.5        # 1 on lobe centres, 0 in grooves
    outline = 1 - 0.045 * (1 - lobe) * (np.abs(v) < 0.9)
    inside = smoothstep(outline, outline - 0.03, np.hypot(u, v))
    nz = np.sqrt(np.clip(1 - u ** 2 - v ** 2, 0, 1))
    light = np.clip(-0.45 * u - 0.6 * v + 0.65 * nz, 0, 1)                         # moonlight from top-left
    groove = np.exp(-((1 - lobe) ** 2) * 0) * smoothstep(0.25, 0.0, lobe)
    skin = np.array([0.92, 0.38, 0.05]) * (0.3 + 0.7 * light[..., None]) * (0.4 + 0.6 * lobe[..., None] ** 0.6)
    skin *= (1 - 0.55 * groove[..., None])
    skin *= (0.88 + 0.18 * grain[..., None])
    spec = np.clip(light, 0, 1) ** 18 * lobe * 0.6
    skin += spec[..., None] * np.array([1.0, 0.85, 0.6])
    # Warm light bleeding through the thin skin around the openings.
    bleed = blur(face, 9 * HS / 2)
    skin += bleed[..., None] * np.array([1.0, 0.5, 0.1]) * 0.55 * flick
    img = img * (1 - inside[..., None]) + inside[..., None] * skin
    # The carved openings: the cut wall (pale flesh, lit) along the bottom/inner edge, glowing core.
    wall = np.clip(np.roll(face, -int(2.5 * HS), axis=0) - face, 0, 1) * inside
    # Hot in the middle of each opening, deep orange towards its edges.
    centre = smoothstep(0.35, 0.95, blur(face, 2 * HS))
    core = (np.array([1.0, 0.45, 0.06]) * (1 - centre[..., None]) + np.array([1.0, 0.85, 0.4]) * centre[..., None]) * (0.85 + 0.3 * flick)
    img = img * (1 - face[..., None]) + face[..., None] * core
    img = img * (1 - wall[..., None]) + wall[..., None] * np.array([1.0, 0.72, 0.3]) * flick
    # Stem.
    st = stem * (1 - inside * 0.0)
    stem_col = np.array([0.32, 0.36, 0.12]) * (0.6 + 0.6 * smoothstep(0.62, 0.45, xr))[..., None] * (0.8 + 0.25 * grain[..., None])
    img = img * (1 - st[..., None]) + st[..., None] * stem_col
    # Glow from the face out into the night, and a warm pool of light on the ground.
    img += blur(face, 14 * HS / 2)[..., None] * np.array([1.0, 0.55, 0.12]) * 1.1 * flick
    pool = np.exp(-(((x - 0.5) / 0.5) ** 2 + ((y - 0.84) / 0.05) ** 2)) * (y > cy)
    img += pool[..., None] * np.array([1.0, 0.45, 0.1]) * 0.35 * flick
    # Green ghost fire licking up from behind the lid, taller when the flame flares.
    sm = np.roll(smoke, -int(phase * 2 * HH), axis=0)
    sm = np.roll(sm, int(5 * HS * math.sin(phase * 4 * math.pi)), axis=1)
    top = cy - ry * 0.9
    height = 0.18 + 0.12 * flare
    column = np.exp(-((x - 0.5) / (0.16 * smoothstep(top - height, top, y) + 0.02)) ** 2) * smoothstep(top - height, top, y) * (y < top + 0.02)
    tongues = smoothstep(0.42, 0.7, sm) * column
    img += tongues[..., None] * np.array([0.35, 1.0, 0.55]) * 0.9
    img += blur(tongues, 4 * HS)[..., None] * np.array([0.2, 0.8, 0.4]) * 0.6
    # Sparks bursting out of the eyes and mouth during the flare.
    burst = np.random.default_rng(166)
    for k in range(18):
        sx_, sy_ = burst.uniform(0.33, 0.67), burst.uniform(cy - 0.06, cy + 0.08)
        ang, spd = burst.uniform(-2.7, -0.45), burst.uniform(0.6, 1.1)
        t = (phase - 0.55) % 1
        if t > 0.35:
            continue
        ex_ = sx_ + math.cos(ang) * spd * t
        ey_ = sy_ + math.sin(ang) * spd * t + 0.6 * t * t
        img += np.exp(-(((x - ex_) * HW) ** 2 + ((y - ey_) * HH) ** 2) / 10)[..., None] * np.array([1.0, 0.7, 0.25]) * (1 - t / 0.35) * 1.4
    # Autumn leaves tumbling down.
    leaves = np.random.default_rng(167).random((7, 4))
    for lx, lp, lsz, lc in leaves:
        t = (phase + lp) % 1
        lx_ = (lx + 0.12 * math.sin((t + lp) * 2 * math.pi * 2)) % 1
        ly_ = t * 1.1 - 0.05
        spin = (t * 3 + lp) * 2 * math.pi
        du, dv = (x - lx_) * HW, (y - ly_) * HH
        a_ = du * math.cos(spin) + dv * math.sin(spin)
        b_ = -du * math.sin(spin) + dv * math.cos(spin)
        size_ = (4 + lsz * 4) * HS
        leaf = smoothstep(1.0, 0.8, np.hypot(a_ / size_, b_ / (size_ * 0.45 * (0.4 + 0.6 * abs(math.cos(spin * 0.5))))))
        colour = np.array([0.85, 0.35, 0.05]) if lc < 0.5 else np.array([0.6, 0.18, 0.04])
        img = img * (1 - leaf[..., None]) + leaf[..., None] * colour * 0.8
    # Fireflies / sparks drifting.
    for fx, fy, fp, fs in flies:
        px_ = (fx + 0.05 * math.sin((phase + fp) * 2 * math.pi * 2)) % 1
        py_ = (fy * 0.7 + 0.05 - 0.04 * math.sin((phase + fp) * 2 * math.pi)) % 1
        blink = max(0, math.sin((phase * 2 + fp) * 2 * math.pi)) ** 3
        img += np.exp(-(((x - px_) * HW) ** 2 + ((y - py_) * HH) ** 2) / (6 + fs * 8))[..., None] * np.array([1.0, 0.75, 0.3]) * blink
    return vignette(tonemap(img * 1.2), 0.5)


# Witching Hour -----------------------------------------------------------------------------------

WITCH = None


def bat_mask(cx, cy, size, flap, x, y):
    """A bat: scalloped wings that rise and fall with {flap} (-1..1), a small body with ears."""
    u = (x - cx) * HW / (size * HW)
    v = (y - cy) * HH / (size * HW)
    au = np.abs(u)
    lift = flap * 0.5
    # Wing: between a leading edge (arching up with the flap) and a scalloped trailing edge.
    lead = -lift * au ** 1.2 - 0.05
    trail = lead + 0.42 * (1 - au) + 0.13 * np.abs(np.sin(au * math.pi * 3)) - 0.02
    wing = (au < 1) * smoothstep(-0.02, 0.02, v - lead) * smoothstep(0.02, -0.02, v - trail)
    body = smoothstep(1.0, 0.9, np.hypot(u / 0.13, (v - 0.1) / 0.24))
    ears = (smoothstep(0.02, -0.02, np.abs(au - 0.07) - 0.045 * (0.1 - v) / 0.18) * (v < -0.05) * (v > -0.2))
    return np.clip(wing + body + ears, 0, 1)


def witching_hour(phase):
    global WITCH
    x, y = hcoords()
    if WITCH is None:
        r = np.random.default_rng(171)

        def castle(d, k):
            Wk, Hk = HW * k, HH * k
            base = Hk * 0.8
            d.polygon([(0, base), (Wk * 0.15, base - Hk * 0.05), (Wk * 0.4, base - Hk * 0.03), (Wk * 0.7, base - Hk * 0.07), (Wk, base - Hk * 0.02),
                       (Wk, Hk), (0, Hk)], fill=255)
            for tx, tw, th in ((0.62, 0.05, 0.2), (0.7, 0.07, 0.27), (0.79, 0.05, 0.17), (0.86, 0.04, 0.13)):
                left, right, top = Wk * (tx - tw / 2), Wk * (tx + tw / 2), base - Hk * th
                d.rectangle([left, top, right, base], fill=255)
                d.polygon([(left - tw * Wk * 0.15, top), (right + tw * Wk * 0.15, top), (Wk * tx, top - Hk * 0.06)], fill=255)
            d.rectangle([Wk * 0.6, base - Hk * 0.12, Wk * 0.88, base], fill=255)

        def tree(d, k):
            Wk, Hk = HW * k, HH * k

            def branch(x0, y0, angle, length, width, depth):
                if depth == 0 or length < 2 * k:
                    return
                pts = [(x0, y0)]
                xx, yy, a = x0, y0, angle
                for _ in range(4):
                    a += r.uniform(-0.25, 0.25)
                    xx, yy = xx + math.cos(a) * length / 4, yy + math.sin(a) * length / 4
                    pts.append((xx, yy))
                d.line(pts, fill=255, width=max(1, int(width)), joint="curve")
                for _ in range(2 if depth > 2 else 3):
                    branch(xx, yy, a + r.uniform(-0.8, 0.8), length * r.uniform(0.55, 0.78), width * 0.62, depth - 1)
            branch(Wk * 0.16, Hk * 1.02, -math.pi / 2 + 0.2, Hk * 0.3, 11 * k, 7)
            d.polygon([(0, Hk * 0.93), (Wk * 0.4, Hk * 0.96), (Wk, Hk * 0.94), (Wk, Hk), (0, Hk)], fill=255)
        bats = [(r.uniform(0, 1), r.uniform(0.1, 0.62), r.uniform(0.05, 0.13), r.uniform(0, 1), int(r.integers(1, 3))) for _ in range(8)]
        WITCH = (_hmask(castle), _hmask(tree), bats, hnoise(5, 172, 5), hnoise(3, 173, 4), np.random.default_rng(174).random((60, 3)))
    castle_m, tree_m, bats, clouds, fog, stars = WITCH
    img = gradient(y, [(0, (22, 6, 34)), (0.35, (70, 14, 50)), (0.65, (150, 40, 34)), (0.85, (90, 22, 30)), (1, (40, 8, 18))])
    for sx, sy, sp in stars:
        if sy > 0.35:
            continue
        tw = 0.3 + 0.7 * max(0, math.sin((phase * 2 + sp) * 2 * math.pi)) ** 2
        img += np.exp(-(((x - sx) * HW) ** 2 + ((y - sy) * HH) ** 2) / 3)[..., None] * tw * 0.6
    # The harvest moon: big, cratered, glowing; a halo ring.
    mcx, mcy, mr = 0.55, 0.42, 0.36
    mm, mc = moon_disc(mcx, mcy, mr, 175, (1.0, 0.6, 0.2), x, y)
    md = np.hypot((x - mcx) * HW, (y - mcy) * HH) / HW
    img += (np.exp(-np.clip(md - mr, 0, None) * 7) * 0.55)[..., None] * np.array([1.0, 0.5, 0.15])
    img += (np.exp(-((md - mr * 1.35) ** 2) * 900) * 0.12)[..., None] * np.array([1.0, 0.7, 0.4])
    img = img * (1 - mm[..., None]) + mm[..., None] * mc * 1.15
    # Thin clouds drifting across the moon, lit at their edges.
    cl = np.roll(clouds, int(phase * HW), axis=1)
    strip = smoothstep(0.1, 0.0, np.abs(y - 0.5 - 0.05 * np.sin(x * 5 + 1)))
    dens = smoothstep(0.5, 0.75, cl) * strip
    edge = np.clip(dens - np.roll(dens, 2 * HS, axis=0), 0, 1)
    img = img * (1 - dens[..., None] * 0.85) + dens[..., None] * np.array([0.18, 0.05, 0.1])
    img += edge[..., None] * np.array([1.0, 0.55, 0.2]) * (0.4 + mm[..., None])
    # Far bats (small, hazy), the castle in the haze, then near bats, the tree, fog.
    def draw_bats(sel, colour, soft):
        nonlocal img
        layer = np.zeros_like(x)
        for bx, by, bs, bp, sp in sel:
            t = (bx + phase * sp) % 1.3 - 0.15
            cyy = by + 0.025 * math.sin((phase * sp * 2 + bp) * 2 * math.pi)
            flap = math.sin((phase * 6 * sp + bp) * 2 * math.pi)
            layer = np.maximum(layer, bat_mask(t, cyy, bs, flap, x, y))
        if soft:
            layer = blur(layer, soft)
        img = img * (1 - layer[..., None]) + layer[..., None] * np.array(colour)
    far = [b for b in bats if b[2] < 0.08]
    near = [b for b in bats if b[2] >= 0.08]
    draw_bats(far, (0.22, 0.06, 0.12), 1)
    haze = np.array([0.32, 0.1, 0.16])
    img = img * (1 - castle_m[..., None]) + castle_m[..., None] * haze
    draw_bats(near, (0.03, 0.01, 0.03), 0)
    img = img * (1 - tree_m[..., None]) + tree_m[..., None] * np.array([0.02, 0.005, 0.02])
    f = np.roll(fog, int(phase * 2 * HW), axis=1)
    img += (smoothstep(0.78, 1.0, y) * smoothstep(0.4, 0.8, f))[..., None] * np.array([0.5, 0.2, 0.3]) * 0.4
    return vignette(tonemap(img * 1.15), 0.45)


# ---- Christmas (Yuletide) - drawn at 4x like the Halloween ones ----

def snow_layer(phase, seed, count, size, speed, drift, x, y):
    """Falling snowflakes that loop: each falls a whole number of cape heights per loop."""
    r = np.random.default_rng(seed)
    layer = np.zeros_like(x)
    for _ in range(count):
        sx, sy, sp = r.random(), r.random(), r.random()
        fy = (sy + phase * speed) % 1.0
        fx = (sx + drift * math.sin((phase * speed + sp) * 2 * math.pi)) % 1.0
        d2 = ((x - fx) * HW) ** 2 + ((y - fy) * HH) ** 2
        layer = np.maximum(layer, np.exp(-d2 / (size * size)))
    return layer


SILENT = None


def silent_night(phase):
    global SILENT
    x, y = hcoords()
    if SILENT is None:
        r = np.random.default_rng(301)

        def pines(k, base, height, count, seed):
            rr = np.random.default_rng(seed)

            def draw(d, s):
                Wk, Hk = HW * s, HH * s
                d.rectangle([0, Hk * base, Wk, Hk], fill=255)
                for _ in range(count):
                    cx = rr.uniform(-0.05, 1.05) * Wk
                    h = rr.uniform(0.7, 1.0) * height * Hk
                    w = h * 0.42
                    top = Hk * base - h
                    for tier in range(4):
                        t0 = top + h * tier * 0.22
                        tw = w * (0.45 + tier * 0.2)
                        d.polygon([(cx, t0), (cx - tw / 2, t0 + h * 0.34), (cx + tw / 2, t0 + h * 0.34)], fill=255)
            return draw

        def cabin(d, s):
            Wk, Hk = HW * s, HH * s
            bx, by, bw, bh = Wk * 0.52, Hk * 0.78, Wk * 0.3, Hk * 0.1
            d.rectangle([bx, by, bx + bw, by + bh], fill=255)
            d.polygon([(bx - bw * 0.1, by), (bx + bw / 2, by - bh * 0.75), (bx + bw * 1.1, by)], fill=255)
            d.rectangle([bx + bw * 0.72, by - bh * 0.85, bx + bw * 0.84, by - bh * 0.3], fill=255)

        def windows(d, s):
            Wk, Hk = HW * s, HH * s
            bx, by, bw, bh = Wk * 0.52, Hk * 0.78, Wk * 0.3, Hk * 0.1
            for wx in (0.15, 0.62):
                d.rectangle([bx + bw * wx, by + bh * 0.3, bx + bw * (wx + 0.2), by + bh * 0.7], fill=255)

        SILENT = (_hmask(pines(2, 0.86, 0.32, 9, 302)), _hmask(pines(1, 0.95, 0.24, 7, 303)), _hmask(cabin), _hmask(windows),
                  r.random((70, 3)), hnoise(3, 304, 4))
    far, near, cabin_m, win_m, stars, aurora = SILENT
    img = gradient(y, [(0, (6, 10, 32)), (0.45, (16, 34, 74)), (0.75, (34, 62, 108)), (1, (60, 86, 130))])
    for sx, sy, sp in stars:
        if sy > 0.55:
            continue
        tw = 0.25 + 0.75 * max(0, math.sin((phase * 2 + sp) * 2 * math.pi)) ** 3
        img += np.exp(-(((x - sx) * HW) ** 2 + ((y - sy) * HH) ** 2) / 2.5)[..., None] * tw * 0.8
    # A soft green veil of aurora drifting across the top.
    au = np.roll(aurora, int(phase * HW), axis=1)
    band = np.exp(-((y - 0.22 - 0.06 * np.sin(x * 6 + phase * 2 * math.pi)) ** 2) * 60) * smoothstep(0.4, 0.8, au)
    img += band[..., None] * np.array([0.15, 0.55, 0.4]) * 0.6
    # The Christmas star, top left, pulsing.
    pulse = 0.75 + 0.25 * math.sin(phase * 2 * math.pi)
    sx, sy = 0.25, 0.12
    dx, dy = (x - sx) * HW, (y - sy) * HH
    star = np.exp(-(dx ** 2 + dy ** 2) / 20) + 0.5 * np.exp(-dx ** 2 / 2) * np.exp(-dy ** 2 / 900) + 0.5 * np.exp(-dy ** 2 / 2) * np.exp(-dx ** 2 / 900)
    img += star[..., None] * np.array([1.0, 0.92, 0.7]) * pulse
    # Far trees (blue, snowy tips), then the cabin with warm windows, near trees.
    img = img * (1 - far[..., None]) + far[..., None] * np.array([0.12, 0.2, 0.3])
    snowcap = np.clip(far - np.roll(far, 3 * HS, axis=0), 0, 1)
    img += snowcap[..., None] * np.array([0.5, 0.6, 0.7]) * 0.6
    img = img * (1 - cabin_m[..., None]) + cabin_m[..., None] * np.array([0.08, 0.06, 0.07])
    flicker = 0.85 + 0.15 * math.sin(phase * 14 * math.pi)
    glow = blur(win_m, 6)
    img += glow[..., None] * np.array([1.0, 0.6, 0.2]) * 0.8 * flicker
    img = img * (1 - win_m[..., None]) + win_m[..., None] * np.array([1.0, 0.75, 0.35]) * flicker
    img = img * (1 - near[..., None]) + near[..., None] * np.array([0.04, 0.08, 0.12])
    nearcap = np.clip(near - np.roll(near, 3 * HS, axis=0), 0, 1)
    img += nearcap[..., None] * np.array([0.8, 0.85, 0.95]) * 0.7
    # Snow in three depths.
    for seed, count, size, speed, drift, b in ((311, 60, 1.2, 1, 0.01, 0.5), (312, 35, 2.0, 2, 0.02, 0.8), (313, 14, 3.4, 3, 0.03, 1.0)):
        img += snow_layer(phase, seed, count, size * HS / 2, speed, drift, x, y)[..., None] * b
    return vignette(tonemap(img * 1.05), 0.35)


def catenary(x0, y0, x1, y1, sag, n=40):
    pts = []
    for i in range(n + 1):
        t = i / n
        pts.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + sag * 4 * t * (1 - t)))
    return pts


LIGHTS = None


def festive_lights(phase):
    global LIGHTS
    x, y = hcoords()
    if LIGHTS is None:
        strands = []
        bulbs = []
        r = np.random.default_rng(321)
        for k, base in enumerate((0.1, 0.33, 0.56, 0.79)):
            y0 = base + r.uniform(-0.02, 0.02)
            y1 = base + r.uniform(-0.03, 0.03)
            pts = catenary(-0.05, y0, 1.05, y1, 0.08 + 0.02 * k)
            strands.append(pts)
            for i in range(2, len(pts) - 2, 4):
                bulbs.append((pts[i][0], pts[i][1] + 0.015, (i // 4 + k) % 4, r.random()))

        def wires(d, s):
            for pts in strands:
                d.line([(px * HW * s, py * HH * s) for px, py in pts], fill=255, width=int(2 * s), joint="curve")
        LIGHTS = (_hmask(wires), bulbs, hnoise(6, 322, 4))
    wire, bulbs, velvet = LIGHTS
    # Deep pine-green velvet with a soft sheen.
    img = np.zeros((HH, HW, 3)) + np.array([0.02, 0.07, 0.04]) + (velvet[..., None] - 0.5) * 0.04
    img += np.exp(-((x * 0.8 + y - (phase * 2.2 - 0.6)) ** 2) * 10)[..., None] * np.array([0.03, 0.08, 0.05])
    img = img * (1 - wire[..., None]) + wire[..., None] * np.array([0.05, 0.12, 0.06])
    palette = [np.array([1.0, 0.18, 0.15]), np.array([1.0, 0.75, 0.2]), np.array([0.2, 0.9, 0.35]), np.array([0.3, 0.55, 1.0])]
    glow = np.zeros((HH, HW, 3))
    core = np.zeros((HH, HW, 3))
    for bx, by, c, seed in bulbs:
        # A chase that runs along the strands twice per loop, plus each bulb's own twinkle.
        chase = 0.5 + 0.5 * math.cos(2 * math.pi * (phase * 2 - bx - c * 0.25))
        twinkle = 0.65 + 0.35 * math.sin(2 * math.pi * (phase * 3 + seed))
        on = 0.35 + 0.65 * chase * twinkle
        d2 = ((x - bx) * HW) ** 2 + (((y - by) * HH) * 0.75) ** 2
        b = np.exp(-d2 / (5.0 * HS))
        core += b[..., None] * palette[c] * on
        glow += np.exp(-d2 / (90.0 * HS))[..., None] * palette[c] * on
    img += glow * 0.9 + core * 1.6
    img += snow_layer(phase, 323, 30, 1.5 * HS / 2, 1, 0.015, x, y)[..., None] * 0.35
    return vignette(tonemap(img), 0.3)


# ---- Velo Staff: only staff members get it ----

# ---- Nitro Booster: for server boosters ----



# ---- shared helpers for the high-detail capes ----

def emboss(mask, radius, light_angle, strength=1.0):
    """Lighting for a raised shape: (diffuse, specular) from a blurred height field lit from {light_angle}."""
    height = blur(mask, radius)
    gy, gx = np.gradient(height)
    lx, ly = math.cos(light_angle), math.sin(light_angle)
    shade = np.clip(0.5 + (gx * lx + gy * ly) * 18 * strength, 0, 1)
    spec = np.clip((gx * lx + gy * ly) * 30 * strength, 0, 1) ** 3
    return shade, spec


def logo_mask_h(scale=0.86, top=0.12):
    size = int(HW * scale)
    img = Image.open(ROOT / "Logo.png").convert("RGBA").resize((size, size), Image.LANCZOS)
    mask = np.zeros((HH, HW))
    oy, ox = int(HH * top), (HW - size) // 2
    mask[oy:oy + size, ox:ox + size] = np.asarray(img)[:, :, 3] / 255
    return mask, (ox + size / 2) / HW, (oy + size / 2) / HH


GIFT2 = None


def gift_wrap(phase):
    from PIL import ImageDraw
    global GIFT2
    x, y = hcoords()
    if GIFT2 is None:
        W2, H2 = HW * 2, HH * 2

        def pattern(d, s):
            step = W2 / 3.2
            for j in range(-1, 8):
                for i in range(-1, 5):
                    cx = i * step + (step / 2 if j % 2 else 0)
                    cy = j * step * 0.9
                    if (i + j) % 2 == 0:
                        for a in range(6):
                            ang = a * math.pi / 3
                            ex, ey = cx + math.cos(ang) * step * 0.2, cy + math.sin(ang) * step * 0.2
                            d.line([(cx, cy), (ex, ey)], fill=255, width=5)
                            mx, my = cx + math.cos(ang) * step * 0.12, cy + math.sin(ang) * step * 0.12
                            for side in (-0.7, 0.7):
                                d.line([(mx, my), (mx + math.cos(ang + side) * step * 0.07, my + math.sin(ang + side) * step * 0.07)], fill=255, width=4)
                    else:
                        pts = []
                        for k in range(10):
                            rr = step * (0.13 if k % 2 == 0 else 0.055)
                            ang = -math.pi / 2 + k * math.pi / 5
                            pts.append((cx + math.cos(ang) * rr, cy + math.sin(ang) * rr))
                        d.polygon(pts, fill=255)
        pat = Image.new("L", (W2, H2), 0)
        pattern(ImageDraw.Draw(pat), 2)
        pat_m = np.asarray(pat.resize((HW, HH), Image.LANCZOS), dtype=float) / 255

        def ribbon(d):
            d.rectangle([W2 * 0.41, 0, W2 * 0.59, H2], fill=255)
            d.rectangle([0, H2 * 0.34, W2, H2 * 0.45], fill=255)
        rib = Image.new("L", (W2, H2), 0)
        ribbon(ImageDraw.Draw(rib))
        rib_m = np.asarray(rib.resize((HW, HH), Image.LANCZOS), dtype=float) / 255
        bow = Image.new("L", (W2, H2), 0)
        bd = ImageDraw.Draw(bow)
        cx, cy = W2 * 0.5, H2 * 0.39
        for side in (-1, 1):
            loop = [(cx, cy)]
            for k in range(25):
                t = k / 24 * math.pi
                loop.append((cx + side * (W2 * 0.27) * math.sin(t) * (0.6 + 0.4 * math.sin(t)), cy - H2 * 0.11 * math.sin(t * 1.0) * math.cos(t - 0.6) - H2 * 0.03 * math.sin(t)))
            bd.polygon(loop, fill=255)
            bd.polygon([(cx, cy), (cx + side * W2 * 0.22, cy + H2 * 0.19), (cx + side * W2 * 0.13, cy + H2 * 0.2), (cx + side * W2 * 0.04, cy + H2 * 0.04)], fill=255)
        bd.ellipse([cx - W2 * 0.065, cy - H2 * 0.045, cx + W2 * 0.065, cy + H2 * 0.035], fill=255)
        bow_m = np.asarray(bow.resize((HW, HH), Image.LANCZOS), dtype=float) / 255
        GIFT2 = (pat_m, rib_m, bow_m, hnoise(10, 451, 3))
    pat_m, rib_m, bow_m, paper = GIFT2
    # Deep red paper with a fine grain and a soft fold shading.
    img = np.zeros((HH, HW, 3)) + np.array([0.6, 0.04, 0.07]) * (0.85 + 0.25 * paper[..., None])
    img *= (0.9 + 0.1 * np.sin(x * 9 + 1))[..., None]
    # Silver foil pattern that glints as the light passes.
    shine = np.exp(-((x * 0.7 + y - (phase * 2.4 - 0.7)) ** 2) * 14)
    foil_shade, foil_spec = emboss(pat_m, 1.5, -2.2, 0.8)
    img = img * (1 - pat_m[..., None] * 0.85) + pat_m[..., None] * (np.array([0.62, 0.6, 0.66]) * (0.5 + foil_shade[..., None] * 0.7) + shine[..., None] * 0.8)
    # Gold satin ribbon with stripes, lit; the bow embossed and glinting.
    gold_dark = np.array([0.55, 0.36, 0.08])
    gold = np.array([1.0, 0.76, 0.28])
    stripe = 0.85 + 0.15 * np.sin((x + y) * 140)
    rib_shade, rib_spec = emboss(rib_m, 3, -2.0, 0.7)
    img = img * (1 - rib_m[..., None]) + rib_m[..., None] * (gold_dark + (gold - gold_dark) * (rib_shade * stripe)[..., None] + shine[..., None] * 0.6)
    img -= blur(np.clip(np.roll(bow_m, 5 * HS, axis=0) - bow_m, 0, 1), 4)[..., None] * 0.35
    bow_shade, bow_spec = emboss(bow_m, 6, -2.2 + 0.3 * math.sin(phase * 2 * math.pi), 1.2)
    img = img * (1 - bow_m[..., None]) + bow_m[..., None] * (gold_dark + (gold - gold_dark) * bow_shade[..., None] + bow_spec[..., None] * 1.2 + shine[..., None] * 0.5)
    # Glints popping on the foil and snow drifting down.
    r = np.random.default_rng(452)
    for _ in range(22):
        sx, sy, sp = r.random(3)
        tw = max(0, math.sin((phase * 3 + sp) * 2 * math.pi)) ** 12
        if tw < 0.02:
            continue
        dx, dy = (x - sx) * HW, (y - sy) * HH
        img += (np.exp(-dx ** 2 / 2) * np.exp(-dy ** 2 / 90) + np.exp(-dy ** 2 / 2) * np.exp(-dx ** 2 / 90))[..., None] * tw * 1.2
    img += snow_layer(phase, 453, 40, 1.4 * HS / 2, 1, 0.012, x, y)[..., None] * 0.55
    img += snow_layer(phase, 454, 14, 2.6 * HS / 2, 2, 0.02, x, y)[..., None] * 0.8
    return vignette(tonemap(img * 1.1), 0.28)


STAFF3 = None


def velo_staff(phase):
    """Minimal and calm: matte black, the Velo logo in flat white, a thin red line and STAFF in spaced letters."""
    from PIL import ImageDraw, ImageFont
    global STAFF3
    x, y = hcoords()
    if STAFF3 is None:
        logo, lcx, lcy = logo_mask_h(0.58, 0.16)
        W2, H2 = HW * 2, HH * 2
        text = Image.new("L", (W2, H2), 0)
        # Large, tracked-out letters across ~74% of the width: readable on a player's back, not a smudge.
        font = ImageFont.truetype(str(ROOT / "launcher/src/main/resources/net/veloclient/launcher/fonts/Inter-Bold.ttf"), int(H2 * 0.088))
        d = ImageDraw.Draw(text)
        letters = "STAFF"
        widths = [d.textbbox((0, 0), ch, font=font)[2] - d.textbbox((0, 0), ch, font=font)[0] for ch in letters]
        target = W2 * 0.74
        gap = (target - sum(widths)) / (len(letters) - 1)
        bb = d.textbbox((0, 0), "S", font=font)
        cx = (W2 - target) / 2
        for ch, w in zip(letters, widths):
            cb = d.textbbox((0, 0), ch, font=font)
            d.text((cx - cb[0], H2 * 0.655 - bb[1]), ch, font=font, fill=255)
            cx += w + gap
        text_m = np.asarray(text.resize((HW, HH), Image.LANCZOS), dtype=float) / 255
        STAFF3 = (logo, text_m, hnoise(40, 461, 2))
    logo, text_m, grain = STAFF3
    red = np.array([0.95, 0.16, 0.22])
    # Matte black with a soft top light and a fine grain.
    img = np.zeros((HH, HW, 3)) + np.array([0.045, 0.045, 0.05]) + (grain[..., None] - 0.5) * 0.025
    img += np.exp(-(((x - 0.5) * 1.6) ** 2 + ((y - 0.28) * 2.2) ** 2) * 3)[..., None] * np.array([0.05, 0.05, 0.06])
    # A thin red line under the logo, its light breathing; a short highlight travelling along it.
    line = np.exp(-((y - 0.6) * HH) ** 2 / 2.0) * smoothstep(0.08, 0.2, x) * smoothstep(0.92, 0.8, x)
    # The letters get a faint red underglow so they read as lit, not printed.
    img += blur(text_m, 5)[..., None] * red * 0.22
    breathe = 0.7 + 0.3 * math.sin(phase * 2 * math.pi)
    glint_x = (phase * 1.4 - 0.2)
    glint = np.exp(-((x - glint_x) * 9) ** 2)
    img += line[..., None] * red * (0.9 * breathe + glint[..., None] * 1.5) + blur(line, 3)[..., None] * red * 0.4 * breathe
    # The logo in flat white, with one slow light sweep across it.
    sweep = np.exp(-((x + y * 0.5 - (phase * 2.0 - 0.4)) * 7) ** 2)
    # (Values above 1: the tonemap below rolls them off to a clean white instead of grey.)
    img = img * (1 - logo[..., None]) + logo[..., None] * (np.array([1.75, 1.75, 1.82]) + sweep[..., None] * 0.8)
    img = img * (1 - text_m[..., None]) + text_m[..., None] * (np.array([1.9, 1.9, 1.98]) + sweep[..., None] * 0.4)
    # A red seam along the bottom edge.
    img += np.exp(-((y - 0.985) * HH) ** 2 / 6)[..., None] * red * 0.8
    return vignette(tonemap(img), 0.25)


def booster(phase):
    """Soft and bright: a pink-to-violet glow, out-of-focus orbs drifting up, a thin glowing gem outline."""
    from PIL import ImageDraw
    x, y = hcoords()
    pink = np.array([1.0, 0.45, 0.85])
    violet = np.array([0.55, 0.35, 1.0])
    img = gradient(y, [(0, (64, 20, 92)), (0.5, (150, 46, 140)), (1, (230, 92, 170))]) * 0.85
    # Bokeh orbs rising at different speeds and sizes (whole cape heights per loop, so it loops).
    r = np.random.default_rng(471)
    for _ in range(18):
        sx, sy, sp = r.random(3)
        size = 4 + sp * 14
        py = (sy - phase * (1 + int(sp * 2))) % 1.15 - 0.075
        d = np.hypot((x - sx) * HW, (y - py) * HH)
        orb = np.clip(1 - d / (size * HS / 2), 0, 1) ** 0.6
        rim = np.exp(-((d - size * HS / 2 * 0.92) ** 2) / 6)
        img += (orb * 0.12 + rim * 0.1)[..., None] * (pink if sp > 0.5 else np.array([1.0, 0.85, 0.95]))
    # The gem: cut facets in pink and violet, lit by a light that sweeps across them, a crisp glowing outline.
    W2, H2 = HW * 2, HH * 2
    cx, cy, gw, gh = W2 * 0.5, H2 * 0.42, W2 * 0.56, H2 * 0.31
    T0, T1 = (cx - gw * 0.28, cy - gh * 0.42), (cx + gw * 0.28, cy - gh * 0.42)
    G0, G1 = (cx - gw * 0.5, cy - gh * 0.1), (cx + gw * 0.5, cy - gh * 0.1)
    K0, K1 = (cx - gw * 0.14, cy - gh * 0.1), (cx + gw * 0.14, cy - gh * 0.1)
    C = (cx, cy + gh * 0.58)
    pts = [T0, T1, G1, C, G0]
    facets = [  # polygon, base brightness, hue mix (0 pink .. 1 violet)
        ([T0, T1, K1, K0], 1.0, 0.15), ([G0, T0, K0], 0.7, 0.55), ([T1, G1, K1], 0.85, 0.35),
        ([G0, K0, C], 0.55, 0.85), ([K0, K1, C], 0.9, 0.4), ([K1, G1, C], 0.65, 0.7),
    ]
    sweep = (phase * 1.6 - 0.3)
    body = np.zeros((HH, HW))
    for k, (poly, bright, mix) in enumerate(facets):
        m = Image.new("L", (W2, H2), 0)
        ImageDraw.Draw(m).polygon(poly, fill=255)
        fm = np.asarray(m.resize((HW, HH), Image.LANCZOS), dtype=float) / 255
        body = np.maximum(body, fm)
        fx = sum(p[0] for p in poly) / len(poly) / W2
        light = np.exp(-((fx - sweep) * 3.2) ** 2)
        colour = pink * (1 - mix) + violet * mix
        inner = (0.45 + 0.35 * y + 0.2 * (1 - x)) * bright
        img = img * (1 - fm[..., None] * 0.92) + fm[..., None] * (colour * (0.55 + 0.6 * inner[..., None]) + light * 0.75)
    pulse = 0.75 + 0.25 * math.sin(phase * 2 * math.pi)
    img += blur(body, 14)[..., None] * pink * 0.55 * pulse
    edges = Image.new("L", (W2, H2), 0)
    ed = ImageDraw.Draw(edges)
    ed.line(pts + [pts[0]], fill=255, width=6, joint="curve")
    ed.line([G0, G1], fill=200, width=4)
    for a, b in ((T0, K0), (K0, C), (T1, K1), (K1, C)):
        ed.line([a, b], fill=170, width=3)
    gm = np.asarray(edges.resize((HW, HH), Image.LANCZOS), dtype=float) / 255
    img += blur(gm, 5)[..., None] * pink * 0.8 * pulse + gm[..., None] * np.array([1.2, 1.05, 1.25]) * (0.8 + 0.2 * pulse)
    # One sparkle that hops between the gem's corners.
    corner = pts[int(phase * 5) % 5]
    local = (phase * 5) % 1
    tw = math.sin(local * math.pi) ** 2
    dx, dy = (x - corner[0] / W2) * HW, (y - corner[1] / H2) * HH
    img += (np.exp(-dx ** 2 / 3) * np.exp(-dy ** 2 / 220) + np.exp(-dy ** 2 / 3) * np.exp(-dx ** 2 / 220))[..., None] * tw * 1.6
    return vignette(tonemap(img * 1.05), 0.2)


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
    "circuit": circuit,
    "holo_grid": holo_grid,
    "silent_night": silent_night,
    "festive_lights": festive_lights,
    "gift_wrap": gift_wrap,
    "velo_staff": velo_staff,
    "booster": booster,
    "haunted": haunted,
    "jack_o_lantern": jack_o_lantern,
    "witching_hour": witching_hour,
}


# ---- cape layout ----

# Capes whose detail (text, fine lines) needs more than 8 texture pixels per cape pixel: {id: (scale, frames, dither)}.
# Every frame stays decoded in the game's memory (scale^2 * 8 KB each), so only where it matters.
# Every cape is 1024 x 512 (OUT_SCALE 16); these differ in frame count / dithering.
HIGH_RES = {
    "velo_staff": (OUT_SCALE, 24, False),   # flat colours: no dither speckle on the STAFF lettering
}


def to_texture(art, scale=OUT_SCALE):
    """Outside art (0..1+) -> (64 x 32) * scale RGB cape texture."""
    art = np.clip(art, 0, 1)
    face = Image.fromarray((art * 255).astype(np.uint8)).resize((10 * scale, 16 * scale), Image.LANCZOS)
    tex = Image.new("RGB", (64 * scale, 32 * scale), (0, 0, 0))
    edge_colour = tuple(int(c) for c in np.asarray(face).reshape(-1, 3).mean(0) * 0.6)
    s = scale
    tex.paste(face, (1 * s, 1 * s))                                     # outside
    inside = Image.fromarray((np.asarray(face.transpose(Image.FLIP_LEFT_RIGHT), dtype=float) * 0.45).astype(np.uint8))
    tex.paste(inside, (12 * s, 1 * s))                                   # inside (towards your back)
    for box in ((1 * s, 0, 11 * s, 1 * s), (11 * s, 0, 21 * s, 1 * s), (0, 1 * s, 1 * s, 17 * s), (11 * s, 1 * s, 12 * s, 17 * s)):
        tex.paste(edge_colour, box)                                      # top, bottom, sides
    return tex


def render(cape_id):
    fn = CAPES[cape_id]
    scale, count, dither = HIGH_RES.get(cape_id, (OUT_SCALE, FRAMES, True))
    delay = round(DELAY_MS * FRAMES / count)  # same loop length with fewer frames
    frames = [to_texture(fn(i / count), scale) for i in range(count)]
    # libimagequant: far better palettes than median cut (smooth gradients, no speckled dither).
    paletted = [f.quantize(colors=256, method=Image.Quantize.LIBIMAGEQUANT,
                           dither=Image.Dither.FLOYDSTEINBERG if dither else Image.Dither.NONE) for f in frames]
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{cape_id}.gif"
    paletted[0].save(path, save_all=True, append_images=paletted[1:], duration=delay, loop=0, disposal=1, optimize=False)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    fw, fh = 10 * scale, 16 * scale
    sheet = Image.new("RGB", (fw * 8 + 70, fh + 20), (20, 20, 26))
    for k, i in enumerate(range(0, count, max(1, count // 8))):
        if k >= 8:
            break
        sheet.paste(frames[i].crop((scale, scale, 11 * scale, 17 * scale)), (10 + k * (fw + 7), 10))
    sheet.save(PREVIEW / f"{cape_id}.png")
    print(f"{cape_id}: {path.relative_to(ROOT)} ({path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    for cape in sys.argv[1:] or CAPES:
        render(cape)
