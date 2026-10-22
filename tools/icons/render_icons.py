"""Renders tools/icons/addon_icons.json to the module icon PNGs (112x112, white strokes on transparent - the same
format as the Lucide-derived module icons; the game tints them).

    pip install --target /tmp/pylib svgpathtools   # once (pure Python)
    PYTHONPATH=/tmp/pylib python3 tools/icons/render_icons.py [--preview sheet.png]

Each icon is a 24x24 stroke path (stroke 2, round caps and joins). Edit the path in the JSON and run this again.
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw
from svgpathtools import parse_path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/client/resources/assets/velo-client/textures/icon/module"
SIZE = 112
SCALE = 8
STROKE = 2.0


def render(d):
    big = SIZE * SCALE
    k = big / 24
    width = max(1, round(STROKE * k))
    img = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(img)
    r = width / 2
    for sub in parse_path(d).continuous_subpaths():
        points = []
        for seg in sub:
            n = max(2, int(seg.length() * 6))
            for i in range(n + 1):
                p = seg.point(i / n)
                points.append((p.real * k, p.imag * k))
        if not points:
            continue
        # Segment by segment with a round stamp at every point: round joins and caps everywhere, no gaps on
        # diagonals (PIL's own joints leave seams there), and dots drawn as tiny segments come out round.
        for a, b in zip(points, points[1:]):
            draw.line((a, b), fill=255, width=width)
        for x, y in points:
            draw.ellipse((x - r, y - r, x + r, y + r), fill=255)
    alpha = img.resize((SIZE, SIZE), Image.LANCZOS)
    icon = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 0))
    icon.putalpha(alpha)
    return icon


def main():
    data = json.loads((ROOT / "tools/icons/addon_icons.json").read_text())["icons"]
    preview = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    sheet = Image.new("RGBA", (8 * 132, ((len(data) + 7) // 8) * 132), (24, 24, 30, 255)) if preview else None
    for i, (module_id, icon) in enumerate(sorted(data.items(), key=lambda e: (e[1]["group"], e[0]))):
        png = render(icon["path"])
        # "dir": somewhere else under textures/icon (the Locker's nav icons), else the module icon.
        target = OUT.parent / f"{icon['dir']}.png" if "dir" in icon else OUT / f"{module_id}.png"
        png.save(target)
        meta = target.with_name(target.name + ".mcmeta")
        if not meta.exists():
            meta.write_text('{\n  "texture": {\n    "blur": true\n  }\n}\n')
        if sheet:
            sheet.alpha_composite(png, ((i % 8) * 132 + 10, (i // 8) * 132 + 10))
    if sheet:
        sheet.save(preview)
    print(f"rendered {len(data)} icons into {OUT}")


main()
