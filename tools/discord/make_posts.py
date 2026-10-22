"""Banner pictures for the Discord bot's /post messages -> bot/src/main/resources/posts/*.png.

Run from the repo root:  python3 tools/discord/make_posts.py [store-screenshot.png]
The optional screenshot is a launcher tour shot of the Store page (05-store.png at 2x) for the link-steps picture.
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "bot/src/main/resources/posts")
FONTS = os.path.join(ROOT, "launcher/src/main/resources/net/veloclient/launcher/fonts")
CAPES = os.path.join(ROOT, "server/cosmetics-seed/capes")
W, H = 1200, 400


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def gradient(c1, c2, angle=0.35):
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(W):
            t = (x / W) * (1 - angle) + (y / H) * angle
            px[x, y] = tuple(int(a + (b - a) * t) for a, b in zip(c1, c2))
    return img.convert("RGBA")


def glow(img, xy, radius, color, alpha=150):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = xy
    d.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color + (alpha,))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(radius * 0.55)))


def grid(img, color=(255, 255, 255), alpha=10, step=40):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for x in range(0, W, step):
        d.line([(x, 0), (x, H)], fill=color + (alpha,))
    for y in range(0, H, step):
        d.line([(0, y), (W, y)], fill=color + (alpha,))
    img.alpha_composite(layer)


def sparkles(img, n, color, seed):
    rnd = random.Random(seed)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(n):
        x, y, r = rnd.randint(0, W), rnd.randint(0, H), rnd.choice([1, 1, 2, 2, 3])
        a = rnd.randint(60, 200)
        d.ellipse([x - r, y - r, x + r, y + r], fill=color + (a,))
    img.alpha_composite(layer)


def logo(size):
    im = Image.open(os.path.join(ROOT, "Logo.png")).convert("RGBA")
    return im.resize((size, size), Image.LANCZOS)


def text(img, xy, s, f, fill=(255, 255, 255), shadow=True, anchor="la"):
    d = ImageDraw.Draw(img)
    if shadow:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).text((xy[0], xy[1] + 4), s, font=f, fill=(0, 0, 0, 170), anchor=anchor)
        img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(6)))
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def pill(img, xy, s, f, bg, fg=(255, 255, 255)):
    d = ImageDraw.Draw(img)
    l, t, r, b = d.textbbox(xy, s, font=f)
    d.rounded_rectangle([l - 16, t - 9, r + 16, b + 9], radius=(b - t + 18) // 2, fill=bg)
    d.text(xy, s, font=f, fill=fg)


def cape_front(name, frame=0, scale=3):
    """The front face of a cape (64x32 layout, any resolution) from its animated GIF, scaled crisply."""
    gif = Image.open(os.path.join(CAPES, name + ".gif"))
    gif.seek(frame % getattr(gif, "n_frames", 1))
    im = gif.convert("RGBA")
    s = im.width // 64
    face = im.crop((1 * s, 1 * s, 11 * s, 17 * s))
    return face.resize((10 * 8 * scale, 16 * 8 * scale), Image.NEAREST if s < 8 else Image.LANCZOS)


def framed(face, radius=10):
    """A cape face with a soft drop shadow and rounded corners."""
    pad = 40
    out = Image.new("RGBA", (face.width + pad * 2, face.height + pad * 2), (0, 0, 0, 0))
    sh = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([pad, pad + 12, pad + face.width, pad + face.height + 12], radius=radius, fill=(0, 0, 0, 190))
    out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(16)))
    mask = Image.new("L", face.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, face.width - 1, face.height - 1], radius=radius, fill=255)
    out.paste(face, (pad, pad), mask)
    return out


def finish(img, name):
    # Rounded corners so it sits nicely in the embed.
    mask = Image.new("L", (W * 3, H * 3), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W * 3 - 1, H * 3 - 1], radius=28 * 3, fill=255)
    mask = mask.resize((W, H), Image.LANCZOS)
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    out.save(os.path.join(OUT, name), optimize=True)
    print("wrote", name)


def header(name, title, subtitle, c1, c2, accent, seed):
    img = gradient(c1, c2)
    grid(img)
    glow(img, (W - 200, 80), 260, accent, 110)
    glow(img, (120, H + 40), 220, accent, 70)
    sparkles(img, 70, (255, 255, 255), seed)
    lg = logo(150)
    img.alpha_composite(lg, (70, (H - 150) // 2 - 10))
    text(img, (250, 132), title, font("Audiowide-Regular.ttf", 76))
    text(img, (254, 232), subtitle, font("Inter-SemiBold.ttf", 30), fill=(255, 255, 255, 215))
    return img


def rules():
    img = header("rules", "SERVER RULES", "Be kind, play fair, have fun.", (34, 10, 14), (12, 10, 14), (255, 60, 70), 1)
    pill(img, (256, 296), "10 rules  ·  read before chatting", font("Inter-Bold.ttf", 22), (255, 77, 77, 60))
    finish(img, "rules.png")


def download():
    img = header("download", "GET VELO CLIENT", "Free · Windows · macOS · Linux", (40, 8, 10), (14, 10, 12), (255, 80, 60), 2)
    pill(img, (256, 296), "velo-client.com", font("Inter-Bold.ttf", 24), (255, 77, 77, 210))
    finish(img, "download.png")


def free_cape():
    img = gradient((6, 22, 36), (10, 8, 22))
    grid(img, (80, 220, 255), 14)
    glow(img, (900, 200), 260, (40, 220, 255), 120)
    glow(img, (1040, 120), 160, (190, 80, 255), 90)
    sparkles(img, 90, (150, 240, 255), 3)
    for i, (frame, x, y, sc) in enumerate([(12, 880, 50, 2), (0, 720, 20, 2)]):
        face = framed(cape_front("velo_neon", frame, sc))
        img.alpha_composite(face, (x, y))
    text(img, (70, 104), "VELO NEON", font("Audiowide-Regular.ttf", 84), fill=(140, 245, 255))
    text(img, (74, 212), "The animated cape - free for", font("Inter-SemiBold.ttf", 32))
    text(img, (74, 254), "everyone who links their Discord.", font("Inter-SemiBold.ttf", 32))
    pill(img, (78, 322), "FREE  ·  NOT SOLD ANYWHERE", font("Inter-ExtraBold.ttf", 22), (41, 224, 255, 230), (6, 22, 36))
    finish(img, "free-cape.png")


def boosters():
    img = gradient((52, 10, 60), (16, 8, 30))
    grid(img, (255, 150, 255), 12)
    glow(img, (950, 200), 260, (244, 127, 255), 130)
    glow(img, (160, H), 220, (120, 90, 255), 90)
    sparkles(img, 110, (255, 200, 255), 4)
    face = framed(cape_front("booster", 6, 2))
    img.alpha_composite(face, (W - face.width - 90, (H - face.height) // 2))
    text(img, (70, 96), "BOOSTER PERKS", font("Audiowide-Regular.ttf", 72), fill=(255, 200, 255))
    text(img, (74, 196), "Boost the server, get rewarded:", font("Inter-SemiBold.ttf", 32))
    f = font("Inter-ExtraBold.ttf", 24)
    pill(img, (78, 268), "14 DAYS VELO+", f, (244, 127, 255, 235), (40, 8, 46))
    pill(img, (330, 268), "BOOSTER CAPE", f, (255, 255, 255, 40))
    pill(img, (78, 330), "BOOSTER ROLE", f, (255, 255, 255, 40))
    finish(img, "boosters.png")


def link_steps(shot):
    """The Store page's Discord banner with the Copy link code button circled."""
    if shot is None or not os.path.exists(shot):
        print("skip link-steps.png (no store screenshot)")
        return
    im = Image.open(shot).convert("RGBA")
    k = im.width / 2000  # the tour shot is ~2184 wide; positions measured on a 2000-wide view
    crop = im.crop((int(380 * k), int(255 * k), int(1950 * k), int(500 * k)))
    crop = crop.resize((W, int(crop.height * W / crop.width)), Image.LANCZOS)
    canvas = Image.new("RGBA", (W, crop.height + 40), (18, 14, 16, 255))
    canvas.alpha_composite(crop, (0, 20))
    sx = W / ((1950 - 380) * k)
    bx0, by0 = (436 - 380) * k * sx, (403 - 255) * k * sx + 20
    bx1, by1 = (668 - 380) * k * sx, (463 - 255) * k * sx + 20
    d = ImageDraw.Draw(canvas)
    # Cover the "Join the Discord" link next to it so the arrow runs over clean background.
    bg = canvas.getpixel((int(bx1 + 300), int(by0 - 6)))
    d.rectangle([bx1 + 14, by0 - 4, bx1 + 320, by1 + 4], fill=bg)
    d.rounded_rectangle([bx0 - 8, by0 - 8, bx1 + 8, by1 + 8], radius=22, outline=(41, 224, 255), width=4)
    f = font("Inter-ExtraBold.ttf", 22)
    lx = bx1 + 330
    d.rounded_rectangle([lx - 14, by0 + 4, lx + 330, by1 - 4], radius=18, fill=(41, 224, 255))
    d.text((lx, (by0 + by1) / 2), "Press this, paste it in Discord", font=f, fill=(6, 22, 36), anchor="lm")
    d.line([(bx1 + 14, (by0 + by1) / 2), (lx - 18, (by0 + by1) / 2)], fill=(41, 224, 255), width=4)
    global H
    keep = H
    H = canvas.height
    finish(canvas, "link-steps.png")
    H = keep


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    rules()
    download()
    free_cape()
    boosters()
    link_steps(sys.argv[1] if len(sys.argv) > 1 else None)
