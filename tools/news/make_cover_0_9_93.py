"""Hand-built cover for the Velo Client 0.9.93 post (1600x976, the launcher's cover size).

Usage: python3 tools/news/make_cover_0_9_93.py <repo> <shader-screenshot.png> <out.jpg>  (needs Pillow). The shader
shot is a real Velo Shaders screenshot (Cinematic preset at sunset); the Anti Coords Leak card is drawn.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(sys.argv[1])
SHOT = Path(sys.argv[2])
OUT = Path(sys.argv[3])
FONTS = REPO / "launcher/src/main/resources/net/veloclient/launcher/fonts"
W, H = 1600, 976
RED = (224, 38, 46)
AMBER = (224, 160, 48)
GREEN = (61, 220, 151)
TEXT = (244, 244, 246, 255)
TEXT_2 = (170, 170, 182, 255)
TEXT_3 = (120, 120, 132, 255)


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def glow(base, center, radius, color, alpha):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = center
    d.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color + (alpha,))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(radius * 0.55)))


def shadow(base, box, radius, blur=34, alpha=160, offset=(0, 22), color=(0, 0, 0)):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    x0, y0, x1, y1 = box
    ImageDraw.Draw(layer).rounded_rectangle((x0 + offset[0], y0 + offset[1], x1 + offset[0], y1 + offset[1]), radius, fill=color + (alpha,))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def card(base, box, radius=22, accent=None):
    shadow(base, box, radius)
    if accent:
        shadow(base, box, radius, blur=46, alpha=70, offset=(0, 0), color=accent)
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle(box, radius, fill=(22, 22, 28, 238), outline=(255, 255, 255, 34), width=2)
    # A faint top sheen.
    x0, y0, x1, y1 = box
    sheen = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(sheen).rounded_rectangle((x0 + 2, y0 + 2, x1 - 2, y0 + 60), radius - 2, fill=(255, 255, 255, 10))
    layer.alpha_composite(sheen)
    base.alpha_composite(layer)
    return ImageDraw.Draw(base, "RGBA")


def gradient_text(base, xy, text, f, top, bottom, anchor="la", halo=None):
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).text(xy, text, font=f, fill=255, anchor=anchor)
    bbox = mask.getbbox()
    if bbox is None:
        return
    if halo:
        h = Image.new("RGBA", base.size, halo + (0,))
        h.putalpha(mask.filter(ImageFilter.GaussianBlur(26)).point(lambda a: int(a * 0.8)))
        base.alpha_composite(h)
    grad = Image.new("RGBA", base.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    y0, y1 = bbox[1], bbox[3]
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, y1 - y0)
        gd.line((bbox[0], y, bbox[2], y), fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,))
    grad.putalpha(mask)
    base.alpha_composite(grad)


def horizontal_gradient_text(base, xy, text, f, colors, anchor="lm"):
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).text(xy, text, font=f, fill=255, anchor=anchor)
    bbox = mask.getbbox()
    grad = Image.new("RGBA", base.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for x in range(bbox[0], bbox[2] + 1):
        t = (x - bbox[0]) / max(1, bbox[2] - bbox[0]) * (len(colors) - 1)
        i = min(len(colors) - 2, int(t))
        f2 = t - i
        col = tuple(round(a + (b - a) * f2) for a, b in zip(colors[i], colors[i + 1]))
        gd.line((x, bbox[1], x, bbox[3]), fill=col + (255,))
    glow_layer = Image.new("RGBA", base.size, colors[0] + (0,))
    glow_layer.putalpha(mask.filter(ImageFilter.GaussianBlur(8)).point(lambda a: int(a * 0.6)))
    base.alpha_composite(glow_layer)
    grad.putalpha(mask)
    base.alpha_composite(grad)


def pill(base, x, y, text, f, fill, outline, color, h=44, dot=None):
    """Drawn on its own layer: translucent fills blend instead of punching holes into the RGBA image."""
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    tw = d.textlength(text, font=f)
    pad = 18 + (18 if dot else 0)
    d.rounded_rectangle((x, y, x + tw + pad + 18, y + h), h // 2, fill=fill, outline=outline, width=2)
    if dot:
        d.ellipse((x + 16, y + h / 2 - 5, x + 26, y + h / 2 + 5), fill=dot)
    d.text((x + pad, y + h / 2), text, font=f, fill=color, anchor="lm")
    base.alpha_composite(layer)
    return x + tw + pad + 18 + 12



def rounded_image(img, size, radius):
    img = img.resize(size, Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    img.putalpha(mask)
    return img


def main():
    img = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    glow(img, (1150, 300), 520, (255, 120, 60), 95)
    glow(img, (1500, 780), 300, RED, 60)
    glow(img, (160, 940), 240, RED, 50)

    # ---- Right: a real Velo Shaders frame ----
    shot = Image.open(SHOT).convert("RGB")
    # the game window of a 2-monitor screenshot, without title bar and taskbar
    if shot.width > 3000:
        shot = shot.crop((2560, 40, 4480, 1330))
    box = (690, 64, 1536, 612)
    shadow(img, box, 26, blur=40, alpha=170)
    shadow(img, box, 26, blur=60, alpha=80, offset=(0, 0), color=(255, 130, 70))
    sw, sh = box[2] - box[0], box[3] - box[1]
    ratio = max(sw / shot.width, sh / shot.height)
    crop_w, crop_h = int(sw / ratio), int(sh / ratio)
    cx = (shot.width - crop_w) // 2
    shot = shot.crop((cx, 0, cx + crop_w, crop_h))
    img.alpha_composite(rounded_image(shot, (sw, sh), 26), (box[0], box[1]))
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle(box, 26, outline=(255, 255, 255, 46), width=2)
    pill(img, box[0] + 24, box[1] + 22, "VELO SHADERS", font("Inter-Bold.ttf", 17), (11, 11, 14, 170), (255, 255, 255, 60), TEXT, h=36)
    pill(img, box[0] + 24, box[3] - 58, "Cinematic preset", font("Inter-SemiBold.ttf", 17), (11, 11, 14, 170), (255, 255, 255, 50), TEXT_2, h=36)

    # ---- Anti Coords Leak card ----
    box = (690, 642, 1100, 818)
    d = card(img, box)
    d.text((722, 680), "ANTI COORDS LEAK", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    pill(img, 950, 662, "Sharing", font("Inter-Bold.ttf", 16), (224, 38, 46, 34), (224, 38, 46, 130), (255, 120, 126, 255), h=34, dot=RED + (255,))
    mono = font("JetBrainsMono-Medium.ttf", 26)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((722, 730), "XYZ:", font=mono, fill=TEXT_2, anchor="lm")
    x = 722 + d.textlength("XYZ: ", font=mono)
    import random
    random.seed(7)
    for part in ["####", " / ", "##", " / ", "#####"]:
        if part.startswith("#"):
            for _ in part:
                ch = random.choice("@#%&$?")
                d.text((x, 730), ch, font=mono, fill=(255, 120, 126, 255), anchor="lm")
                x += d.textlength("0", font=mono)
        else:
            d.text((x, 730), part, font=mono, fill=TEXT_2, anchor="lm")
            x += d.textlength(part, font=mono)
    d.text((722, 776), "RTX 3060 · 240 fps stay visible", font=font("Inter-Medium.ttf", 19), fill=TEXT_3, anchor="lm")

    # ---- 26.3 card ----
    box = (1126, 642, 1536, 818)
    d = card(img, box)
    d.text((1158, 680), "NOW ON", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    x = 1158
    for v in ["1.21.11", "26.1", "26.2", "26.3"]:
        x = pill(img, x, 708, v, font("Inter-Bold.ttf", 19), (255, 255, 255, 14) if v != "26.3" else (61, 220, 151, 30),
                 (255, 255, 255, 40) if v != "26.3" else (61, 220, 151, 130), (230, 230, 236, 255) if v != "26.3" else GREEN + (255,), h=38)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((1158, 776), "Shaders on 26.2 & 26.3 (Vulkan)", font=font("Inter-Medium.ttf", 19), fill=TEXT_3, anchor="lm")

    # ---- Left: brand, version, title ----
    shade = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shade).rectangle((0, 0, 600, H), fill=150)
    dark = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    dark.putalpha(shade.filter(ImageFilter.GaussianBlur(110)))
    img.alpha_composite(dark)
    d = ImageDraw.Draw(img, "RGBA")
    logo = Image.open(REPO / "velo-site/assets/logo.png").convert("RGBA").resize((64, 64), Image.LANCZOS)
    img.alpha_composite(logo, (84, 82))
    d.text((164, 114), "VELO CLIENT", font=font("Sora-Bold.ttf", 30), fill=TEXT, anchor="lm")
    gradient_text(img, (74, 170), "0.9.93", font("Sora-Bold.ttf", 176), (255, 255, 255), (240, 53, 61), halo=RED)
    x = pill(img, 90, 392, "NEW", font("Inter-ExtraBold.ttf", 20), RED + (255,), RED + (255,), (255, 255, 255, 255), h=40)
    pill(img, x, 392, "SHADERS BETA", font("Inter-ExtraBold.ttf", 20), AMBER + (255,), AMBER + (255,), (26, 18, 6, 255), h=40)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((88, 470), "Shaders that", font=font("Sora-Bold.ttf", 60), fill=TEXT)
    d.text((88, 542), "run on Vulkan.", font=font("Sora-Bold.ttf", 60), fill=TEXT)
    chip = font("Inter-SemiBold.ttf", 21)
    x, y = 90, 650
    for text in ["Real shadows", "Volumetric clouds"]:
        x = pill(img, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))
    x, y = 90, y + 58
    for text in ["8 presets", "Coloured light"]:
        x = pill(img, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))

    # ---- Bottom bar ----
    bar = (84, 850, 1536, 918)
    shadow(img, bar, 18, blur=30, alpha=120, offset=(0, 0), color=RED)
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle(bar, 18, fill=(224, 38, 46, 255))
    d.text((112, 884), "ALSO NEW", font=font("Inter-ExtraBold.ttf", 27), fill=(255, 255, 255, 255), anchor="lm")
    d.text((112 + d.textlength("ALSO NEW", font=font("Inter-ExtraBold.ttf", 27)) + 14, 884),
           "·  Anti Coords Leak, Anti Deepslate, 26.3 fixes, GPU HUD, sharper skins", font=font("Inter-SemiBold.ttf", 27),
           fill=(255, 235, 236, 255), anchor="lm")

    vig = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vig).rectangle((0, 0, W, H), outline=255, width=110)
    vig = vig.filter(ImageFilter.GaussianBlur(110)).point(lambda a: int(a * 0.5))
    black = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    black.putalpha(vig)
    img.alpha_composite(black)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(OUT, quality=93, subsampling=0)
    print("saved", OUT)


main()
