#!/usr/bin/env python3
"""Responsive WebP variants for the site's photos (called by build.js on dist/).

For every .jpg/.png/.webp under dist/news/*/images and dist/assets/partners: <name>-160.webp, -480.webp and -960.webp
(only widths smaller than the picture). optimize.js then adds them as srcset. Results are cached by content hash in
~/.cache/velo-site-img, so a build only converts new pictures.
"""
import hashlib, os, sys, shutil
from PIL import Image

dist = sys.argv[1]
cache = os.path.expanduser("~/.cache/velo-site-img")
os.makedirs(cache, exist_ok=True)
roots = [os.path.join(dist, "news"), os.path.join(dist, "assets", "partners")]
made = 0
dims = {}
for root in roots:
    for d, _, files in os.walk(root):
        for f in files:
            name, ext = os.path.splitext(f)
            if ext.lower() not in (".jpg", ".jpeg", ".png", ".webp") or name.rsplit("-", 1)[-1] in ("160", "480", "960"):
                continue
            src = os.path.join(d, f)
            data = open(src, "rb").read()
            key = hashlib.sha1(data).hexdigest()[:16]
            try:
                with Image.open(src) as probe:
                    dims["/" + os.path.relpath(src, dist).replace(os.sep, "/")] = probe.width
            except Exception:
                pass
            try:
                im = None
                for w in (160, 480, 960):
                    out = os.path.join(d, f"{name}-{w}.webp")
                    cached = os.path.join(cache, f"{key}-{w}.webp")
                    if os.path.exists(cached):
                        shutil.copyfile(cached, out)
                        continue
                    if im is None:
                        im = Image.open(src)
                        if getattr(im, "n_frames", 1) > 1:
                            break  # animated: leave as it is
                        im = im.convert("RGBA" if im.mode in ("RGBA", "LA", "P") else "RGB")
                    if im.width <= w:
                        continue
                    im.resize((w, round(im.height * w / im.width)), Image.LANCZOS).save(cached, quality=78, method=6)
                    shutil.copyfile(cached, out)
                    made += 1
            except Exception as e:
                print(f"  ! {src}: {e}")
import json
json.dump(dims, open(os.path.join(dist, ".image-widths.json"), "w"))
print(f"Images: {made} new WebP variants")
