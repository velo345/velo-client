#!/usr/bin/env python3
"""Turns the Meshy props (meshy_props.py -> work/props/<id>.glb) into game assets:

    assets/velo-client/cosmetics/emote/props/<id>.vprop     the mesh
    assets/velo-client/textures/cosmetics/emote/props/<id>.png   its texture (512 px)

    python3 tools/cosmetics/emotes/import_props.py [--preview]

.vprop (big endian, like .vmesh): "VPRP", int version 1, int vertexCount, then per vertex x y z nx ny nz u v (floats),
int indexCount, indices (ints). Model space: y up, the prop's front towards +Z, standing on y = 0 centred on x = z = 0,
its largest side 1.0 long - the emote says how big it is in blocks.
--preview renders each prop from three angles into work/props/<id>_check.png.
"""
import io
import json
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
WORK = Path(__file__).resolve().parent / "work/props"
MESH_OUT = ROOT / "src/client/resources/assets/velo-client/cosmetics/emote/props"
TEX_OUT = ROOT / "src/client/resources/assets/velo-client/textures/cosmetics/emote/props"
TEX_SIZE = 512
# props Meshy stood on a ground plate anyway: triangles below this height (of the normalised prop) are cut off
CROP = {"snowball": 0.16}
DTYPES = {5120: "i1", 5121: "u1", 5122: "<i2", 5123: "<u2", 5125: "<u4", 5126: "<f4"}
WIDTH = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def read_glb(path):
    data = path.read_bytes()
    jl = struct.unpack_from("<I", data, 12)[0]
    gltf = json.loads(data[20:20 + jl])
    blob = data[20 + jl + 8:]

    def accessor(i):
        a = gltf["accessors"][i]
        v = gltf["bufferViews"][a["bufferView"]]
        w = WIDTH[a["type"]]
        dt = np.dtype(DTYPES[a["componentType"]])
        start = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = v.get("byteStride", 0)
        if stride and stride != dt.itemsize * w:
            raw = np.frombuffer(blob, dtype="u1", count=stride * a["count"], offset=start).reshape(a["count"], stride)
            arr = raw[:, : dt.itemsize * w].copy().view(dt).reshape(a["count"], w)
        else:
            arr = np.frombuffer(blob, dtype=dt, count=a["count"] * w, offset=start).reshape(a["count"], w)
        arr = arr.astype(np.float64)
        if a.get("normalized") and dt.kind in "iu":
            arr /= np.iinfo(dt).max
        return arr if w > 1 else arr[:, 0]

    def image(i):
        img = gltf["images"][i]
        v = gltf["bufferViews"][img["bufferView"]]
        return Image.open(io.BytesIO(blob[v.get("byteOffset", 0):v.get("byteOffset", 0) + v["byteLength"]])).convert("RGB")
    return gltf, accessor, image


def quat_mat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"]).reshape(4, 4).T
    m = np.eye(4)
    m[:3, :3] = quat_mat(n.get("rotation", [0, 0, 0, 1])) * np.array(n.get("scale", [1, 1, 1]))
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


def load(prop_id):
    gltf, accessor, image = read_glb(WORK / f"{prop_id}.glb")
    pos, nrm, uv, idx = [], [], [], []
    texture = None

    def visit(i, parent):
        nonlocal texture
        n = gltf["nodes"][i]
        m = parent @ node_matrix(n)
        if "mesh" in n:
            for prim in gltf["meshes"][n["mesh"]]["primitives"]:
                at = prim["attributes"]
                p = accessor(at["POSITION"])
                q = accessor(at["NORMAL"]) if "NORMAL" in at else np.zeros_like(p)
                t = accessor(at["TEXCOORD_0"]) if "TEXCOORD_0" in at else np.zeros((len(p), 2))
                ix = accessor(prim["indices"]).astype(int) if "indices" in prim else np.arange(len(p))
                base = sum(len(a) for a in pos)
                pos.append((m[:3, :3] @ p.T).T + m[:3, 3])
                nn = (np.linalg.inv(m[:3, :3]).T @ q.T).T
                nrm.append(nn / np.maximum(1e-9, np.linalg.norm(nn, axis=1))[:, None])
                uv.append(t)
                idx.append(ix + base)
                mat = gltf["materials"][prim.get("material", 0)] if gltf.get("materials") else {}
                tex = mat.get("pbrMetallicRoughness", {}).get("baseColorTexture")
                if tex is not None and texture is None:
                    texture = image(gltf["textures"][tex["index"]]["source"])
        for c in n.get("children", []):
            visit(c, m)

    for root in gltf["scenes"][gltf.get("scene", 0)]["nodes"]:
        visit(root, np.eye(4))
    p, n, t, ix = normalise(np.concatenate(pos)), np.concatenate(nrm), np.concatenate(uv), np.concatenate(idx)
    if prop_id in CROP:
        tris = ix.reshape(-1, 3)
        keep = p[tris].mean(axis=1)[:, 1] >= CROP[prop_id]
        used = np.unique(tris[keep])
        remap = -np.ones(len(p), dtype=int)
        remap[used] = np.arange(len(used))
        p, n, t, ix = normalise(p[used]), n[used], t[used], remap[tris[keep]].reshape(-1)
    return p, n, t, ix, texture


