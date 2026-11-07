"""Cover for the Velo Client 1.2.2 post: "1000+ FPS" - a glowing FPS curve racing over a perspective grid.

Usage: python3 tools/news/make_cover_1_2_2.py <repo> <out.jpg>   (needs Pillow)

Everything that matters sits in a centred column about 600 px wide, so the cover works as the wide banner on the
News page and centre-cropped in the narrow Home sidebar and the game's news panel. The side cards (live stats on the
left, the OG badge on the right) are extras for the wide view.
"""
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

REPO = Path(sys.argv[1])
OUT = Path(sys.argv[2])

# Shared helpers from the 0.9.93 cover (same look across posts).
src = (REPO / "tools/news/make_cover_0_9_93.py").read_text().rsplit("\nmain()", 1)[0]
helpers = {}
argv = sys.argv
sys.argv = [argv[0], str(REPO), "", str(OUT)]
exec(compile(src, "make_cover_0_9_93.py", "exec"), helpers)
sys.argv = argv
font, glow, shadow, card, gradient_text, pill = (helpers[k] for k in ["font", "glow", "shadow", "card", "gradient_text", "pill"])
TEXT, TEXT_2, TEXT_3 = helpers["TEXT"], helpers["TEXT_2"], helpers["TEXT_3"]

W, H = 1600, 900
CX = W // 2
RED = (232, 40, 52)
GOLD = (247, 192, 74)
HORIZON = 668


def grid_floor(img):
    """A perspective grid from the horizon down, red, fading into the distance."""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    vx, vy = CX, HORIZON - 120
    for i in range(-24, 25):
        x_bottom = CX + i * 150
        # from the bottom edge towards the vanishing point, clipped at the horizon
        t = (HORIZON - vy) / (H - vy)
        x_h = vx + (x_bottom - vx) * t
        d.line((x_h, HORIZON, x_bottom, H), fill=(255, 70, 80, 120), width=2)
    z = 1.0
    while True:
        y = vy + (H - vy) / z
        if y < HORIZON:
            break
        alpha = int(140 * min(1, (y - HORIZON) / 120))
        d.line((0, y, W, y), fill=(255, 70, 80, alpha), width=2)
        z += 0.55
    fade = Image.new("L", (W, H), 0)
    fd = ImageDraw.Draw(fade)
    for y in range(HORIZON, H):
        fd.line((0, y, W, y), fill=int(255 * min(1, (y - HORIZON) / 140)))
    layer.putalpha(Image.composite(layer.split()[3], Image.new("L", (W, H), 0), fade))
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(0.6)))


def fps_curve(img):
    """The FPS line: climbing from the left, levelling out high on the right, red into gold, with a glow and a fill."""
    random.seed(11)
    pts = []
    for x in range(-20, W + 21, 8):
        t = x / W
        base = 880 - 200 * (1 - math.exp(-3.4 * t))
        y = base + math.sin(x / 53) * 5 + math.sin(x / 13.7) * 2.5 + (random.random() - 0.5) * 4
        pts.append((x, y))

    def color(x):
        t = max(0.0, min(1.0, x / W))
        return tuple(round(a + (b - a) * t) for a, b in zip(RED, GOLD))

    fill = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fill)
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        c = color(x0)
        fd.polygon([(x0, y0), (x1, y1), (x1, H), (x0, H)], fill=c + (40,))
    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    for y in range(H):
        md.line((0, y, W, y), fill=255)
    img.alpha_composite(fill)

    line = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(line)
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        ld.line((x0, y0, x1, y1), fill=color(x0) + (255,), width=6)
    glow_layer = line.filter(ImageFilter.GaussianBlur(14))
    img.alpha_composite(glow_layer)
    img.alpha_composite(glow_layer)
    img.alpha_composite(line)
    return pts


def stats_card(img):
    box = (70, 120, 420, 330)
    d = card(img, box, radius=22, accent=RED)
    d.text((100, 158), "PERFORMANCE BOOST", font=font("Inter-Bold.ttf", 16), fill=TEXT_3, anchor="lm")
    d.ellipse((380, 150, 396, 166), fill=(74, 222, 128, 255))
    f_big = font("Sora-Bold.ttf", 64)
    d.text((98, 230), "1,048", font=f_big, fill=(74, 222, 128, 255), anchor="ls")
    d.text((98 + d.textlength("1,048", font=f_big) + 12, 228), "FPS", font=font("Inter-Bold.ttf", 22), fill=TEXT_2, anchor="ls")
    # mini sparkline
    random.seed(3)
    x, base = 100, 286
    for i in range(42):
        h = 10 + 18 * (1 - math.exp(-i / 9)) + random.random() * 6
        d.rounded_rectangle((x, base - h, x + 3, base), 1, fill=(74, 222, 128, 120 if i < 41 else 255))
        x += 7
    d.text((100, 308), "0.95 ms  ·  GPU 97%  ·  Vulkan", font=font("Inter-SemiBold.ttf", 17), fill=TEXT_3, anchor="lm")


