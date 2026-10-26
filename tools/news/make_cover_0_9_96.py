"""Hand-built cover for the Velo Client 0.9.96 post (1600x976, the launcher's cover size).

Usage: python3 tools/news/make_cover_0_9_96.py <repo> <editor-screenshot.png> <sword.png> <out.jpg>  (needs Pillow).
The editor shot is a real screenshot of the texture editor (screenshot tour scene "packs"); the sword is the game's
diamond_sword.png, recoloured with the same GIMP-style colorize the editor uses.
"""
import colorsys
import importlib.util
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

REPO = Path(sys.argv[1])
SHOT = Path(sys.argv[2])
SWORD = Path(sys.argv[3])
OUT = Path(sys.argv[4])

# Shared drawing helpers from the 0.9.93 cover (same look across posts).
spec = importlib.util.spec_from_file_location("cover93", REPO / "tools/news/make_cover_0_9_93.py")
src = (REPO / "tools/news/make_cover_0_9_93.py").read_text().rsplit("\nmain()", 1)[0]
helpers = {}
sys.argv = [sys.argv[0], str(REPO), str(SHOT), str(OUT)]
exec(compile(src, "make_cover_0_9_93.py", "exec"), helpers)
font, glow, shadow, card, gradient_text, pill, rounded_image = (helpers[k] for k in
    ["font", "glow", "shadow", "card", "gradient_text", "pill", "rounded_image"])
W, H, RED, GREEN, TEXT, TEXT_2, TEXT_3 = (helpers[k] for k in ["W", "H", "RED", "GREEN", "TEXT", "TEXT_2", "TEXT_3"])
VIOLET = (150, 110, 255)


def colorize(img, hue, sat, light):
    """GIMP's Colors > Colorize: luminance -> HSL with the given hue / saturation (+ lightness offset)."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            lum = lum * (1 - light) + light if light > 0 else lum * (1 + light)
            rr, gg, bb = colorsys.hls_to_rgb(hue / 360, lum, sat)
            px[x, y] = (round(rr * 255), round(gg * 255), round(bb * 255), a)
    return out


def main():
    img = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    glow(img, (1120, 300), 520, (255, 70, 80), 80)
    glow(img, (1480, 800), 300, VIOLET, 55)
    glow(img, (160, 940), 240, RED, 50)

    # ---- Right: the real texture editor ----
    shot = Image.open(SHOT).convert("RGB")
    box = (690, 64, 1536, 612)
    shadow(img, box, 26, blur=40, alpha=170)
    shadow(img, box, 26, blur=60, alpha=80, offset=(0, 0), color=(255, 70, 80))
    sw, sh = box[2] - box[0], box[3] - box[1]
    # The whole editor window height (title row to the Save buttons), cut on the left to the cover's frame.
    y0, y1 = 27, 873
    crop_w = round((y1 - y0) * sw / sh)
    x1 = shot.width - 27
    shot = shot.crop((x1 - crop_w, y0, x1, y1))
    img.alpha_composite(rounded_image(shot, (sw, sh), 26), (box[0], box[1]))
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle(box, 26, outline=(255, 255, 255, 46), width=2)
    x = pill(img, box[0] + 20, box[1] + 12, "TEXTURE EDITOR", font("Inter-Bold.ttf", 15), (11, 11, 14, 200), (255, 255, 255, 60), TEXT, h=30)
    pill(img, x - 4, box[1] + 12, "Live 3D preview", font("Inter-SemiBold.ttf", 15), (11, 11, 14, 200), (255, 255, 255, 50), TEXT_2, h=30)

    # ---- Colorize card: the diamond sword in four colours ----
    box = (690, 642, 1100, 818)
    d = card(img, box)
    d.text((722, 680), "COLORIZE", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    pill(img, 980, 662, "GIMP-style", font("Inter-Bold.ttf", 15), (150, 110, 255, 40), (150, 110, 255, 140), (196, 176, 255, 255), h=32)
    sword = Image.open(SWORD).convert("RGBA")
    x = 722
    for hue, sat, light in [(None, 0, 0), (2, 0.85, 0.05), (45, 0.9, 0.1), (275, 0.75, 0.05), (135, 0.7, 0.0)]:
        s = sword if hue is None else colorize(sword, hue, sat, light)
        s = s.resize((64, 64), Image.NEAREST)
        img.alpha_composite(s, (x, 712))
        x += 74
    d = ImageDraw.Draw(img, "RGBA")
    d.text((722, 796), "Recolour anything, shading stays", font=font("Inter-Medium.ttf", 19), fill=TEXT_3, anchor="lm")

    # ---- Sound card: a waveform ----
    box = (1126, 642, 1536, 818)
    d = card(img, box)
    d.text((1158, 680), "SOUNDS & MUSIC", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    d.ellipse((1462, 662, 1504, 704), fill=RED + (255,))
    d.polygon([(1477, 673), (1477, 693), (1494, 683)], fill=(255, 255, 255, 255))
    import math
    import random
    random.seed(4)
    cy = 742
    for i in range(0, 340, 5):
        env = 0.25 + 0.75 * abs(math.sin(i / 340 * math.pi * 2.2)) * (0.6 + 0.4 * random.random())
        h = max(2, int(26 * env))
        in_trim = 40 <= i <= 300
        col = (255, 90, 98, 255) if in_trim else (255, 255, 255, 60)
        d.rounded_rectangle((1158 + i, cy - h, 1161 + i, cy + h), 1, fill=col)
    for hx in (1198, 1458):
        d.rectangle((hx - 1, 712, hx + 1, 772), fill=(255, 255, 255, 220))
        d.rounded_rectangle((hx - 5, 710, hx + 5, 722), 3, fill=(255, 255, 255, 255))
    d.text((1158, 796), "Play, trim, fade, replace", font=font("Inter-Medium.ttf", 19), fill=TEXT_3, anchor="lm")

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
    gradient_text(img, (74, 170), "0.9.96", font("Sora-Bold.ttf", 176), (255, 255, 255), (240, 53, 61), halo=RED)
    x = pill(img, 90, 392, "NEW", font("Inter-ExtraBold.ttf", 20), RED + (255,), RED + (255,), (255, 255, 255, 255), h=40)
    pill(img, x, 392, "PACK EDITOR", font("Inter-ExtraBold.ttf", 20), VIOLET + (255,), VIOLET + (255,), (16, 10, 30, 255), h=40)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((88, 470), "Build your pack.", font=font("Sora-Bold.ttf", 60), fill=TEXT)
    d.text((88, 542), "Right in the game.", font=font("Sora-Bold.ttf", 60), fill=TEXT)
    chip = font("Inter-SemiBold.ttf", 21)
    x, y = 90, 650
    for text in ["Pixel editor", "Animations", "3D preview"]:
        x = pill(img, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))
    x, y = 90, y + 58
    for text in ["Sounds & music", "Code editor"]:
        x = pill(img, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))

    # ---- Bottom bar ----
    bar = (84, 850, 1536, 918)
    shadow(img, bar, 18, blur=30, alpha=120, offset=(0, 0), color=RED)
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle(bar, 18, fill=(224, 38, 46, 255))
    d.text((112, 884), "ALSO NEW", font=font("Inter-ExtraBold.ttf", 27), fill=(255, 255, 255, 255), anchor="lm")
    d.text((112 + d.textlength("ALSO NEW", font=font("Inter-ExtraBold.ttf", 27)) + 14, 884),
           "·  New resource pack menu, softer shader sun, cleaner title screen", font=font("Inter-SemiBold.ttf", 27),
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
