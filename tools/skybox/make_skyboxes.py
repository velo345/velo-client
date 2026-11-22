#!/usr/bin/env python3
"""Velo Custom Skybox presets: equirectangular 2:1 panoramas (4096 x 2048) plus a small preview per preset.

    python3 tools/skybox/make_skyboxes.py              all presets
    python3 tools/skybox/make_skyboxes.py aurora       just these

Every noise is sampled on the unit sphere (3D value noise of the view direction), so nothing smears toward the zenith
and the seam is invisible. No stars are painted in: the shader draws them per pixel (velo_skybox.fsh), sharp at any
screen size - a star baked into a panorama is always a blurry blob once stretched across the screen. The previews
render a perspective view (horizon, 80 degree fov) with stars like the shader's, for the picker in game.
Writes src/client/resources/assets/velo-client/textures/skybox/<id>.jpg and .../skybox/preview/<id>.png.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/skybox"
W, H = 4096, 2048

# view direction per panorama pixel (same convention as velo_skybox.fsh: u = atan(x, -z) / 2pi + 0.5, v = 0.5 - lat / pi)
v = (np.arange(H, dtype=np.float32) + 0.5) / H
u = (np.arange(W, dtype=np.float32) + 0.5) / W
LAT = ((0.5 - v) * np.pi)[:, None] * np.ones((1, W), np.float32)
LON = ((u - 0.5) * 2 * np.pi)[None, :] * np.ones((H, 1), np.float32)
DX = np.cos(LAT) * np.sin(LON)
DY = np.sin(LAT)
DZ = -np.cos(LAT) * np.cos(LON)
UP = LAT / (np.pi / 2)  # -1 (down) .. 1 (zenith)

TABLE = np.random.default_rng(1234).random(1 << 20).astype(np.float32)


def _hash(ix, iy, iz, seed):
    h = (ix * 73856093) ^ (iy * 19349663) ^ (iz * 83492791) ^ (seed * 2654435761)
    return TABLE[h & ((1 << 20) - 1)]


def noise3(x, y, z, seed):
    """Smooth value noise in 3D (quintic fade), 0..1."""
    xi, yi, zi = np.floor(x), np.floor(y), np.floor(z)
    fx, fy, fz = x - xi, y - yi, z - zi
    xi, yi, zi = xi.astype(np.int64), yi.astype(np.int64), zi.astype(np.int64)
    fade = lambda t: t * t * t * (t * (t * 6 - 15) + 10)
    ux, uy, uz = fade(fx), fade(fy), fade(fz)
    out = 0
    for dx in (0, 1):
        wx = ux if dx else 1 - ux
        for dy in (0, 1):
            wy = uy if dy else 1 - uy
            for dz in (0, 1):
                wz = uz if dz else 1 - uz
                out = out + _hash(xi + dx, yi + dy, zi + dz, seed) * wx * wy * wz
    return out


def fbm(freq, octaves, seed, gain=0.5, stretch=(1.0, 1.0, 1.0), warp=0.0):
    """Fractal noise of the view direction. stretch squashes axes (y > 1 = flat horizontal streaks)."""
    x, y, z = DX * stretch[0], DY * stretch[1], DZ * stretch[2]
    if warp:
        x = x + (noise3(x * 2, y * 2, z * 2, seed + 91) - 0.5) * warp
        y = y + (noise3(x * 2, y * 2, z * 2, seed + 92) - 0.5) * warp
    total, amp, norm, f = 0, 1.0, 0.0, freq
    for o in range(octaves):
        total = total + amp * noise3(x * f + 17.3 * o, y * f, z * f, seed + o)
        norm += amp
        amp *= gain
        f *= 2.03
    return (total / norm).astype(np.float32)


def grad(stops, t):
    t = np.clip(t, 0, 1)
    c = np.zeros(t.shape + (3,), np.float32)
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (t >= p0) & (t <= p1)
        k = ((t - p0) / max(1e-6, p1 - p0))[..., None]
        c = np.where(m[..., None], np.array(c0, np.float32) * (1 - k) + np.array(c1, np.float32) * k, c)
    return c / 255.0


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def great_circle_dist(tilt, phase):
    """Angular distance (radians) from a tilted great circle - for a galaxy band."""
    nx, ny, nz = np.sin(tilt) * np.cos(phase), np.cos(tilt), np.sin(tilt) * np.sin(phase)
    return np.abs(np.arcsin(np.clip(DX * nx + DY * ny + DZ * nz, -1, 1)))


def galaxy(tilt, phase, seed, colour, strength):
    d = great_circle_dist(tilt, phase)
    clump = np.clip((fbm(6, 7, seed, warp=0.6) - 0.38) * 2.0, 0, 1.3)
    dust = smooth(0.52, 0.7, fbm(9, 6, seed + 7, warp=0.4)) * np.exp(-(d / 0.06) ** 2)
    core = np.exp(-(d / 0.11) ** 2) * clump * (1 - dust * 0.9)
    halo = np.exp(-(d / 0.32) ** 2) * 0.22
    return (core + halo)[..., None] * np.array(colour, np.float32) * strength


# ---- the presets ----

def starry_night():
    sky = grad([(0, (6, 9, 24)), (0.42, (12, 19, 46)), (0.5, (26, 36, 72)), (0.56, (12, 17, 40)), (1, (3, 4, 12))], 0.5 - UP * 0.5)
    sky += galaxy(0.55, 0.7, 11, (0.70, 0.68, 0.86), 0.62)
    sky += (smooth(0.55, 0.8, fbm(3, 5, 13)) * 0.05)[..., None] * np.array([0.3, 0.4, 0.9], np.float32)
    return sky


def velo_nebula():
    sky = grad([(0, (8, 5, 10)), (0.5, (14, 7, 14)), (1, (4, 2, 5))], 0.5 - UP * 0.5)
    n1 = fbm(2.2, 8, 21, warp=1.2)
    n2 = fbm(2.8, 8, 33, warp=1.0)
    filaments = 1 - np.abs(fbm(4.5, 6, 37, warp=0.8) * 2 - 1)
    neb = smooth(0.5, 0.82, n1) ** 1.8
    sky += neb[..., None] * np.array([0.95, 0.14, 0.2], np.float32) * 0.55
    sky += (smooth(0.58, 0.86, n2) ** 2)[..., None] * np.array([0.58, 0.12, 0.66], np.float32) * 0.4
    sky += (neb * filaments ** 8)[..., None] * np.array([1.0, 0.55, 0.5], np.float32) * 0.4
    return sky


def aurora():
    sky = grad([(0, (4, 12, 20)), (0.46, (8, 26, 36)), (0.52, (12, 32, 42)), (1, (2, 5, 9))], 0.5 - UP * 0.5)
    edge = 0.36 + 0.09 * np.sin(LON * 2 + fbm(1.5, 4, 51) * 6) + 0.04 * np.sin(LON * 5 + 1.3)
    above = LAT - edge
    curtain = np.where(above >= 0, np.exp(-above / 0.2) * (0.6 + 0.4 * np.exp(-(above / 0.05) ** 2)), np.exp(-(above / 0.035) ** 2) * 0.6)
    # vertical rays: noise of longitude only (fine, the band sits well away from the zenith)
    rays = np.clip(fbm(1.0, 6, 53, stretch=(60.0, 0.6, 60.0)) * 1.8 - 0.45, 0, 1) ** 1.4
    fold = 0.45 + 0.55 * fbm(2.5, 4, 54)
    glow = np.clip(curtain * (0.3 + rays) * fold * 1.6, 0, 1.2)
    top = np.clip(above / 0.28, 0, 1)[..., None]
    col = np.array([0.18, 1.0, 0.55], np.float32) * (1 - top) + np.array([0.62, 0.32, 1.0], np.float32) * top
    sky += glow[..., None] * col * 0.85
    sky += (np.exp(-(above / 0.5) ** 2) * 0.06)[..., None] * np.array([0.2, 0.9, 0.6], np.float32)
    return sky


def sunset_glow():
    sky = grad([(0, (34, 20, 64)), (0.24, (110, 48, 108)), (0.4, (236, 104, 88)), (0.49, (255, 168, 92)), (0.53, (196, 104, 86)), (1, (36, 22, 38))], 0.5 - UP * 0.5)
    sun = np.exp(-((LON / 0.42) ** 2 + ((LAT - 0.04) / 0.2) ** 2))
    sky += sun[..., None] * np.array([1.0, 0.62, 0.28], np.float32) * 0.65
    streaks = smooth(0.48, 0.7, fbm(2.2, 7, 41, stretch=(1.0, 9.0, 1.0), warp=0.3))
    band = streaks * np.exp(-((LAT - 0.2) / 0.13) ** 2)
    lit = np.array([1.0, 0.58, 0.42], np.float32) * (0.75 + 0.35 * sun[..., None])
    sky = sky * (1 - band[..., None] * 0.55) + band[..., None] * lit * 0.6
    return sky


def pastel_day():
    sky = grad([(0, (96, 150, 232)), (0.42, (160, 200, 246)), (0.5, (214, 230, 252)), (0.55, (186, 202, 224)), (1, (146, 160, 186))], 0.5 - UP * 0.5)
    base = fbm(2.6, 8, 61, warp=0.5)
    cover = np.clip(1 - np.abs(LAT - 0.3) / 0.5, 0, 1) * smooth(0.0, 0.12, LAT)
    cloud = smooth(0.5, 0.66, base) * cover
    shade = smooth(0.35, 0.75, fbm(2.6, 6, 62)) * 0.25
    ccol = np.array([1.0, 1.0, 1.0], np.float32) * (1 - shade[..., None]) + np.array([0.78, 0.82, 0.92], np.float32) * shade[..., None]
    return sky * (1 - cloud[..., None]) + ccol * cloud[..., None]


def deep_space():
    sky = grad([(0, (3, 4, 10)), (0.5, (6, 7, 16)), (1, (2, 2, 6))], 0.5 - UP * 0.5)
    sky += galaxy(1.05, 2.3, 71, (0.85, 0.78, 0.95), 0.75)
    dust_a = smooth(0.58, 0.85, fbm(1.8, 7, 73, warp=1.1))
    dust_b = smooth(0.6, 0.85, fbm(2.1, 7, 74, warp=1.0))
    sky += dust_a[..., None] * np.array([0.15, 0.35, 0.8], np.float32) * 0.28
    sky += dust_b[..., None] * np.array([0.6, 0.2, 0.55], np.float32) * 0.22
    return sky


def crimson_dusk():
    sky = grad([(0, (6, 3, 6)), (0.3, (22, 6, 12)), (0.44, (96, 16, 22)), (0.5, (196, 40, 36)), (0.53, (90, 18, 22)), (1, (8, 3, 5))], 0.5 - UP * 0.5)
    streaks = smooth(0.5, 0.72, fbm(2.0, 7, 81, stretch=(1.0, 10.0, 1.0), warp=0.35))
    band = streaks * np.exp(-((LAT - 0.12) / 0.1) ** 2)
    sky = sky * (1 - band[..., None] * 0.7) + band[..., None] * np.array([0.12, 0.03, 0.05], np.float32)
    rim = band * np.exp(-((LAT - 0.08) / 0.05) ** 2)
    sky += rim[..., None] * np.array([1.0, 0.3, 0.2], np.float32) * 0.35
    return sky


def cotton_candy():
    sky = grad([(0, (54, 60, 126)), (0.3, (128, 108, 190)), (0.44, (230, 150, 200)), (0.5, (255, 196, 210)), (0.55, (200, 160, 200)), (1, (70, 60, 110))], 0.5 - UP * 0.5)
    base = fbm(2.3, 8, 91, warp=0.6)
    cover = np.clip(1 - np.abs(LAT - 0.24) / 0.4, 0, 1) * smooth(0.0, 0.1, LAT)
    cloud = smooth(0.52, 0.7, base) * cover
    lit = smooth(0.3, 0.8, fbm(2.3, 6, 92))[..., None]
    ccol = np.array([1.0, 0.82, 0.9], np.float32) * (0.75 + 0.25 * lit)
    return sky * (1 - cloud[..., None] * 0.9) + ccol * cloud[..., None] * 0.9


PRESETS = {
    "starry_night": (starry_night, 1.0),
    "deep_space": (deep_space, 1.0),
    "velo_nebula": (velo_nebula, 0.8),
    "aurora": (aurora, 0.85),
    "crimson_dusk": (crimson_dusk, 0.55),
    "sunset_glow": (sunset_glow, 0.2),
    "cotton_candy": (cotton_candy, 0.25),
    "pastel_day": (pastel_day, 0.0),
}


def preview(pano, stars_amount, seed):
    """Perspective view toward the horizon (yaw 0, pitch 14 deg, fov 80), 2x supersampled, with shader-like stars."""
    pw, ph = 640, 360
    tan_half = np.tan(np.radians(80) / 2)
    xs = (np.arange(pw) + 0.5) / pw * 2 - 1
    ys = 1 - (np.arange(ph) + 0.5) / ph * 2
    nx, ny = np.meshgrid(xs * tan_half * pw / ph, ys * tan_half)
    pitch = np.radians(14)
    fx, fy, fz = 0.0, np.sin(pitch), -np.cos(pitch)
    uxx, uyy, uzz = 0.0, np.cos(pitch), np.sin(pitch)
    dx = fx + nx * 1.0 + ny * uxx
    dy = fy + ny * uyy
    dz = fz + ny * uzz
    n = np.sqrt(dx * dx + dy * dy + dz * dz)
    dx, dy, dz = dx / n, dy / n, dz / n
    uu = (np.arctan2(dx, -dz) / (2 * np.pi) + 0.5) % 1.0
    vv = 0.5 - np.arcsin(np.clip(dy, -1, 1)) / np.pi
    px = np.clip((uu * W).astype(int), 0, W - 1)
    py = np.clip((vv * H).astype(int), 0, H - 1)
    img = pano[py, px].copy()
    if stars_amount > 0:
        r = np.random.default_rng(seed)
        count = int(900 * stars_amount)
        sx, sy = r.uniform(0, pw, count), r.uniform(0, ph * 0.75, count)
        b = 0.25 + 0.75 * r.random(count) ** 3
        lum = img.mean(axis=2)
        for x, y, br in zip(sx, sy, b):
            ix, iy = int(x), int(y)
            vis = 1 - smooth(0.15, 0.45, lum[iy, ix])
            rad = 1 if br > 0.6 else 0
            img[max(0, iy - rad):iy + rad + 1, max(0, ix - rad):ix + rad + 1] += br * vis * 0.9
    small = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)).resize((320, 180), Image.LANCZOS)
    return small


def main():
    wanted = sys.argv[1:] or list(PRESETS)
    (OUT / "preview").mkdir(parents=True, exist_ok=True)
    for i, name in enumerate(wanted):
        fn, stars_amount = PRESETS[name]
        pano = np.clip(fn(), 0, 1)
        # a whisper of grain against banding in the dark gradients (8-bit)
        pano = pano + (np.random.default_rng(5 + i).random(pano.shape[:2])[..., None] - 0.5) / 255.0
        img = Image.fromarray((np.clip(pano, 0, 1) * 255 + 0.5).astype(np.uint8))
        # JPEG at high quality without chroma subsampling: ~1 MB instead of ~5 MB as PNG, no visible difference on soft skies
        img.save(OUT / f"{name}.jpg", quality=94, subsampling=0, optimize=True)
        preview(pano, stars_amount, 40 + i).save(OUT / "preview" / f"{name}.png", optimize=True)
        print(f"{name}: {(OUT / f'{name}.jpg').stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
