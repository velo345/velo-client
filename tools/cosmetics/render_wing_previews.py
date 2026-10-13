#!/usr/bin/env python3
"""Renders a still picture of every pair in wings.json (3/4 view from behind, lit, transparent
background) to textures/cosmetics/wings/<id>_preview.png - shown by the Store where there's no
player to put the wings on (the title screen) and by the launcher's thumbnails.

    python3 tools/cosmetics/render_wing_previews.py
"""
import json
import math
import struct
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/client/resources/assets/velo-client"
W, H, SS = 384, 288, 3


def load(wid, scale):
    b = (ASSETS / f"cosmetics/wings/{wid}.vmesh").read_bytes()
    o = 12
    halves = []
    for _ in range(struct.unpack_from(">i", b, 8)[0]):
        hx, hy, hz, span = struct.unpack_from(">ffff", b, o); o += 16
        vc, = struct.unpack_from(">i", b, o); o += 4
        v = np.frombuffer(b[o:o + vc * 32], dtype=">f4").reshape(vc, 8).astype(float); o += vc * 32
        ic, = struct.unpack_from(">i", b, o); o += 4
        idx = np.frombuffer(b[o:o + ic * 4], dtype=">i4").reshape(-1, 3); o += ic * 4
        p = (v[:, :3] - [hx, hy, hz]) * scale
        side = -1 if p[:, 0].sum() < 0 else 1
        # Body space like the game: y down, z backwards; a resting sweep of ~0.4 rad back.
        g = np.c_[p[:, 0], -p[:, 1], -p[:, 2]]
        n = np.c_[v[:, 3], -v[:, 4], -v[:, 5]]
        yaw = -side * 0.4
        c, s = math.cos(yaw), math.sin(yaw)
        g = np.c_[g[:, 0] * c + g[:, 2] * s + side * 0.05, g[:, 1], -g[:, 0] * s + g[:, 2] * c]
        n = np.c_[n[:, 0] * c + n[:, 2] * s, n[:, 1], -n[:, 0] * s + n[:, 2] * c]
        halves.append((g, n, v[:, 6:8], idx))
    return halves


def render(wid, scale):
    tex = np.array(Image.open(ASSETS / f"textures/cosmetics/wings/{wid}.png").convert("RGB")).astype(float)
    halves = load(wid, scale)
    # Camera behind the wearer, turned 28 degrees, looking slightly down.
    yaw, pitch = math.radians(28), math.radians(12)
    light = np.array([0.35, -0.6, 0.7]); light /= np.linalg.norm(light)
    tris = []
    allp = []
    for g, n, uv, idx in halves:
        x = g[:, 0] * math.cos(yaw) - g[:, 2] * math.sin(yaw)
        z = g[:, 0] * math.sin(yaw) + g[:, 2] * math.cos(yaw)
        y = g[:, 1] * math.cos(pitch) - z * math.sin(pitch)
        depth = g[:, 1] * math.sin(pitch) + z * math.cos(pitch)
        allp.append(np.c_[-x, y])
        for t in idx:
            u, vv = uv[t].mean(0)
            col = tex[int(np.clip(vv, 0, .999) * tex.shape[0]), int(np.clip(u, 0, .999) * tex.shape[1])]
            nn = n[t].mean(0); nn /= (np.linalg.norm(nn) or 1)
            shade = 0.55 + 0.45 * abs(float(nn @ light))
            tris.append((depth[t].mean(), [(-x[k], y[k]) for k in t], tuple(int(min(255, ch * shade)) for ch in col)))
    pts = np.vstack(allp)
    mn, mx = pts.min(0), pts.max(0)
    sc = min((W * 0.88) / (mx[0] - mn[0]), (H * 0.88) / (mx[1] - mn[1])) * SS
    ox = W * SS / 2 - (mn[0] + mx[0]) / 2 * sc
    oy = H * SS / 2 - (mn[1] + mx[1]) / 2 * sc
    img = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _, poly, col in sorted(tris, key=lambda q: -q[0]):
        d.polygon([(ox + px * sc, oy + py * sc) for px, py in poly], fill=col + (255,))
    img = img.resize((W, H), Image.LANCZOS)
    # Soft drop shadow so it sits on any background.
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    shadow.putalpha(img.getchannel("A").point(lambda a: a * 0.45).filter(ImageFilter.GaussianBlur(6)))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(shadow, (3, 6))
    out.alpha_composite(img)
    path = ASSETS / f"textures/cosmetics/wings/{wid}_preview.png"
    out.save(path, optimize=True)
    print("wrote", path.relative_to(ROOT))


for entry in json.loads((ASSETS / "cosmetics/wings/wings.json").read_text()):
    render(entry["id"], entry.get("scale", 0.9))
