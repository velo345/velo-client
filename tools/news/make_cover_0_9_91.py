"""Hand-built cover for the Velo Client 0.9.91 hotfix post (1600x976, the launcher's cover size).

Usage: python3 tools/news/make_cover_0_9_91.py <repo> <out.jpg>  (needs Pillow). The right side is drawn, not
screenshotted: the World Hosting connection, a tab list with a Name Style and an HD partner server card.
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(sys.argv[1])
OUT = Path(sys.argv[2])
FONTS = REPO / "launcher/src/main/resources/net/veloclient/launcher/fonts"
W, H = 1600, 976
RED = (224, 38, 46)
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


def node(base, center, r, label, sub, kind):
    cx, cy = center
    d = ImageDraw.Draw(base, "RGBA")
    if kind == "relay":
        glow(base, center, r + 30, RED, 120)
        d = ImageDraw.Draw(base, "RGBA")
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(32, 14, 16, 255), outline=RED + (255,), width=3)
        logo = Image.open(REPO / "velo-site/assets/logo.png").convert("RGBA").resize((int(r * 1.15), int(r * 1.15)), Image.LANCZOS)
        base.alpha_composite(logo, (int(cx - logo.width / 2), int(cy - logo.height / 2)))
    else:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(30, 30, 38, 255), outline=(255, 255, 255, 50), width=2)
        if kind == "host":
            # A little house: the world runs here.
            d.polygon([(cx - 20, cy - 2), (cx, cy - 20), (cx + 20, cy - 2)], fill=TEXT)
            d.rectangle((cx - 15, cy - 2, cx + 15, cy + 18), fill=TEXT)
            d.rectangle((cx - 5, cy + 6, cx + 5, cy + 18), fill=(30, 30, 38, 255))
        else:
            # A player.
            d.ellipse((cx - 10, cy - 20, cx + 10, cy), fill=TEXT)
            d.rounded_rectangle((cx - 18, cy + 4, cx + 18, cy + 20), 8, fill=TEXT)
    d = ImageDraw.Draw(base, "RGBA")
    d.text((cx, cy + r + 22), label, font=font("Inter-SemiBold.ttf", 21), fill=TEXT, anchor="mm")
    d.text((cx, cy + r + 48), sub, font=font("Inter-Medium.ttf", 16), fill=TEXT_3, anchor="mm")


def link(base, a, b, phase):
    """A glowing connection with moving packets between two nodes."""
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    (x0, y0), (x1, y1) = a, b
    d.line((x0, y0, x1, y1), fill=RED + (120,), width=4)
    blur = layer.filter(ImageFilter.GaussianBlur(6))
    base.alpha_composite(blur)
    base.alpha_composite(layer)
    d = ImageDraw.Draw(base, "RGBA")
    length = math.hypot(x1 - x0, y1 - y0)
    for i in range(int(length // 34)):
        t = ((i * 34 + phase) % length) / length
        px, py = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        s = 4 if i % 3 else 6
        d.ellipse((px - s, py - s, px + s, py + s), fill=(255, 200, 200, 230) if i % 3 else (255, 255, 255, 255))


def ping_bars(d, x, y, level):
    for i in range(5):
        h = 4 + i * 3
        col = GREEN + (255,) if i < level else (70, 70, 80, 255)
        d.rectangle((x + i * 5, y - h, x + i * 5 + 3, y), fill=col)


def main():
    img = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    for x in range(0, W, 48):
        gd.line((x, 0, x, H), fill=(255, 255, 255, 9))
    for y in range(0, H, 48):
        gd.line((0, y, W, y), fill=(255, 255, 255, 9))
    fade = Image.new("L", (W, H), 0)
    ImageDraw.Draw(fade).ellipse((500, -300, 1900, 1100), fill=255)
    grid.putalpha(Image.composite(grid.split()[3], Image.new("L", (W, H), 0), fade.filter(ImageFilter.GaussianBlur(160))))
    img.alpha_composite(grid)

    glow(img, (1150, 260), 480, RED, 110)
    glow(img, (1480, 760), 300, (255, 90, 60), 55)
    glow(img, (150, 940), 240, RED, 50)

    shade = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shade).rectangle((0, 0, 620, H), fill=190)
    dark = Image.new("RGBA", (W, H), (11, 11, 14, 255))
    dark.putalpha(shade.filter(ImageFilter.GaussianBlur(120)))
    img.alpha_composite(dark)

    # ---- Right: the World Hosting connection ----
    box = (700, 70, 1530, 400)
    d = card(img, box, accent=RED)
    d.text((736, 112), "WORLD HOSTING", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    pill(img, 1300, 92, "Connected", font("Inter-Bold.ttf", 18), (61, 220, 151, 30), (61, 220, 151, 120), GREEN + (255,), h=40, dot=GREEN + (255,))
    host, relay, friend = (820, 230), (1115, 230), (1410, 230)
    link(img, (host[0] + 46, host[1]), (relay[0] - 58, relay[1]), 6)
    link(img, (relay[0] + 58, relay[1]), (friend[0] - 46, friend[1]), 20)
    node(img, host, 46, "Your world", "no port forwarding", "host")
    node(img, relay, 58, "Velo relay", "api.velo-client.com", "relay")
    node(img, friend, 46, "Your friend", "joined in 1.2 s", "friend")

    # ---- Tab list with a Name Style ----
    box = (700, 440, 1170, 800)
    d = card(img, box)
    d.text((736, 482), "TAB LIST", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    rows = [("Steve_Builds", 5, None), ("velo", 5, "style"), ("Kat_Miner", 4, None), ("xXSniperXx", 3, None)]
    mono = font("Inter-SemiBold.ttf", 25)
    y = 530
    for name, level, style in rows:
        if style:
            row = Image.new("RGBA", img.size, (0, 0, 0, 0))
            ImageDraw.Draw(row).rounded_rectangle((722, y - 4, 1148, y + 54), 12, fill=(224, 38, 46, 40), outline=(224, 38, 46, 120), width=2)
            img.alpha_composite(row)
            d = ImageDraw.Draw(img, "RGBA")
        d.rounded_rectangle((738, y + 6, 776, y + 44), 6, fill=(78, 92, 66, 255) if not style else RED + (255,))
        if style:
            # The name style's icon (a four-point star) and the animated gradient name.
            sx, sy = 800, y + 25
            star = [(sx, sy - 11), (sx + 3, sy - 3), (sx + 11, sy), (sx + 3, sy + 3), (sx, sy + 11), (sx - 3, sy + 3), (sx - 11, sy), (sx - 3, sy - 3)]
            ImageDraw.Draw(img).polygon(star, fill=(255, 214, 120, 255))
            horizontal_gradient_text(img, (820, y + 25), "Velo_Player", mono, [(255, 77, 92), (255, 168, 72), (255, 236, 140), (255, 77, 92)])
            d = ImageDraw.Draw(img, "RGBA")
        else:
            d.text((792, y + 25), name, font=mono, fill=TEXT_2, anchor="lm")
        ping_bars(d, 1112, y + 36, level)
        y += 66

    # ---- HD partner server ----
    box = (1200, 440, 1530, 800)
    d = card(img, box)
    d.text((1236, 482), "PARTNER SERVER", font=font("Inter-Bold.ttf", 17), fill=TEXT_3, anchor="lm")
    icon_box = (1290, 520, 1440, 670)
    shadow(img, icon_box, 30, blur=30, alpha=150, offset=(0, 0), color=(255, 120, 60))
    tile = Image.new("RGBA", (150, 150), (0, 0, 0, 0))
    td = ImageDraw.Draw(tile)
    for yy in range(150):
        t = yy / 149
        td.line((0, yy, 150, yy), fill=(round(255 - 40 * t), round(120 - 80 * t), round(60 - 20 * t), 255))
    # A crisp crest: the point of HD icons.
    td.polygon([(75, 22), (122, 42), (116, 96), (75, 128), (34, 96), (28, 42)], fill=(255, 255, 255, 235))
    td.polygon([(75, 38), (106, 52), (102, 92), (75, 113), (48, 92), (44, 52)], fill=(214, 64, 40, 255))
    td.text((75, 76), "P", font=font("Sora-Bold.ttf", 52), fill=(255, 255, 255, 255), anchor="mm")
    mask = Image.new("L", (150, 150), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 149, 149), 30, fill=255)
    tile.putalpha(mask)
    img.alpha_composite(tile, (icon_box[0], icon_box[1]))
    d = ImageDraw.Draw(img, "RGBA")
    d.text((1365, 708), "Your Network", font=font("Inter-Bold.ttf", 25), fill=TEXT, anchor="mm")
    d.ellipse((1300, 742, 1312, 754), fill=GREEN + (255,))
    d.text((1322, 748), "1,284 online", font=font("Inter-SemiBold.ttf", 19), fill=GREEN + (255,), anchor="lm")
    pill(img, 1300, 762, "512 px icon", font("JetBrainsMono-Medium.ttf", 15), (255, 255, 255, 10), (255, 255, 255, 40), TEXT_2, h=28)

    # ---- Left: brand, version, title, what's fixed ----
    d = ImageDraw.Draw(img, "RGBA")
    logo = Image.open(REPO / "velo-site/assets/logo.png").convert("RGBA").resize((64, 64), Image.LANCZOS)
    img.alpha_composite(logo, (84, 82))
    d.text((164, 114), "VELO CLIENT", font=font("Sora-Bold.ttf", 30), fill=TEXT, anchor="lm")

    gradient_text(img, (74, 170), "0.9.91", font("Sora-Bold.ttf", 176), (255, 255, 255), (240, 53, 61), halo=RED)
    d = ImageDraw.Draw(img, "RGBA")
    pill(img, 90, 392, "HOTFIX", font("Inter-ExtraBold.ttf", 20), RED + (255,), RED + (255,), (255, 255, 255, 255), h=40)

    d.text((88, 470), "World Hosting", font=font("Sora-Bold.ttf", 60), fill=TEXT)
    d.text((88, 542), "works now.", font=font("Sora-Bold.ttf", 60), fill=TEXT)

    chip = font("Inter-SemiBold.ttf", 21)
    x, y = 90, 650
    for text in ["Friends can join", "No mystery mods"]:
        x = pill(img, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))
    x, y = 90, y + 58
    for text in ["Name Styles in tab", "HD partner icons"]:
        x = pill(img, x, y, text, chip, (255, 255, 255, 14), (255, 255, 255, 40), (230, 230, 236, 255))

    # ---- Bottom bar ----
    bar = (84, 850, 1530, 918)
    shadow(img, bar, 18, blur=30, alpha=120, offset=(0, 0), color=RED)
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle(bar, 18, fill=(224, 38, 46, 255))
    d.text((112, 884), "UPDATE NOW", font=font("Inter-ExtraBold.ttf", 27), fill=(255, 255, 255, 255), anchor="lm")
    d.text((112 + d.textlength("UPDATE NOW", font=font("Inter-ExtraBold.ttf", 27)) + 14, 884), "·  Hosting and joining worlds need 0.9.91 - update in the launcher", font=font("Inter-SemiBold.ttf", 27),
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
