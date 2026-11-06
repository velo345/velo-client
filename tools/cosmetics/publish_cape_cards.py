#!/usr/bin/env python3
"""Makes the small "card" art of every animated cloak on a running Velo server and uploads it as the item's `card` file.

The full cloak GIFs are 2048 x 1024 with 40 frames (~8 MB each, ~335 MB once decoded): Store and Locker grids on the
website, in the launcher and in the game showed those full files at card size, which made every cloak page crawl.
A card is the same 64 x 32 layout, every frame, at CARD_WIDTH px (12 texture pixels per cloak pixel - more than any
card shows) - about 1.3 MB. Clients use `card` for grids and keep the full `gif` for the detail view and for wearing.

    python3 tools/cosmetics/publish_cape_cards.py            every cloak (skips ones whose card is already current)
    python3 tools/cosmetics/publish_cape_cards.py --force    remake all
    python3 tools/cosmetics/publish_cape_cards.py cape_glacier

Only the `card` file changes (the server merges files); price, status, name and the `gif` stay as they are.
publish_cape_art.py calls make_card() too, so new art always gets a matching card.
Needs VELO_SERVER_URL and VELO_ADMIN_TOKEN (environment or the repository's .env). Run with the system python3
(its Pillow has libimagequant).
"""
import io
import json
import sys
import urllib.request
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from publish_wings import call, env  # noqa: E402

CARD_WIDTH = 768


def make_card(gif_bytes, width=CARD_WIDTH):
    """The card version of a cloak GIF: same layout and frames, scaled to `width`, transparency kept."""
    im = Image.open(io.BytesIO(gif_bytes))
    if im.size[0] <= width:
        return None  # already small - the card would be the same file
    frames, durations = [], []
    for i in range(getattr(im, "n_frames", 1)):
        im.seek(i)
        durations.append(im.info.get("duration", 60))
        frame = im.convert("RGBA").resize((width, width * im.size[1] // im.size[0]), Image.LANCZOS)
        alpha = frame.split()[3]
        paletted = frame.convert("RGB").quantize(colors=255, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.FLOYDSTEINBERG)
        paletted.paste(255, mask=alpha.point(lambda v: 255 if v < 128 else 0))
        frames.append(paletted)
    out = io.BytesIO()
    frames[0].save(out, format="GIF", save_all=True, append_images=frames[1:], duration=durations, loop=0, disposal=2,
                   transparency=255, optimize=False)
    return out.getvalue()


def main():
    base, token = env("VELO_SERVER_URL"), env("VELO_ADMIN_TOKEN")
    force = "--force" in sys.argv
    wanted = {a for a in sys.argv[1:] if not a.startswith("--")}
    request = urllib.request.Request(base.rstrip("/") + "/v1/admin/cosmetics", headers={"X-Velo-Admin-Token": token})
    with urllib.request.urlopen(request, timeout=60) as response:
        items = json.loads(response.read())["items"]
    done = skipped = 0
    for item in items:
        files = item.get("files") or {}
        if item.get("type") != "cape" or "gif" not in files or (wanted and item["id"] not in wanted):
            continue
        # A card made from this exact gif is remembered as `card_of` = the gif's sha.
        if not force and files.get("card") and files.get("card_of") == files["gif"]:
            skipped += 1
            continue
        with urllib.request.urlopen(base.rstrip("/") + "/v1/cosmetics/file/" + files["gif"], timeout=120) as response:
            gif = response.read()
        card = make_card(gif)
        if card is None:
            print(f"{item['id']}: already small, no card needed")
            continue
        sha = call(base, token, "/v1/admin/cosmetics/file", card, "application/octet-stream")["sha256"]
        call(base, token, "/v1/admin/cosmetics/item",
             json.dumps({"item": {"id": item["id"], "files": {"card": sha, "card_of": files["gif"]}}, "clearWindow": False}).encode(),
             "application/json")
        print(f"{item['id']}: card {len(card) // 1024} KB (gif {len(gif) // 1024} KB) -> {sha[:12]}")
        done += 1
    print(f"{done} card(s) made, {skipped} already current")


if __name__ == "__main__":
    main()