def og_card(img):
    box = (1180, 120, 1530, 330)
    shadow(img, box, 22, blur=50, alpha=90, offset=(0, 0), color=GOLD)
    d = card(img, box, radius=22)
    d.text((1210, 158), "THANK YOU, OG", font=font("Inter-Bold.ttf", 16), fill=(247, 192, 74, 255), anchor="lm")
    badge = Image.open(REPO / "launcher/src/main/resources/net/veloclient/launcher/images/badge_og.png").convert("RGBA")
    badge = badge.resize((236, 118), Image.LANCZOS)
    halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    halo.alpha_composite(badge, (1237, 178))
    tint = Image.new("RGBA", (W, H), GOLD + (0,))
    tint.putalpha(halo.split()[3].filter(ImageFilter.GaussianBlur(18)).point(lambda a: int(a * 0.55)))
    img.alpha_composite(tint)
    img.alpha_composite(badge, (1237, 178))
    d = ImageDraw.Draw(img, "RGBA")
    d.text((1355, 308), "For the first two weeks", font=font("Inter-SemiBold.ttf", 17), fill=TEXT_3, anchor="mm")


def centered_pills(img, y, items, f, h=46, gap=12):
    d = ImageDraw.Draw(img)
    widths = [d.textlength(t, font=f) + 36 + (18 if dot else 0) for t, *_rest, dot in items]
    total = sum(widths) + gap * (len(items) - 1)
    x = CX - total / 2
    for (text, fill, outline, color, dot), w in zip(items, widths):
        pill(img, round(x), y, text, f, fill, outline, color, h=h, dot=dot)
        x += w + gap


def main():
    img = Image.new("RGBA", (W, H), (9, 9, 12, 255))
    glow(img, (CX, 330), 520, (150, 16, 26), 120)
    glow(img, (CX, HORIZON), 380, RED, 70)
    glow(img, (1420, 560), 260, GOLD, 40)
    glow(img, (180, 760), 240, RED, 50)
    grid_floor(img)
    # the horizon line itself, bright in the middle
    hz = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hz)
    for x in range(W):
        a = int(170 * max(0.0, 1 - abs(x - CX) / (W * 0.55)))
        hd.point((x, HORIZON), fill=(255, 120, 120, a))
        hd.point((x, HORIZON + 1), fill=(255, 120, 120, a // 2))
    img.alpha_composite(hz.filter(ImageFilter.GaussianBlur(1.2)))
    img.alpha_composite(hz)
    fps_curve(img)

    stats_card(img)
    og_card(img)

    # Dark plate behind the centred column so the type stays crisp over the glow.
    plate = Image.new("L", (W, H), 0)
    ImageDraw.Draw(plate).ellipse((CX - 420, 40, CX + 420, 640), fill=120)
    dark = Image.new("RGBA", (W, H), (9, 9, 12, 255))
    dark.putalpha(plate.filter(ImageFilter.GaussianBlur(90)))
    img.alpha_composite(dark)

    d = ImageDraw.Draw(img, "RGBA")
    logo = Image.open(REPO / "velo-site/assets/logo.png").convert("RGBA").resize((54, 54), Image.LANCZOS)
    word = font("Sora-Bold.ttf", 28)
    ww = d.textlength("VELO CLIENT", font=word)
    lx = round(CX - (54 + 14 + ww) / 2)
    img.alpha_composite(logo, (lx, 92))
    d.text((lx + 68, 119), "VELO CLIENT", font=word, fill=TEXT, anchor="lm")

    gradient_text(img, (CX, 168), "1.2.2", font("Sora-Bold.ttf", 210), (255, 255, 255), (244, 64, 72), anchor="ma", halo=RED)
    d = ImageDraw.Draw(img, "RGBA")
    tag = font("Sora-Bold.ttf", 44)
    left, right = "1000+ FPS.", " Everywhere."
    lw, rw = d.textlength(left, font=tag), d.textlength(right, font=tag)
    x0 = CX - (lw + rw) / 2
    gradient_text(img, (round(x0), 432), left, tag, (255, 236, 150), (232, 160, 40), halo=GOLD)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((x0 + lw, 432), right, font=tag, fill=TEXT)

    chip = font("Inter-SemiBold.ttf", 21)
    centered_pills(img, 520, [
        ("GPU cosmetics", (255, 255, 255, 16), (255, 255, 255, 46), (236, 236, 240, 255), None),
        ("Performance dashboard", (255, 255, 255, 16), (255, 255, 255, 46), (236, 236, 240, 255), None),
    ], chip)
    centered_pills(img, 576, [
        ("OG badge", (247, 192, 74, 38), (247, 192, 74, 150), (255, 222, 140, 255), (247, 192, 74, 255)),
        ("macOS", (255, 255, 255, 16), (255, 255, 255, 46), (236, 236, 240, 255), None),
        ("Smoother zoom", (255, 255, 255, 16), (255, 255, 255, 46), (236, 236, 240, 255), None),
    ], chip)

    vig = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vig).rectangle((0, 0, W, H), outline=255, width=120)
    vig = vig.filter(ImageFilter.GaussianBlur(120)).point(lambda a: int(a * 0.55))
    black = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    black.putalpha(vig)
    img.alpha_composite(black)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(OUT, quality=93, subsampling=0)
    print("saved", OUT)


main()
