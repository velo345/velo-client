"""Velo Client Setup art: the whole installer window is drawn here, the .iss only places a few labels and click areas on it.

Writes 24-bit BMPs at 100% and 200% into packaging/windows/ (Inno's [Code] picks the one for the screen's scaling).
Run: python3 tools/installer/make_images.py

Layout (logical pixels, keep in sync with velo-setup.iss): window 820 x 500, hero art left (0..470, fading into the panel),
panel right (x 490..790): logo + wordmark at y 54, tagline y 104, page content y 150..380, button y 412 (310 x 48).
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "packaging" / "windows"
LOGO = Image.open(ROOT / "Logo.png").convert("RGBA")
HERO = Image.open(ROOT / "velo-site" / "assets" / "hero" / "hero-2560.webp").convert("RGB")
FONTS = ROOT / "library" / "src" / "main" / "resources" / "fonts"

W, H = 820, 500
PANEL_X = 490
BG = (12, 10, 10)          # -velo-background
SURFACE = (20, 16, 17)     # -velo-surface
ACCENT = (215, 38, 45)     # -velo-accent
ACCENT_HOVER = (228, 60, 67)
TEXT = (244, 239, 238)
TEXT_2 = (168, 158, 160)
BTN_W, BTN_H = 310, 48


def font(name: str, size: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / f"Inter-{name}.ttf"), round(size))


def background(s: int) -> Image.Image:
    w, h = W * s, H * s
    img = Image.new("RGBA", (w, h), BG + (255,))
    # hero: the player with the Velo cloak, cropped so they stand in the left part
    hero_w = 540 * s
    hw, hh = HERO.size
    crop_h = hh
    crop_w = int(crop_h * hero_w / h)
    left = int(hw * 0.79) - crop_w // 2
    left = max(0, min(hw - crop_w, left))
    hero = HERO.crop((left, 0, left + crop_w, crop_h)).resize((hero_w, h), Image.LANCZOS).convert("RGBA")
    # darken a little so the panel stays the focus
    hero = Image.blend(hero, Image.new("RGBA", hero.size, (0, 0, 0, 255)), 0.12)
    img.alpha_composite(hero, (0, 0))
    # fade the hero into the panel (wide, smooth)
    fade = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    fd = fade.load()
    start, end = 380 * s, PANEL_X * s + 10 * s
    for x in range(start, w):
        t = min(1.0, (x - start) / (end - start))
        a = int(255 * (t * t * (3 - 2 * t)))
        for y in range(h):
            fd[x, y] = BG + (a,)
    img.alpha_composite(fade)
    # bottom vignette on the hero
    vig = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    vd = ImageDraw.Draw(vig)
    for i in range(120 * s):
        a = int(150 * (i / (120 * s)) ** 2)
        vd.line((0, h - 120 * s + i, w, h - 120 * s + i), fill=(0, 0, 0, a))
    img.alpha_composite(vig)
    # soft red glow behind the wordmark
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = (PANEL_X + 40) * s, 76 * s
    gd.ellipse((cx - 120 * s, cy - 80 * s, cx + 120 * s, cy + 80 * s), fill=ACCENT + (40,))
    glow = glow.filter(ImageFilter.GaussianBlur(50 * s))
    img.alpha_composite(glow)

    d = ImageDraw.Draw(img)
    # logo + wordmark + tagline
    ls = 46 * s
    logo = LOGO.resize((ls, ls), Image.LANCZOS)
    img.alpha_composite(logo, (PANEL_X * s, 52 * s))
    d.text(((PANEL_X + 58) * s, 52 * s), "Velo Client", font=font("ExtraBold", 31 * s), fill=TEXT)
    d.text(((PANEL_X + 60) * s, 89 * s), "MINECRAFT, FASTER.", font=font("SemiBold", 10.5 * s), fill=ACCENT_HOVER)
    # a thin divider under the header
    line = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(line).line((PANEL_X * s, 128 * s, (W - 30) * s, 128 * s), fill=(255, 255, 255, 22), width=max(1, s))
    img.alpha_composite(line)
    # title bar: minimize / close glyphs, top right (click areas are labels in the .iss)
    gx = (W - 22) * s
    gy = 22 * s
    r = 5 * s
    d.line((gx - r, gy - r, gx + r, gy + r), fill=TEXT_2, width=max(1, int(1.6 * s)))
    d.line((gx - r, gy + r, gx + r, gy - r), fill=TEXT_2, width=max(1, int(1.6 * s)))
    mx = gx - 34 * s
    d.line((mx - r, gy, mx + r, gy), fill=TEXT_2, width=max(1, int(1.6 * s)))
    # accent line along the bottom of the hero
    d.rectangle((0, h - 3 * s, 300 * s, h), fill=ACCENT)
    return img.convert("RGB")


def rounded(size, radius, fill, s):
    w, h = size
    big = Image.new("RGBA", (w * 4, h * 4), (0, 0, 0, 0))
    ImageDraw.Draw(big).rounded_rectangle((0, 0, w * 4 - 1, h * 4 - 1), radius=radius * 4, fill=fill)
    return big.resize((w, h), Image.LANCZOS)


def button(label: str, s: int, hover: bool, disabled: bool = False) -> Image.Image:
    w, h = BTN_W * s, BTN_H * s
    img = Image.new("RGBA", (w, h), BG + (255,))
    color = (70, 40, 42) if disabled else ACCENT_HOVER if hover else ACCENT
    # glow under the button
    if not disabled:
        glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(glow).rounded_rectangle((8 * s, 12 * s, w - 8 * s, h - 2 * s), radius=10 * s,
                                               fill=ACCENT + (110 if hover else 70,))
        img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(6 * s)))
    body = rounded((w, h - 4 * s), 10 * s, color + (255,), s)
    img.alpha_composite(body, (0, 0))
    # top highlight
    hl = rounded((w, h - 4 * s), 10 * s, (255, 255, 255, 22), s)
    mask = Image.new("L", hl.size, 0)
    ImageDraw.Draw(mask).rectangle((0, 0, w, (h - 4 * s) // 2), fill=255)
    img.paste(hl, (0, 0), Image.composite(hl, Image.new("RGBA", hl.size), mask))
    d = ImageDraw.Draw(img)
    f = font("SemiBold", 15 * s)
    tw = d.textlength(label, font=f)
    bbox = f.getbbox("Ag")
    th = bbox[3] - bbox[1]
    d.text(((w - tw) / 2, (h - 4 * s - th) / 2 - bbox[1]), label, font=f,
           fill=(150, 130, 132) if disabled else (255, 255, 255))
    return img.convert("RGB")


def toggle(on: bool, s: int) -> Image.Image:
    w, h = 36 * s, 20 * s
    img = Image.new("RGBA", (w, h), BG + (255,))
    track = rounded((w, h), h // 2, (ACCENT if on else (58, 50, 52)) + (255,), s)
    img.alpha_composite(track)
    k = 16 * s
    knob = rounded((k, k), k // 2, (255, 255, 255, 255), s)
    img.alpha_composite(knob, (w - k - 2 * s if on else 2 * s, 2 * s))
    return img.convert("RGB")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("wizard-*.bmp"):
        old.unlink()
    for s in (1, 2):
        background(s).save(OUT / f"setup-bg-{s}x.bmp")
        for name, label in (("install", "Install"), ("launch", "Launch Velo Client")):
            button(label, s, False).save(OUT / f"setup-{name}-{s}x.bmp")
            button(label, s, True).save(OUT / f"setup-{name}-hover-{s}x.bmp")
        button("Installing...", s, False, disabled=True).save(OUT / f"setup-busy-{s}x.bmp")
        toggle(True, s).save(OUT / f"setup-toggle-on-{s}x.bmp")
        toggle(False, s).save(OUT / f"setup-toggle-off-{s}x.bmp")
    print("written to", OUT)
