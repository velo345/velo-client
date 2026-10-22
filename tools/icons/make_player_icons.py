"""Draws the clip player's nav icons (56x56, white on transparent, tinted by the game) with plain PIL shapes:
pause, volume, volume_off, scissors, share, send, copy, trash, download, expand, close, eye.

    python3 tools/icons/make_player_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/icon/nav"
SIZE = 56
K = 16  # supersampling
U = SIZE * K / 24  # one unit of a 24x24 grid
W = round(2.2 * U)  # stroke


def P(x, y):
    return (x * U, y * U)


def line(d, pts):
    pts = [P(*p) for p in pts]
    d.line(pts, fill=255, width=W, joint="curve")
    for x, y in pts:
        d.ellipse((x - W / 2, y - W / 2, x + W / 2, y + W / 2), fill=255)


def ring(d, cx, cy, r):
    d.ellipse((P(cx - r, cy - r), P(cx + r, cy + r)), outline=255, width=W)


def arc(d, cx, cy, r, a0, a1):
    d.arc((P(cx - r, cy - r), P(cx + r, cy + r)), a0, a1, fill=255, width=W)


def rect(d, x0, y0, x1, y1, r=2):
    d.rounded_rectangle((P(x0, y0), P(x1, y1)), radius=r * U, outline=255, width=W)


def fill_rect(d, x0, y0, x1, y1, r=1):
    d.rounded_rectangle((P(x0, y0), P(x1, y1)), radius=r * U, fill=255)


def icons():
    def pause(d):
        fill_rect(d, 6, 4.5, 10, 19.5)
        fill_rect(d, 14, 4.5, 18, 19.5)

    def speaker(d):
        d.polygon([P(4, 9.5), P(8, 9.5), P(13, 5), P(13, 19), P(8, 14.5), P(4, 14.5)], fill=255)

    def volume(d):
        speaker(d)
        arc(d, 13, 12, 4, -55, 55)
        arc(d, 13, 12, 8, -55, 55)

    def volume_off(d):
        speaker(d)
        line(d, [(16, 9), (22, 15)])
        line(d, [(22, 9), (16, 15)])

    def scissors(d):
        ring(d, 6, 6, 3)
        ring(d, 6, 18, 3)
        line(d, [(8.2, 8.2), (20, 19)])
        line(d, [(8.2, 15.8), (20, 5)])

    def share(d):
        line(d, [(12, 3), (12, 15)])
        line(d, [(7.5, 7.5), (12, 3), (16.5, 7.5)])
        line(d, [(5, 12), (5, 20), (19, 20), (19, 12)])

    def send(d):
        line(d, [(21, 3), (3, 10.5), (10.5, 13.5), (13.5, 21), (21, 3)])
        line(d, [(10.5, 13.5), (21, 3)])

    def copy(d):
        rect(d, 8, 8, 20, 20)
        line(d, [(4, 15), (4, 5.5), (5.5, 4), (15, 4)])

    def trash(d):
        line(d, [(3.5, 6.5), (20.5, 6.5)])
        line(d, [(9, 6.5), (9, 3.5), (15, 3.5), (15, 6.5)])
        line(d, [(5.5, 6.5), (6.5, 20.5), (17.5, 20.5), (18.5, 6.5)])
        line(d, [(10, 10.5), (10, 16.5)])
        line(d, [(14, 10.5), (14, 16.5)])

    def download(d):
        line(d, [(12, 3), (12, 15)])
        line(d, [(7.5, 10.5), (12, 15), (16.5, 10.5)])
        line(d, [(4, 20), (20, 20)])

    def expand(d):
        line(d, [(4, 9), (4, 4), (9, 4)])
        line(d, [(15, 4), (20, 4), (20, 9)])
        line(d, [(20, 15), (20, 20), (15, 20)])
        line(d, [(9, 20), (4, 20), (4, 15)])

    def close(d):
        line(d, [(6, 6), (18, 18)])
        line(d, [(18, 6), (6, 18)])

    def eye(d):
        arc(d, 12, 19, 11, 215, 325)
        arc(d, 12, 5, 11, 35, 145)
        ring(d, 12, 12, 3)

    return {"pause": pause, "volume": volume, "volume_off": volume_off, "scissors": scissors, "share": share, "send": send,
            "copy": copy, "trash": trash, "download": download, "expand": expand, "close": close, "eye": eye}


def main():
    for name, draw in icons().items():
        big = Image.new("L", (SIZE * K, SIZE * K), 0)
        draw(ImageDraw.Draw(big))
        icon = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
        icon.putalpha(big.resize((SIZE, SIZE), Image.LANCZOS))
        icon.save(OUT / (name + ".png"))
        (OUT / (name + ".png.mcmeta")).write_text('{\n  "texture": {\n    "blur": true\n  }\n}\n')
        print("wrote", name)


if __name__ == "__main__":
    main()
