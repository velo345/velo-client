"""Velo Client Setup (Inno Setup) wizard art: the side panel (welcome / finish pages) and the small header logo.

Writes 24-bit BMPs at 100% and 200% into packaging/windows/ - Inno picks the one for the screen's scaling.
Run: python3 tools/installer/make_images.py
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "packaging" / "windows"
LOGO = Image.open(ROOT / "Logo.png").convert("RGBA")
BG_TOP = (20, 14, 15)
BG_BOTTOM = (10, 8, 8)
ACCENT = (215, 38, 45)


def side(scale: int) -> Image.Image:
    w, h = 164 * scale, 314 * scale
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        for x in range(w):
            r = BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t
            g = BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t
            b = BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t
            px[x, y] = (int(r), int(g), int(b))
    # red glow behind the logo
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    cx, cy = w // 2, int(h * 0.36)
    for i in range(40, 0, -1):
        rad = int(w * 0.62 * i / 40)
        alpha = int(70 * (1 - i / 40) ** 1.6)
        d.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), fill=ACCENT + (alpha,))
    glow = glow.filter(ImageFilter.GaussianBlur(14 * scale))
    img = Image.alpha_composite(img.convert("RGBA"), glow)
    # thin diagonal lines (speed / motion), very faint
    lines = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lines)
    for k in range(-h, w, 18 * scale):
        ld.line((k, h, k + h, 0), fill=(255, 255, 255, 7), width=scale)
    img = Image.alpha_composite(img, lines)
    # the logo
    size = int(w * 0.62)
    logo = LOGO.resize((size, size), Image.LANCZOS)
    img.alpha_composite(logo, (cx - size // 2, cy - size // 2))
    # wordmark
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 15 * scale)
        small = ImageFont.truetype("DejaVuSans.ttf", 9 * scale)
    except OSError:
        font = small = ImageFont.load_default()
    text = "VELO CLIENT"
    tw = d.textlength(text, font=font)
    ty = int(h * 0.62)
    d.text(((w - tw) / 2, ty), text, font=font, fill=(244, 239, 238))
    sub = "Minecraft, faster."
    sw = d.textlength(sub, font=small)
    d.text(((w - sw) / 2, ty + 22 * scale), sub, font=small, fill=(170, 160, 162))
    # accent bar at the bottom
    d.rectangle((0, h - 4 * scale, w, h), fill=ACCENT)
    return img.convert("RGB")


def small(scale: int) -> Image.Image:
    s = 55 * scale
    img = Image.new("RGBA", (s, s), BG_TOP + (255,))
    logo = LOGO.resize((int(s * 0.86), int(s * 0.86)), Image.LANCZOS)
    img.alpha_composite(logo, ((s - logo.width) // 2, (s - logo.height) // 2))
    return img.convert("RGB")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for scale in (1, 2):
        side(scale).save(OUT / f"wizard-side-{scale}x.bmp")
        small(scale).save(OUT / f"wizard-small-{scale}x.bmp")
    print("written to", OUT)
