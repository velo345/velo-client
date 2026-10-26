#!/usr/bin/env python3
"""Renders an emote's pose with its props at a few moments (front and side), using the same transforms as the game's
EmoteFxRenderer - to check a teacup sits in the hand and a hat on the head without starting the game.

    python3 tools/cosmetics/emotes/check_props.py <id> [t...]      -> work/pv/props_<id>.png
"""
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ASSETS = ROOT / "src/client/resources/assets/velo-client"
BOXES = [(-4, -8, -4, 8, 8, 8), (-4, 0, -2, 8, 12, 4), (-3, -2, -2, 4, 12, 4), (-1, -2, -2, 4, 12, 4), (-2, 0, -2, 4, 12, 4),
         (-2, 0, -2, 4, 12, 4)]
COLOURS = [(222, 180, 140), (40, 180, 190), (200, 150, 120), (200, 150, 120), (70, 60, 170), (90, 80, 190)]
PART = {"head": 0, "chest": 1, "rhand": 2, "lhand": 3}


def rx(a):
    return np.array([[1, 0, 0, 0], [0, math.cos(a), -math.sin(a), 0], [0, math.sin(a), math.cos(a), 0], [0, 0, 0, 1]])


def ry(a):
    return np.array([[math.cos(a), 0, math.sin(a), 0], [0, 1, 0, 0], [-math.sin(a), 0, math.cos(a), 0], [0, 0, 0, 1]])


def rz(a):
    return np.array([[math.cos(a), -math.sin(a), 0, 0], [math.sin(a), math.cos(a), 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]])


def tr(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def sc(s):
    return np.diag([s, s, s, 1.0])


def part_matrix(pose, i):
    """ModelPart.applyTransform: pivot / 16, then Z * Y * X - in blocks."""
    px, py, pz, xr, yr, zr = pose[i]
    return tr(px / 16, py / 16, pz / 16) @ rz(zr) @ ry(yr) @ rx(xr)


def prop_matrix(p):
    """EmoteFxRenderer.propMatrix, for the prop as it rests (no pop / rise animation)."""
    at = p["at"]
    x, y, z = p["pos"]
    m = np.eye(4)
    if at == "ground":
        m = m @ tr(-x, 1.501 - y, -z)
    elif at == "rhand":
        m = m @ tr(-1 / 16, 10 / 16, 0)
    elif at == "lhand":
        m = m @ tr(1 / 16, 10 / 16, 0)
    elif at == "head":
        m = m @ tr(0, -8 / 16, 0)
    m = m @ rx(math.pi)
    if at != "ground":
        m = m @ tr(x, y, z)
    yaw, pitch, roll = (math.radians(v) for v in p.get("rot", [0, 0, 0]))
    return m @ ry(yaw) @ rx(pitch) @ rz(roll) @ sc(p["scale"])


def read_prop(prop_id):
    data = (ASSETS / f"cosmetics/emote/props/{prop_id}.vprop").read_bytes()
    count = struct.unpack_from(">i", data, 8)[0]
    v = np.frombuffer(data, dtype=">f4", count=count * 8, offset=12).reshape(count, 8).astype(float)
    n = struct.unpack_from(">i", data, 12 + count * 32)[0]
    ix = np.frombuffer(data, dtype=">i4", count=n, offset=16 + count * 32).reshape(-1, 3)
    tex = np.asarray(Image.open(ASSETS / f"textures/cosmetics/emote/props/{prop_id}.png").convert("RGB").resize((128, 128)))
    return v, ix, tex


def pose_at(emote, t):
    frames = emote["frames"]
    f = t * emote["fps"]
    if emote["loop"]:
        f %= len(frames) - 1
    k = min(len(frames) - 1, int(f))
    return np.array(frames[k], dtype=float)


def render(emote_id, times):
    emote = json.loads((ASSETS / f"emotes/{emote_id}.vemote.json").read_text())
    props = [e for e in emote["events"] if "prop" in e]
    sheet = Image.new("RGB", (len(times) * 360, 170), (30, 30, 38))
    for col, t in enumerate(times):
        pose = pose_at(emote, t)
        for row, view in enumerate(("front", "side")):
            quads = []
            for i, (ox, oy, oz, w, h, d) in enumerate(BOXES):
                m = part_matrix(pose, i)
                corners = [m @ np.array([(ox + w * a) / 16, (oy + h * b) / 16, (oz + d * c) / 16, 1]) for a in (0, 1) for b in (0, 1) for c in (0, 1)]
                for face in ((0, 1, 3, 2), (4, 5, 7, 6), (0, 1, 5, 4), (2, 3, 7, 6), (0, 2, 6, 4), (1, 3, 7, 5)):
                    quads.append(([corners[k][:3] for k in face], COLOURS[i]))
            for p in props:
                if not (p["t"] <= t and (p["dur"] == 0 or t <= p["t"] + p["dur"])):
                    continue
                v, ix, tex = read_prop(p["prop"])
                m = prop_matrix(p)
                if p["at"] in PART:
                    m = part_matrix(pose, PART[p["at"]]) @ m
                pts = (m @ np.hstack([v[:, :3], np.ones((len(v), 1))]).T).T[:, :3]
                for tri in ix:
                    uv = v[tri, 6:8].mean(axis=0)
                    c = tex[int(np.clip(uv[1], 0, 0.999) * 128), int(np.clip(uv[0], 0, 0.999) * 128)]
                    quads.append(([pts[k] for k in tri], tuple(int(x) for x in c)))
            img = Image.new("RGB", (180, 170), (30, 30, 38))
            dr = ImageDraw.Draw(img)

            def proj(q):
                return (q[0], q[1], q[2]) if view == "front" else (q[2], q[1], -q[0])
            quads.sort(key=lambda e: -np.mean([proj(q)[2] for q in e[0]]))
            for pts, c in quads:
                dr.polygon([(90 + proj(q)[0] * 48, 48 + proj(q)[1] * 48) for q in pts], fill=c)
            dr.text((3, 3), f"{view} t={t}", fill=(255, 255, 0))
            sheet.paste(img, (col * 360 + row * 180, 0))
    out = HERE / f"work/pv/props_{emote_id}.png"
    sheet.save(out)
    print("wrote", out)


if __name__ == "__main__":
    render(sys.argv[1], [float(a) for a in sys.argv[2:]] or [0.5, 2.0, 4.0])
