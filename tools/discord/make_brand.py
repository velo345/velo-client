"""Discord brand art for the Velo Client server, all in Velo red.

Run from the repo root:  python3 tools/discord/make_brand.py [banner] [icon] [welcome]   (all when none given)

    assets/discord/banner.png            server banner, 1920 x 1080 (Discord's 16:9 slot; the middle stays clear of crops)
    assets/discord/icon-animated.gif     animated server icon, 512 x 512, seamless loop (needs boost level 1 on Discord)
    assets/discord/icon.png              the same icon, still (first frame)
    bot/src/main/resources/welcome/background.png   the welcome card the bot writes each new member's name and avatar on
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FONTS = os.path.join(ROOT, "launcher/src/main/resources/net/veloclient/launcher/fonts")
OUT = os.path.join(ROOT, "assets/discord")
WELCOME_OUT = os.path.join(ROOT, "bot/src/main/resources/welcome")

RED = np.array([255, 52, 64]) / 255
DEEP = np.array([120, 8, 18]) / 255
HOT = np.array([255, 170, 160]) / 255


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def logo(size):
    """The Velo logo from the 1024 px icon set (sharper than Logo.png when large)."""
    try:
        im = Image.open(os.path.join(ROOT, "Logo.icns"))
        im.size = (1024, 1024)
        im.load()
        im = im.convert("RGBA")
    except Exception:
        im = Image.open(os.path.join(ROOT, "Logo.png")).convert("RGBA")
    bbox = im.getchannel("A").getbbox()
    im = im.crop(bbox)
    scale = size / max(im.size)
    return im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)


def noise(h, w, cells, seed, octaves=5):
    """Fractal value noise in [0, 1]."""
    r = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        ch, cw = cells * 2 ** o, max(2, int(cells * w / h)) * 2 ** o
        grid = r.random((ch + 1, cw + 1))
        ys = np.linspace(0, ch, h, endpoint=False)
        xs = np.linspace(0, cw, w, endpoint=False)
        y0, x0 = ys.astype(int), xs.astype(int)
        fy, fx = ys - y0, xs - x0
        fy, fx = fy * fy * (3 - 2 * fy), fx * fx * (3 - 2 * fx)
        a, b = grid[y0][:, x0], grid[y0][:, x0 + 1]
        c, d = grid[y0 + 1][:, x0], grid[y0 + 1][:, x0 + 1]
        top = a + (b - a) * fx[None, :]
        bot = c + (d - c) * fx[None, :]
        out += amp * (top + (bot - top) * fy[:, None])
        total += amp
        amp *= 0.5
    return out / total


def tonemap(img, exposure=1.0):
    return 1 - np.exp(-np.clip(img, 0, None) * exposure * 1.15)


def to_image(arr):
    return Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8))


def blur_arr(arr, radius):
    return np.asarray(to_image(arr).filter(ImageFilter.GaussianBlur(radius)), dtype=float) / 255


def alpha_of(im):
    return np.asarray(im.getchannel("A"), dtype=float) / 255


def place(mask_small, size, x, y):
    """A small mask placed into a (h, w) canvas at (x, y); parts outside the canvas are cut off."""
    h, w = size
    out = np.zeros((h, w))
    mh, mw = mask_small.shape
    y0, x0 = max(0, y), max(0, x)
    y1, x1 = min(h, y + mh), min(w, x + mw)
    if y1 > y0 and x1 > x0:
        out[y0:y1, x0:x1] = mask_small[y0 - y:y1 - y, x0 - x:x1 - x]
    return out


def text_mask(size, xy, s, f, anchor="mm", tracking=0):
    """A text mask (0..1) of {s} drawn with extra letter spacing."""
    w, h = size
    im = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(im)
    if tracking == 0:
        d.text(xy, s, font=f, fill=255, anchor=anchor)
    else:
        widths = [d.textlength(ch, font=f) for ch in s]
        total = sum(widths) + tracking * (len(s) - 1)
        x = xy[0] - total / 2 if anchor[0] == "m" else xy[0]
        for ch, cw in zip(s, widths):
            d.text((x, xy[1]), ch, font=f, fill=255, anchor="l" + anchor[1])
            x += cw + tracking
    return np.asarray(im, dtype=float) / 255


def cube_poly(cx, cy, s):
    """An isometric cube's three faces (top, left, right) around (cx, cy) with edge {s}."""
    h = s * 0.5
    top = [(cx, cy - s), (cx + s * 0.87, cy - h), (cx, cy), (cx - s * 0.87, cy - h)]
    left = [(cx - s * 0.87, cy - h), (cx, cy), (cx, cy + s), (cx - s * 0.87, cy + h)]
    right = [(cx + s * 0.87, cy - h), (cx, cy), (cx, cy + s), (cx + s * 0.87, cy + h)]
    return top, left, right