def normalise(p):
    """Standing on y = 0, centred on x = z = 0, largest side 1."""
    lo, hi = p.min(axis=0), p.max(axis=0)
    center = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    return (p - center) / max(hi - lo)


def write(prop_id, p, n, t, ix, texture):
    MESH_OUT.mkdir(parents=True, exist_ok=True)
    TEX_OUT.mkdir(parents=True, exist_ok=True)
    out = io.BytesIO()
    out.write(b"VPRP")
    out.write(struct.pack(">ii", 1, len(p)))
    verts = np.hstack([p, n, t]).astype(">f4")
    out.write(verts.tobytes())
    out.write(struct.pack(">i", len(ix)))
    out.write(ix.astype(">i4").tobytes())
    (MESH_OUT / f"{prop_id}.vprop").write_bytes(out.getvalue())
    texture.resize((TEX_SIZE, TEX_SIZE), Image.LANCZOS).save(TEX_OUT / f"{prop_id}.png", optimize=True)


def preview(prop_id, p, n, t, ix, texture):
    """Three flat-shaded views (front, side, top) to check orientation."""
    from PIL import ImageDraw
    tex = np.asarray(texture.resize((256, 256)))
    sheet = Image.new("RGB", (3 * 256, 256), (34, 36, 44))
    tris = ix.reshape(-1, 3)
    views = [("front", lambda v: (v[:, 0], -v[:, 1], -v[:, 2])), ("side", lambda v: (-v[:, 2], -v[:, 1], -v[:, 0])),
             ("top", lambda v: (v[:, 0], v[:, 2], -v[:, 1]))]
    for k, (name, proj) in enumerate(views):
        img = Image.new("RGB", (256, 256), (34, 36, 44))
        dr = ImageDraw.Draw(img)
        sx, sy, depth = proj(p)
        order = np.argsort(-depth[tris].mean(axis=1))
        for tri in tris[order]:
            uvc = t[tri].mean(axis=0)
            c = tex[int(np.clip(uvc[1], 0, 0.999) * 256), int(np.clip(uvc[0], 0, 0.999) * 256)]
            light = 0.55 + 0.45 * max(0.0, float(np.dot(n[tri].mean(axis=0), [0.3, 0.6, 0.75])))
            dr.polygon([(128 + sx[i] * 200, 200 + sy[i] * 200 if name != "top" else 128 + sy[i] * 200) for i in tri],
                       fill=tuple(int(min(255, v * light)) for v in c))
        dr.text((4, 4), name, fill=(255, 255, 0))
        sheet.paste(img, (k * 256, 0))
    sheet.save(WORK / f"{prop_id}_check.png")


def main():
    for glb in sorted(WORK.glob("*.glb")):
        prop_id = glb.stem
        p, n, t, ix, texture = load(prop_id)
        write(prop_id, p, n, t, ix, texture)
        size = (MESH_OUT / f"{prop_id}.vprop").stat().st_size // 1024
        print(f"{prop_id:12s} {len(ix) // 3:5d} tris {len(p):5d} verts  {size} KB  extent {np.round(p.max(axis=0) - p.min(axis=0), 2)}")
        if "--preview" in sys.argv:
            preview(prop_id, p, n, t, ix, texture)


if __name__ == "__main__":
    main()
