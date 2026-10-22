"""Hand-built cover for the Velo Client 0.9.7 news post (1600x976, the launcher's cover size).

Usage: python3 tools/news/make_cover_0_9_7.py <repo> <out.jpg>  (needs Pillow + numpy). Copy and change the texts for later posts.
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(sys.argv[1])
OUT = Path(sys.argv[2])
FONTS = REPO / "launcher/src/main/resources/net/veloclient/launcher/fonts"
SHOTS = REPO / "news/posts/0.9.7/images"
W, H = 1600, 976
RED = (224, 38, 46)


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def glow(base, center, radius, color, alpha):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = center
    d.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color + (alpha,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.55))
    base.alpha_composite(layer)


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def perspective_coeffs(src, dst):
    """Coefficients mapping dst quad -> src quad for Image.transform(PERSPECTIVE)."""
    import numpy as np
    rows = []
    for (x, y), (u, v) in zip(dst, src):
        rows.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        rows.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    a = np.array(rows, dtype=float)
    b = np.array([c for p in src for c in p], dtype=float)
    return np.linalg.solve(a, b).tolist()


def framed_shot(path, width, radius=18):
    shot = Image.open(path).convert("RGB")
    h = round(shot.height * width / shot.width)
    shot = shot.resize((width, h), Image.LANCZOS)
    pad = 10
    frame = Image.new("RGBA", (width + pad * 2, h + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((0, 0, frame.width - 1, frame.height - 1), radius + 8, fill=(30, 30, 38, 255), outline=(255, 255, 255, 46), width=2)
    frame.alpha_composite(rounded(shot, radius), (pad, pad))
    return frame


def tilt(img, lean):
    """A gentle 3D lean to the left (lean > 0 pushes the right edge back)."""
    w, h = img.size
    inset = h * lean
    dst = [(0, 0), (w, inset), (w, h - inset), (0, h)]
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    return img.transform((w, h), Image.PERSPECTIVE, perspective_coeffs(src, dst), Image.BICUBIC)


def shadowed(base, img, pos, blur=38, alpha=170, offset=(0, 26), tint=(0, 0, 0)):
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    m = img.split()[3].point(lambda a: alpha if a > 0 else 0)
    solid = Image.new("RGBA", img.size, tint + (255,))
    solid.putalpha(m)
    sh.alpha_composite(solid, (pos[0] + offset[0], pos[1] + offset[1]))
    base.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))
    base.alpha_composite(img, pos)


def pill(d, x, y, text, f, fill, outline, color):
    tw = d.textlength(text, font=f)
    h = 44
    d.rounded_rectangle((x, y, x + tw + 36, y + h), h // 2, fill=fill, outline=outline, width=2)
    d.text((x + 18, y + h / 2), text, font=f, fill=color, anchor="lm")
    return x + tw + 36 + 12


def main():
    img = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    # Ground: a faint grid that fades out, like the launcher's backdrop.
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    for x in range(0, W, 48):
        gd.line((x, 0, x, H), fill=(255, 255, 255, 9))
    for y in range(0, H, 48):
        gd.line((0, y, W, y), fill=(255, 255, 255, 9))
    fade = Image.new("L", (W, H), 0)
    ImageDraw.Draw(fade).ellipse((-300, -400, 1100, 900), fill=255)
    grid.putalpha(Image.composite(grid.split()[3], Image.new("L", (W, H), 0), fade.filter(ImageFilter.GaussianBlur(160))))
    img.alpha_composite(grid)

    glow(img, (1180, 300), 520, RED, 120)
    glow(img, (1420, 820), 300, (255, 90, 60), 60)
    glow(img, (180, 900), 260, RED, 55)

    # Soft dark band on the left so the text never fights the glow.
    shade = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shade).rectangle((0, 0, 640, H), fill=190)
    shade = shade.filter(ImageFilter.GaussianBlur(120))
    dark = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    dark.putalpha(shade)
    img.alpha_composite(dark)

    # Right: the launcher Store leaning back, the in-game title screen in front of it with a red glow.
    store = tilt(framed_shot(SHOTS / "store.jpg", 900), 0.06)
    shadowed(img, store, (700, 70), blur=46, alpha=190, offset=(-20, 34))
    title = tilt(framed_shot(SHOTS / "ingame-title.jpg", 560, radius=14), 0.035)
    pos = (752, 590)
    shadowed(img, title, pos, blur=60, alpha=120, offset=(0, 0), tint=RED)
    shadowed(img, title, pos, blur=36, alpha=190, offset=(-14, 30))

    # Left: brand, version, title, what's in it.
    left = Image.new("RGBA", img.size, (0, 0, 0, 0))

    d = ImageDraw.Draw(left)
    logo = Image.open(REPO / "velo-site/assets/logo.png").convert("RGBA").resize((64, 64), Image.LANCZOS)
    left.alpha_composite(logo, (90, 92))
    d.text((170, 124), "VELO CLIENT", font=font("Sora-Bold.ttf", 30), fill=(244, 244, 246, 255), anchor="lm")

    # The version, big, with a red-to-white gradient and a glow behind it.
    ver_font = font("Sora-Bold.ttf", 230)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).text((80, 190), "0.9.7", font=ver_font, fill=255)
    halo = Image.new("RGBA", img.size, RED + (0,))
    halo.putalpha(mask.filter(ImageFilter.GaussianBlur(28)).point(lambda a: int(a * 0.85)))
    img.alpha_composite(halo)
    grad = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gdr = ImageDraw.Draw(grad)
    for y in range(190, 470):
        t = (y - 190) / 280
        col = (round(255 - (255 - 240) * t), round(255 - (255 - 53) * t), round(255 - (255 - 61) * t), 255)
        gdr.line((0, y, 900, y), fill=col)
    grad.putalpha(mask)
    img.alpha_composite(grad)

    d.text((90, 520), "The new launcher", font=font("Sora-Bold.ttf", 64), fill=(244, 244, 246, 255))
    d.text((90, 596), "& in-game menus", font=font("Sora-Bold.ttf", 64), fill=(244, 244, 246, 255))

    chip = font("Inter-SemiBold.ttf", 21)
    x = 90
    y = 712
    for text in ["New Home", "Store with bundles", "Locker"]:
        x = pill(d, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))
    x = 90
    y += 58
    for text in ["Title & pause menus", "Invite rewards"]:
        x = pill(d, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))
    x = pill(d, x, y, "Out now", font("Inter-Bold.ttf", 21), RED + (255,), RED + (255,), (255, 255, 255, 255))

    d.text((90, 890), "velo-client.com", font=font("JetBrainsMono-Medium.ttf", 22), fill=(138, 138, 149, 255))
    img.alpha_composite(left)

    # Vignette for depth.
    vig = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vig).rectangle((0, 0, W, H), outline=255, width=120)
    vig = vig.filter(ImageFilter.GaussianBlur(120)).point(lambda a: int(a * 0.55))
    black = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    black.putalpha(vig)
    img.alpha_composite(black)

    img.convert("RGB").save(OUT, quality=93, subsampling=0)
    print("saved", OUT)


main()
