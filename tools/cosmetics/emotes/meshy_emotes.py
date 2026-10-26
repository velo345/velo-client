#!/usr/bin/env python3
"""Gets emote animations from Meshy (needs MESHY_API_KEY in .env) for Velo Client's emote wheel.

    python3 tools/cosmetics/emotes/meshy_emotes.py steve            # writes work/steve.glb (the model Meshy rigs)
    python3 tools/cosmetics/emotes/meshy_emotes.py library [search]  # lists Meshy's animation library (free)
    python3 tools/cosmetics/emotes/meshy_emotes.py rig               # rigs work/steve.glb -> work/rig.json (task id)
    python3 tools/cosmetics/emotes/meshy_emotes.py animate <action_id>...   # up to 10 per call -> work/anim_<task>.glb

Then tools/cosmetics/emotes/import_emotes.py turns the downloaded clips into .vemote files.

The model is a Minecraft player (Steve's skin, real player proportions) in a T-pose facing +Z - Meshy's rigger
wants a textured humanoid facing +Z. Only its joint motion is used later, so the shape just has to be read as a
person. Work files go to tools/cosmetics/emotes/work/ (git-ignored).
"""
import base64
import io
import json
import os
import struct
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
WORK = Path(__file__).resolve().parent / "work"
API = "https://api.meshy.ai/openapi/v1"
PX = 0.9 / 16  # one model pixel in metres: the 32 px tall player becomes 1.8 m


def env(name):
    value = os.environ.get(name)
    if value:
        return value
    for line in (ROOT / ".env").read_text().splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(name + " missing (.env)")


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bearer " + env("MESHY_API_KEY"), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Meshy {method} {path}: {e.code} {e.read().decode(errors='replace')[:400]}")


def wait(kind, task_id):
    while True:
        task = call("GET", f"/{kind}/{task_id}")
        status = task.get("status")
        print(f"  {kind} {task_id}: {status} {task.get('progress', 0)}%", flush=True)
        if status == "SUCCEEDED":
            return task
        if status in ("FAILED", "CANCELED"):
            raise SystemExit(f"{kind} task {status}: {task.get('task_error')}")
        time.sleep(5)


def download(url, path):
    with urllib.request.urlopen(url, timeout=300) as r:
        path.write_bytes(r.read())
    print("  saved", path.relative_to(ROOT))


# ---------------------------------------------------------------- the Steve model

def steve_skin():
    jars = sorted(Path.home().glob(".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly-deobf/26.2/*.jar"))
    if not jars:
        raise SystemExit("26.2 client jar not found (build the project once)")
    with zipfile.ZipFile(jars[0]) as z:
        return Image.open(io.BytesIO(z.read("assets/minecraft/textures/entity/player/wide/steve.png"))).convert("RGBA")


