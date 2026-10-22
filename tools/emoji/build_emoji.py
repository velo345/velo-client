"""Builds the chat emoji font for the Emoji Chat module.

    python3 tools/emoji/build_emoji.py <download-dir>

Shortcodes come from GitHub's gemoji (MIT), the pictures from Twemoji (graphics CC-BY 4.0, see NOTICE.md). Only emoji
that are a single code point (ignoring the U+FE0F variation selector) are included - Minecraft bitmap fonts map one
code point to one glyph. Writes:
  src/client/resources/assets/velo-client/font/emoji.json            bitmap providers, 16 x 16 grids
  src/client/resources/assets/velo-client/textures/font/emoji_<n>.png the atlases (32 px cells)
  src/client/resources/assets/velo-client/emoji/shortcodes.json      {"smile": "😄", ...} (+ "_order": popular first)
"""
import concurrent.futures
import io
import json
import sys
import urllib.request
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/client/resources/assets/velo-client"
GEMOJI = "https://cdn.jsdelivr.net/gh/github/gemoji@v4.1.0/db/emoji.json"
TWEMOJI = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.1.0/assets/72x72/{}.png"
CELL = 32
GRID = 16


def fetch(url, cache):
    path = cache / url.rsplit("/", 1)[-1]
    if not path.exists():
        with urllib.request.urlopen(url, timeout=30) as r:
            path.write_bytes(r.read())
    return path


def main():
    cache = Path(sys.argv[1])
    cache.mkdir(parents=True, exist_ok=True)
    db = json.loads(fetch(GEMOJI, cache).read_text(encoding="utf-8"))
    entries = []
    for e in db:
        cps = [ord(c) for c in e["emoji"] if ord(c) != 0xFE0F]
        if len(cps) != 1:
            continue
        entries.append((cps[0], e["aliases"]))

    def png(cp):
        try:
            return cp, Image.open(fetch(TWEMOJI.format(format(cp, "x")), cache)).convert("RGBA")
        except Exception:
            return cp, None

    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        images = dict(pool.map(png, [cp for cp, _ in entries]))
    entries = [(cp, a) for cp, a in entries if images.get(cp) is not None]

    per = GRID * GRID
    providers = []
    for sheet in range(0, len(entries), per):
        chunk = entries[sheet:sheet + per]
        atlas = Image.new("RGBA", (GRID * CELL, GRID * CELL), (0, 0, 0, 0))
        rows = []
        for r in range(GRID):
            row = ""
            for c in range(GRID):
                i = r * GRID + c
                if i < len(chunk):
                    cp = chunk[i][0]
                    atlas.paste(images[cp].resize((CELL, CELL), Image.LANCZOS), (c * CELL, r * CELL))
                    row += chr(cp)
                else:
                    row += "\u0000"
            rows.append(row)
        n = sheet // per
        (RES / "textures/font").mkdir(parents=True, exist_ok=True)
        atlas.save(RES / f"textures/font/emoji_{n}.png", optimize=True)
        providers.append({"type": "bitmap", "file": f"velo-client:font/emoji_{n}.png", "height": 9, "ascent": 8, "chars": rows})
    (RES / "font").mkdir(parents=True, exist_ok=True)
    (RES / "font/emoji.json").write_text(json.dumps({"providers": providers}, ensure_ascii=False, indent=1), encoding="utf-8")

    shortcodes = {}
    for cp, aliases in entries:
        for a in aliases:
            shortcodes.setdefault(a, chr(cp))
    popular = ["joy", "heart", "fire", "skull", "sob", "thumbsup", "smile", "rofl", "eyes", "pray", "100", "sweat_smile",
               "wave", "clap", "sunglasses", "thinking", "cry", "rage", "sparkles", "tada", "ok_hand", "pleading_face", "heart_eyes"]
    out = {"_order": [p for p in popular if p in shortcodes]}
    out.update(dict(sorted(shortcodes.items())))
    (RES / "emoji").mkdir(parents=True, exist_ok=True)
    (RES / "emoji/shortcodes.json").write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"{len(entries)} emoji, {len(shortcodes)} shortcodes, {len(providers)} atlases")


if __name__ == "__main__":
    main()