# ---------------------------------------------------------------- banner

def banner():
    W, H = 1920, 1080
    y, x = np.mgrid[0:H, 0:W]
    u, v = x / W, y / H
    # Dark and calm: near-black with a faint warm haze behind the logo - no rays, no grid.
    centre = np.exp(-(((u - 0.5) * 1.4) ** 2 + ((v - 0.42) * 2.0) ** 2) * 2.4)
    img = np.zeros((H, W, 3)) + np.array([0.026, 0.02, 0.024])
    img += (np.clip((noise(H, W, 3, 11) - 0.45) * 2, 0, 1) ** 2)[..., None] * np.array([0.05, 0.012, 0.018])
    img += centre[..., None] * np.array([0.14, 0.02, 0.03])
    horizon = 2.0  # no floor
    # The logo: chrome-red with a glow, its reflection on the floor.
    size = 400
    lg = logo(size)
    lx, ly = (W - lg.width) // 2, int(H * 0.40 - lg.height / 2) - 95
    lmask = place(alpha_of(lg), (H, W), lx, ly)
    rgb = np.asarray(lg.convert("RGB"), dtype=float) / 255
    lrgb = np.zeros((H, W, 3))
    lrgb[ly:ly + lg.height, lx:lx + lg.width] = rgb
    img += blur_arr(lmask, 46)[..., None] * RED * 0.55 + blur_arr(lmask, 14)[..., None] * RED * 0.35
    shade = 0.82 + 0.5 * np.clip(1 - (v - ly / H) / (lg.height / H), 0, 1)[..., None]
    sweep = np.exp(-(((u + v * 0.6) - 0.86) * 9) ** 2)[..., None]
    # Keep the logo's own reds saturated (the tonemap greys out anything pushed too far), a narrow white sweep on top.
    img = img * (1 - lmask[..., None]) + lmask[..., None] * (lrgb ** 1.25 * 1.05 * shade + sweep * np.array([0.6, 0.45, 0.45]))
    # Title + tagline.
    title_f = font("Audiowide-Regular.ttf", 150)
    tm = text_mask((W, H), (W / 2, 640), "VELO CLIENT", title_f, tracking=10)
    img += blur_arr(tm, 22)[..., None] * RED * 0.35
    chrome = np.clip(1.25 - (v - 0.55) * 5, 0.7, 1.25)[..., None] * np.array([1.0, 0.9, 0.92])
    img = img * (1 - tm[..., None]) + tm[..., None] * (chrome * 2.6 + sweep * 0.5)
    sub_f = font("Inter-SemiBold.ttf", 40)
    sm = text_mask((W, H), (W / 2, 748), "PvP  ·  Performance  ·  Cosmetics  ·  Free for everyone", sub_f, tracking=2)
    img = img * (1 - sm[..., None] * 0.95) + sm[..., None] * np.array([1.0, 0.84, 0.86]) * 2.0

    out = to_image(tonemap(img)).convert("RGBA")

    # Floating red glass voxels on both sides (blurred by depth) and embers rising.
    rnd = random.Random(21)
    layer_far = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    layer_near = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for k in range(10):
        side = -1 if k % 2 else 1
        cy = rnd.uniform(90, 760)
        # Clear of the title and tagline in the middle (they run from x 360 to 1560 below y 520).
        cx = W / 2 + side * (rnd.uniform(560, 900) if cy < 500 else rnd.uniform(720, 900))
        near = rnd.random() < 0.4
        s = rnd.uniform(46, 86) if near else rnd.uniform(16, 38)
        target = layer_near if near else layer_far
        d = ImageDraw.Draw(target)
        top, left, right = cube_poly(cx, cy, s)
        a = 150 if near else 80
        d.polygon(top, fill=(255, 112, 118, a))
        d.polygon(left, fill=(170, 18, 34, a))
        d.polygon(right, fill=(110, 8, 22, a))
        d.line(top + [top[0]], fill=(255, 200, 200, a), width=2 if near else 1)
        d.line([left[1], left[2]], fill=(255, 160, 160, a // 2), width=1)
    out.alpha_composite(layer_far.filter(ImageFilter.GaussianBlur(3.5)))
    glow = layer_near.filter(ImageFilter.GaussianBlur(18))
    out.alpha_composite(Image.merge("RGBA", (*glow.split()[:3], glow.getchannel("A").point(lambda p: p // 2))))
    out.alpha_composite(layer_near.filter(ImageFilter.GaussianBlur(0.6)))
    embers = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(embers)
    for _ in range(120):
        ex, ey = rnd.uniform(0, W), rnd.uniform(0, H * 0.9)
        r = rnd.choice([1, 1, 1.5, 2, 2.5, 3])
        if 330 < ex < 1590 and 560 < ey < 780:
            continue  # not over the title and tagline
        col = rnd.choice([(255, 220, 210), (255, 120, 120), (255, 80, 90)])
        d.ellipse([ex - r, ey - r, ex + r, ey + r], fill=col + (rnd.randint(70, 220),))
    out.alpha_composite(embers.filter(ImageFilter.GaussianBlur(0.7)))
    # Vignette.
    vig = np.clip(1 - 0.55 * (((u - 0.5) * 1.3) ** 2 + ((v - 0.45) * 1.25) ** 2) * 2.2, 0.25, 1)
    arr = np.asarray(out.convert("RGB"), dtype=float) / 255 * vig[..., None]
    os.makedirs(OUT, exist_ok=True)
    to_image(arr).save(os.path.join(OUT, "banner.png"), optimize=True)
    print("wrote assets/discord/banner.png")


# ---------------------------------------------------------------- animated icon

def icon():
    """Simple on purpose: a dark background and the logo - a soft glow breathing behind it and a shine passing over it."""
    S, SS = 512, 2
    N, DELAY = 40, 50
    R = S * SS
    y, x = np.mgrid[0:R, 0:R]
    u, v = x / R - 0.5, y / R - 0.5
    rr = np.hypot(u, v)
    base = np.zeros((R, R, 3)) + np.array([0.028, 0.022, 0.026])
    base += np.exp(-(rr * 2.6) ** 2)[..., None] * np.array([0.05, 0.02, 0.025])
    lg = logo(int(R * 0.62))
    # Optical centre: the V is wide on top and pointed below, so its mass sits at ~39% of its height; centring the box
    # made it look high. Halfway between the box centre and the mass centre goes in the middle of the square.
    am = alpha_of(lg)
    my = (np.arange(lg.height)[:, None] * am).sum() / am.sum()
    mx = (np.arange(lg.width)[None, :] * am).sum() / am.sum()
    lx = round(R / 2 - (lg.width / 2 + mx) / 2)
    ly = round(R / 2 - (lg.height / 2 + my) / 2)
    lmask = place(am, (R, R), lx, ly)
    lrgb = np.zeros((R, R, 3))
    lrgb[ly:ly + lg.height, lx:lx + lg.width] = np.asarray(lg.convert("RGB"), dtype=float) / 255
    glow = blur_arr(lmask, 30 * SS)
    frames = []
    for i in range(N):
        p = i / N
        pulse = 0.5 + 0.5 * math.sin(p * 2 * math.pi)
        img = base + glow[..., None] * RED * (0.35 + 0.35 * pulse)
        # The shine crosses the logo in the first half of the loop and rests off it in the second.
        sweep_pos = -0.75 + min(1.0, p * 2) * 1.7
        sweep = np.exp(-((u + v * 0.5 - sweep_pos) * 8) ** 2)[..., None]
        img = img * (1 - lmask[..., None]) + lmask[..., None] * (lrgb ** 1.2 * 1.1 + sweep * np.array([0.7, 0.5, 0.52]))
        frames.append(to_image(tonemap(img)).resize((S, S), Image.LANCZOS))
    pal = [f.quantize(colors=255, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.FLOYDSTEINBERG) for f in frames]
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "icon-animated.gif")
    pal[0].save(path, save_all=True, append_images=pal[1:], duration=DELAY, loop=0, disposal=1, optimize=False)
    frames[N // 2].save(os.path.join(OUT, "icon.png"), optimize=True)
    print("wrote assets/discord/icon-animated.gif (%d KB) and icon.png" % (os.path.getsize(path) // 1024))


# ---------------------------------------------------------------- welcome card background

def welcome():
    """1100 x 360. The bot draws the avatar into the ring at (180, 180), r = 112, and the text right of it (x >= 340)."""
    W, H = 1100, 360
    SS = 2
    w, h = W * SS, H * SS
    y, x = np.mgrid[0:h, 0:w]
    u, v = x / w, y / h
    # Dark, like the banner: near-black, a faint warm haze behind the avatar and the logo.
    img = np.zeros((h, w, 3)) + np.array([0.026, 0.02, 0.024])
    img += (np.clip((noise(h, w, 3, 41) - 0.45) * 2, 0, 1) ** 2)[..., None] * np.array([0.05, 0.012, 0.018])
    img += np.exp(-(((u - 0.9) * 1.6) ** 2 + ((v - 0.3) * 1.3) ** 2) * 4)[..., None] * np.array([0.14, 0.02, 0.03])
    img += np.exp(-(((u - 0.16) * 2.4) ** 2 + ((v - 0.5) * 1.6) ** 2) * 5)[..., None] * np.array([0.12, 0.018, 0.026])
    # Big logo watermark on the right.
    lg = logo(int(h * 1.15))
    lm = place(alpha_of(lg), (h, w), int(w * 0.70), int(-h * 0.08))
    img += lm[..., None] * np.array([0.06, 0.012, 0.018])
    # The avatar ring: dark well, red neon ring with a glow.
    cx, cy, r = 180 * SS, 180 * SS, 112 * SS
    d = np.hypot(x - cx, y - cy)
    img *= np.where(d < r + 6 * SS, 0.55, 1)[..., None]
    ring = np.exp(-((d - (r + 7 * SS)) / (2.6 * SS)) ** 2)
    img += ring[..., None] * RED * 1.1 + blur_arr(ring, 10 * SS)[..., None] * RED * 0.9
    # A thin red accent bar under where the name goes.
    bar = (np.abs(y - 222 * SS) < 2 * SS) & (x > 360 * SS) & (x < 420 * SS)
    img += bar[..., None] * RED * 1.6
    out = to_image(tonemap(img)).convert("RGBA")
    sp = Image.new("RGBA", out.size, (0, 0, 0, 0))
    dr = ImageDraw.Draw(sp)
    rnd = random.Random(42)
    for _ in range(40):
        sx, sy = rnd.uniform(330 * SS, w), rnd.uniform(0, h)
        rad = rnd.choice([1, 1.5, 2, 3]) * SS
        dr.ellipse([sx - rad, sy - rad, sx + rad, sy + rad], fill=(255, rnd.choice([220, 150, 110]), rnd.choice([210, 150, 120]), rnd.randint(50, 190)))
    out.alpha_composite(sp)
    out = out.resize((W, H), Image.LANCZOS)
    # Rounded corners for the embed.
    mask = Image.new("L", (W * 3, H * 3), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W * 3 - 1, H * 3 - 1], radius=26 * 3, fill=255)
    out.putalpha(mask.resize((W, H), Image.LANCZOS))
    os.makedirs(WELCOME_OUT, exist_ok=True)
    out.save(os.path.join(WELCOME_OUT, "background.png"), optimize=True)
    print("wrote bot/src/main/resources/welcome/background.png")


if __name__ == "__main__":
    jobs = {"banner": banner, "icon": icon, "welcome": welcome}
    for name in sys.argv[1:] or jobs:
        jobs[name]()
