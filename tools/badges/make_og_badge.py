#!/usr/bin/env python3
"""Draws the OG badge (Velo logo in gold + "OG"): the font glyph (256 x 128, like the Velo+ one at 192 x 128) for the
game and a large copy for the launcher. Run from the repository root."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

ROOT = "src/client/resources/assets/velo-client/"
GOLD_TOP, GOLD_MID, GOLD_BOTTOM = (255, 236, 150), (247, 192, 74), (196, 124, 24)


def gradient(size):
    w, h = size
    g = Image.new("RGBA", size)
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        a, b, k = (GOLD_TOP, GOLD_MID, t / 0.5) if t < 0.5 else (GOLD_MID, GOLD_BOTTOM, (t - 0.5) / 0.5)
        c = tuple(round(a[i] + (b[i] - a[i]) * k) for i in range(3))
        for x in range(w):
            px[x, y] = c + (255,)
    return g


def gold(mask):
    """The gold gradient where `mask` is opaque, with a thin dark rim so it reads on any background."""
    g = gradient(mask.size)
    g.putalpha(mask)
    rim = mask.filter(ImageFilter.MaxFilter(9))
    out = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    dark = Image.new("RGBA", mask.size, (58, 34, 4, 255))
    dark.putalpha(rim.point(lambda v: v * 200 // 255))
    out.alpha_composite(dark)
    out.alpha_composite(g)
    return out


def make(scale):
    w, h = 256 * scale, 128 * scale
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    # The logo's own alpha as the shape, gold instead of red.
    logo = Image.open(ROOT + "textures/icon/logo.png").convert("RGBA")
    box = round(112 * scale)
    logo = logo.resize((box, box), Image.LANCZOS)
    mask = Image.new("L", (w, h), 0)
    mask.paste(logo.split()[3], (round(4 * scale), (h - box) // 2))
    # Keep the logo's inner shading: darker where the red was darker.
    shade = Image.new("L", (w, h), 255)
    lum = logo.convert("L").point(lambda v: min(255, 175 + v))
    shade.paste(lum, (round(4 * scale), (h - box) // 2))
    g = gold(mask)
    g = ImageChops.multiply(g, Image.merge("RGBA", (shade, shade, shade, Image.new("L", (w, h), 255))))
    canvas.alpha_composite(g)
    # "OG" in Sora Bold, as big as the glyph allows.
    tmask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(tmask)
    text_x = round(122 * scale)
    room_w, room_h = w - text_x - round(6 * scale), round(96 * scale)
    size = 200 * scale
    while True:
        font = ImageFont.truetype(ROOT + "font/sora_bold.ttf", size)
        bbox = d.textbbox((0, 0), "OG", font=font)
        if bbox[2] - bbox[0] <= room_w and bbox[3] - bbox[1] <= room_h:
            break
        size -= 2
    d.text((text_x - bbox[0], (h - (bbox[3] - bbox[1])) // 2 - bbox[1]), "OG", font=font, fill=255)
    canvas.alpha_composite(gold(tmask))
    return canvas


make(1).save(ROOT + "textures/font/badge_og_glyph.png")
make(2).save("launcher/src/main/resources/net/veloclient/launcher/images/badge_og.png")
print("ok")
