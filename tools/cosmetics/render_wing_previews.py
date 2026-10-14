#!/usr/bin/env python3
"""Renders a still picture of every pair (3/4 view from behind, lit, transparent background) to
<id>_preview.png - shown by the Store where there's no player to put the wings on (the title
screen) and by the launcher's thumbnails. Covers the pairs bundled with the client (wings.json)
and the server-only ones in server/cosmetics-seed/seed.json.

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


SEED = ROOT / "server/cosmetics-seed"


def load(mesh_path, scale):
    b = Path(mesh_path).read_bytes()
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


def render(mesh_path, texture_path, scale, out_path):
    tex = np.array(Image.open(texture_path).convert("RGB")).astype(float)
    halves = load(mesh_path, scale)
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
    out.save(out_path, optimize=True)
    print("wrote", out_path)


def render_client(wid, scale):
    render(ASSETS / f"cosmetics/wings/{wid}.vmesh", ASSETS / f"textures/cosmetics/wings/{wid}.png", scale,
           ASSETS / f"textures/cosmetics/wings/{wid}_preview.png")


def render_seed(item):
    """A server-only pair: files live next to seed.json (paths in item["files"])."""
    files = item["files"]
    if files["mesh"].startswith("client/"):
        return  # bundled with the client - rendered above
    render(SEED / files["mesh"], SEED / files["texture"], item.get("scale", 0.9), SEED / files["preview"])


if __name__ == "__main__":
    for entry in json.loads((ASSETS / "cosmetics/wings/wings.json").read_text()):
        render_client(entry["id"], entry.get("scale", 0.9))
    seed = SEED / "seed.json"
    if seed.exists():
        for item in json.loads(seed.read_text())["items"]:
            if item["type"] == "wings":
                render_seed(item)