def box(origin, size, uv, rot=None, pivot=None):
    """A Minecraft model box (origin/size in pixels, facing +Z) with its standard skin layout at uv (64x64 texture)."""
    x0, y0, z0 = origin
    w, h, d = size
    u, v = uv
    x1, y1, z1 = x0 + w, y0 + h, z0 + d
    # (corners, normal, texture rect (u, v, width, height)) - "front" is +Z, the character's right side is -X
    faces = [
        ([(x1, y1, z1), (x0, y1, z1), (x0, y0, z1), (x1, y0, z1)], (0, 0, 1), (u + d, v + d, w, h)),       # front
        ([(x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (x0, y0, z0)], (0, 0, -1), (u + 2 * d + w, v + d, w, h)),  # back
        ([(x0, y1, z1), (x0, y1, z0), (x0, y0, z0), (x0, y0, z1)], (-1, 0, 0), (u, v + d, d, h)),         # right side
        ([(x1, y1, z0), (x1, y1, z1), (x1, y0, z1), (x1, y0, z0)], (1, 0, 0), (u + d + w, v + d, d, h)),   # left side
        ([(x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1)], (0, 1, 0), (u + d, v, w, d)),           # top
        ([(x1, y0, z1), (x0, y0, z1), (x0, y0, z0), (x1, y0, z0)], (0, -1, 0), (u + d + w, v, w, d)),      # bottom
    ]
    pos, nrm, uvs, idx = [], [], [], []
    for corners, n, (fu, fv, fw, fh) in faces:
        base = len(pos)
        rect = [(fu, fv), (fu + fw, fv), (fu + fw, fv + fh), (fu, fv + fh)]
        for c, t in zip(corners, rect):
            p = np.array(c, dtype=float)
            nn = np.array(n, dtype=float)
            if rot is not None:
                p = rot @ (p - pivot) + pivot
                nn = rot @ nn
            pos.append(p)
            nrm.append(nn)
            uvs.append((t[0] / 64.0, t[1] / 64.0))
        idx += [base, base + 1, base + 2, base, base + 2, base + 3]
    return pos, nrm, uvs, idx


def rot_z(deg):
    a = np.radians(deg)
    return np.array([[np.cos(a), -np.sin(a), 0], [np.sin(a), np.cos(a), 0], [0, 0, 1]])


def make_steve():
    # Pixel coordinates: feet at y = 0, centred on x = 0, facing +Z. Arms straight out (T-pose).
    parts = [
        box((-4, 24, -4), (8, 8, 8), (0, 0)),       # head
        box((-4, 12, -2), (8, 12, 4), (16, 16)),     # body
        box((-4, 0, -2), (4, 12, 4), (0, 16)),       # right leg (-X)
        box((0, 0, -2), (4, 12, 4), (16, 48)),       # left leg (+X)
        box((-8, 12, -2), (4, 12, 4), (40, 16), rot_z(-90), np.array([-5.0, 22.0, 0.0])),  # right arm, out to -X
        box((4, 12, -2), (4, 12, 4), (32, 48), rot_z(90), np.array([5.0, 22.0, 0.0])),     # left arm, out to +X
    ]
    pos, nrm, uvs, idx = [], [], [], []
    for p, n, t, i in parts:
        base = len(pos)
        pos += p
        nrm += n
        uvs += t
        idx += [base + k for k in i]
    pos = np.array(pos, dtype=np.float32) * PX
    nrm = np.array(nrm, dtype=np.float32)
    uvs = np.array(uvs, dtype=np.float32)
    idx = np.array(idx, dtype=np.uint32)
    skin = steve_skin().resize((1024, 1024), Image.NEAREST)
    png = io.BytesIO()
    skin.save(png, "PNG")
    return write_glb(pos, nrm, uvs, idx, png.getvalue())


def write_glb(pos, nrm, uvs, idx, png):
    chunks = [pos.tobytes(), nrm.tobytes(), uvs.tobytes(), idx.tobytes(), png]
    views, blob = [], b""
    for c in chunks:
        while len(blob) % 4:
            blob += b"\0"
        views.append({"buffer": 0, "byteOffset": len(blob), "byteLength": len(c)})
        blob += c
    while len(blob) % 4:
        blob += b"\0"
    gltf = {
        "asset": {"version": "2.0", "generator": "velo meshy_emotes.py"},
        "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0, "name": "Steve"}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0}]}],
        "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 1.0}}],
        "textures": [{"source": 0, "sampler": 0}],
        "samplers": [{"magFilter": 9728, "minFilter": 9728}],
        "images": [{"bufferView": 4, "mimeType": "image/png"}],
        "buffers": [{"byteLength": len(blob)}],
        "bufferViews": views,
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(pos), "type": "VEC3",
             "min": pos.min(axis=0).tolist(), "max": pos.max(axis=0).tolist()},
            {"bufferView": 1, "componentType": 5126, "count": len(nrm), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": len(uvs), "type": "VEC2"},
            {"bufferView": 3, "componentType": 5125, "count": len(idx), "type": "SCALAR"},
        ],
    }
    js = json.dumps(gltf).encode()
    while len(js) % 4:
        js += b" "
    total = 12 + 8 + len(js) + 8 + len(blob)
    return (struct.pack("<III", 0x46546C67, 2, total) + struct.pack("<II", len(js), 0x4E4F534A) + js
            + struct.pack("<II", len(blob), 0x004E4942) + blob)


# ---------------------------------------------------------------- commands

def main():
    WORK.mkdir(parents=True, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "steve":
        (WORK / "steve.glb").write_bytes(make_steve())
        print("wrote", (WORK / "steve.glb").relative_to(ROOT))
    elif cmd == "library":
        query = "?search=" + urllib.parse.quote(sys.argv[2]) if len(sys.argv) > 2 else ""
        items = call("GET", "/animations/library" + query)
        items = items if isinstance(items, list) else items.get("result", items.get("data", []))
        (WORK / "library.json").write_text(json.dumps(items, indent=1))
        for a in items:
            print(f"{a['action_id']:5d}  {a['category']:13s} {a.get('sub_category', ''):22s} {a['name']}")
    elif cmd == "rig":
        glb = (WORK / "steve.glb").read_bytes()
        uri = "data:application/octet-stream;base64," + base64.b64encode(glb).decode()
        task_id = call("POST", "/rigging", {"model_url": uri, "height_meters": 1.8})["result"]
        print("rigging task", task_id)
        task = wait("rigging", task_id)
        (WORK / "rig.json").write_text(json.dumps(task, indent=1))
        download(task["result"]["rigged_character_glb_url"], WORK / "rigged.glb")
    elif cmd == "animate":
        ids = [int(a) for a in sys.argv[2:]]
        if not 1 <= len(ids) <= 10:
            raise SystemExit("1-10 action ids")
        rig = json.loads((WORK / "rig.json").read_text())
        task_id = call("POST", "/animations", {"rig_task_id": rig["id"], "action_ids": ids})["result"]
        print("animation task", task_id)
        task = wait("animations", task_id)
        (WORK / f"anim_{task_id}.json").write_text(json.dumps({"ids": ids, "task": task}, indent=1))
        download(task["result"]["animation_glb_url"], WORK / f"anim_{task_id}.glb")
    else:
        print(__doc__)


if __name__ == "__main__":
    import urllib.parse  # noqa: F401 (library search)
    main()
