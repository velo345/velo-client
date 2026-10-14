#!/usr/bin/env python3
"""Turns a Meshy (or any single-mesh glTF) pair-of-wings .glb into Velo Client's wing assets.

    python3 tools/cosmetics/import_wings.py <wings.glb> <id> "<Display Name>" [--style feathered|bat|crystal] [--scale 0.9]
        [--dest server|client] [--collection <id>] [--accent #rrggbb] [--price 2500] [--description "..."]

--dest server (the default): the pair lives on the Velo server only - clients download it, no client
update needed. Writes server/cosmetics-seed/wings/<id>.vmesh, <id>.png (512x512) and <id>_preview.png
and adds the item to server/cosmetics-seed/seed.json with status "testing" (only the owner can give it
out; release it in the launcher's Owner panel). The next server build ships it; to add it to a running
server right away use tools/cosmetics/publish_wings.py <id>.

--dest client: also bundled inside the mod (cosmetics/wings/<id>.vmesh + textures/cosmetics/wings/ and an
entry in cosmetics/wings/wings.json) so it shows without downloading. Only worth it for long-lived pairs.

Hand-edited fields of an existing entry (description, price, offsetY, status...) stay as they are.

What the model needs: both wings in one mesh, spread flat and facing forward (+Y up, the wings to
the left and right along X, thin along Z, +Z towards the wearer's back), the two halves meeting at
x = 0 - exactly what Meshy produces for "pair of wings, spread, front view". Metallic / roughness /
normal maps are dropped (Minecraft only uses the colour).

The game animates each half around its hinge (where it meets the body), bending the outer part
more than the inner, so no rig or animation is needed in the file.

.vmesh layout (big-endian, read by WingModel.java):
    "VMSH" int version=1, int halves=2, then per half (left = -X first, then right):
    float hingeX, hingeY, hingeZ, float span, int vertexCount,
    vertexCount * (px py pz nx ny nz u v), int indexCount, indexCount * int
"""
import argparse
import io
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/client/resources/assets/velo-client"
TEXTURE_SIZE = 512


def read_glb(path):
    data = Path(path).read_bytes()
    magic, _, _ = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:
        raise SystemExit("Not a .glb file")
    json_len, _ = struct.unpack_from("<II", data, 12)
    gltf = json.loads(data[20:20 + json_len])
    offset = 20 + json_len
    bin_len, _ = struct.unpack_from("<II", data, offset)
    blob = data[offset + 8:offset + 8 + bin_len]

    def accessor(index):
        a = gltf["accessors"][index]
        view = gltf["bufferViews"][a["bufferView"]]
        kind, size = {5126: ("f4", 4), 5125: ("u4", 4), 5123: ("u2", 2), 5121: ("u1", 1)}[a["componentType"]]
        width = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
        start = view.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = view.get("byteStride") or size * width
        if stride != size * width:
            raise SystemExit("Interleaved buffers aren't supported - re-export without interleaving")
        arr = np.frombuffer(blob[start:start + stride * a["count"]], dtype="<" + kind)
        return arr.reshape(a["count"], width) if width > 1 else arr

    if len(gltf["meshes"]) != 1 or len(gltf["meshes"][0]["primitives"]) != 1:
        raise SystemExit("Expected exactly one mesh with one primitive (Meshy's default export)")
    prim = gltf["meshes"][0]["primitives"][0]
    pos = accessor(prim["attributes"]["POSITION"]).astype(np.float64)
    nrm = accessor(prim["attributes"]["NORMAL"]).astype(np.float64)
    uv = accessor(prim["attributes"]["TEXCOORD_0"]).astype(np.float64)
    idx = accessor(prim["indices"]).astype(np.int64).reshape(-1, 3)

    # Apply the node's matrix if it has one (Meshy's newer exports use an identity matrix).
    node = next(n for n in gltf["nodes"] if "mesh" in n)
    if "matrix" in node:
        m = np.array(node["matrix"]).reshape(4, 4).T
        pos = (np.c_[pos, np.ones(len(pos))] @ m.T)[:, :3]
        nrm = nrm @ np.linalg.inv(m[:3, :3])
    if any(k in node for k in ("rotation", "scale")):
        raise SystemExit("Node rotation / scale isn't supported - apply transforms before exporting")

    material = gltf["materials"][prim["material"]]
    tex_index = material["pbrMetallicRoughness"]["baseColorTexture"]["index"]
    image = gltf["images"][gltf["textures"][tex_index]["source"]]
    view = gltf["bufferViews"][image["bufferView"]]
    start = view.get("byteOffset", 0)
    texture = Image.open(io.BytesIO(blob[start:start + view["byteLength"]])).convert("RGBA")
    return pos, nrm, uv, idx, texture


def split_half(pos, nrm, uv, idx, left):
    """Triangles whose centre lies on one side, with their own copy of the vertices."""
    centres = pos[idx].mean(axis=1)[:, 0]
    tris = idx[centres < 0] if left else idx[centres >= 0]
    used, remap = np.unique(tris, return_inverse=True)
    sub_pos = pos[used]
    # Hinge: where the half meets the middle - the average of its innermost vertices.
    inner = np.abs(sub_pos[:, 0])
    near = sub_pos[inner <= np.percentile(inner, 4) + 1e-6]
    hinge = np.array([0.0, near[:, 1].mean(), near[:, 2].mean()])
    span = float(np.abs(sub_pos[:, 0]).max())
    return hinge, span, sub_pos, nrm[used], uv[used], remap.reshape(-1, 3)


def write_vmesh(path, halves):
    out = io.BytesIO()
    out.write(b"VMSH")
    out.write(struct.pack(">ii", 1, len(halves)))
    for hinge, span, p, n, t, tris in halves:
        out.write(struct.pack(">ffff", *hinge, span))
        out.write(struct.pack(">i", len(p)))
        lengths = np.linalg.norm(n, axis=1, keepdims=True)
        n = n / np.where(lengths == 0, 1, lengths)
        verts = np.c_[p, n, t].astype(">f4")
        out.write(verts.tobytes())
        flat = tris.reshape(-1).astype(">i4")
        out.write(struct.pack(">i", len(flat)))
        out.write(flat.tobytes())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out.getvalue())


SEED = ROOT / "server/cosmetics-seed"


def upsert_seed(item):
    """Adds / updates an item in server/cosmetics-seed/seed.json, keeping hand-set fields."""
    path = SEED / "seed.json"
    seed = json.loads(path.read_text()) if path.exists() else {"featured": None, "collections": [], "items": []}
    previous = next((e for e in seed["items"] if e["id"] == item["id"]), None)
    if previous:
        merged = dict(item)
        for key in ("description", "price", "status", "collection", "accent", "offsetY", "availableFrom", "availableUntil"):
            if previous.get(key) not in (None, ""):
                merged[key] = previous[key]
        seed["items"][seed["items"].index(previous)] = merged
    else:
        seed["items"].append(item)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(seed, indent="\t") + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("glb")
    parser.add_argument("id", help="lowercase id, e.g. phoenix")
    parser.add_argument("name", help='shown in the game, e.g. "Phoenix Wings"')
    parser.add_argument("--style", default="feathered", choices=["feathered", "bat", "crystal"],
                        help="animation feel: feathered = soft flaps, bat = snappier, crystal = stiff, slower")
    parser.add_argument("--scale", type=float, default=0.9, help="size on the player (1 = model units as blocks)")
    parser.add_argument("--dest", default="server", choices=["server", "client"])
    parser.add_argument("--collection", default=None)
    parser.add_argument("--accent", default="#ff4d4d")
    parser.add_argument("--price", type=int, default=2500)
    parser.add_argument("--description", default="")
    args = parser.parse_args()
    if not args.id.replace("_", "").replace("-", "").isalnum() or args.id.lower() != args.id or len(args.id) > 32:
        raise SystemExit("id must be lowercase letters, digits, _ or - (max 32)")

    pos, nrm, uv, idx, texture = read_glb(args.glb)
    halves = [split_half(pos, nrm, uv, idx, left=True), split_half(pos, nrm, uv, idx, left=False)]
    texture = texture.resize((TEXTURE_SIZE, TEXTURE_SIZE), Image.LANCZOS)

    if args.dest == "client":
        mesh_path = ASSETS / "cosmetics/wings" / f"{args.id}.vmesh"
        tex_path = ASSETS / "textures/cosmetics/wings" / f"{args.id}.png"
        preview_path = ASSETS / "textures/cosmetics/wings" / f"{args.id}_preview.png"
        files = {"mesh": f"client/wings/{args.id}.vmesh", "texture": f"client/wings/textures/{args.id}.png",
                 "preview": f"client/wings/textures/{args.id}_preview.png"}
    else:
        mesh_path = SEED / "wings" / f"{args.id}.vmesh"
        tex_path = SEED / "wings" / f"{args.id}.png"
        preview_path = SEED / "wings" / f"{args.id}_preview.png"
        files = {"mesh": f"wings/{args.id}.vmesh", "texture": f"wings/{args.id}.png", "preview": f"wings/{args.id}_preview.png"}
    write_vmesh(mesh_path, halves)
    tex_path.parent.mkdir(parents=True, exist_ok=True)
    texture.save(tex_path, optimize=True)

    if args.dest == "client":
        index_path = ASSETS / "cosmetics/wings/wings.json"
        entries = json.loads(index_path.read_text()) if index_path.exists() else []
        # Keep anything set by hand on an existing entry (description, price, offsetY...).
        previous = next((e for e in entries if e["id"] == args.id), {})
        entries = [e for e in entries if e["id"] != args.id]
        entry = dict(previous)
        entry.update({"id": args.id, "name": args.name, "style": args.style, "scale": args.scale})
        entry.setdefault("description", args.description)
        entry.setdefault("price", args.price)
        entry.setdefault("accent", args.accent)
        if args.collection:
            entry.setdefault("collection", args.collection)
        entries.append(entry)
        entries.sort(key=lambda e: e["name"])
        index_path.write_text(json.dumps(entries, indent="\t") + "\n")

    upsert_seed({"id": "wings_" + args.id, "type": "wings", "name": args.name, "description": args.description,
                 "price": args.price, "status": "testing", "collection": args.collection, "accent": args.accent,
                 "style": args.style, "scale": args.scale, "files": files})

    import importlib.util
    spec = importlib.util.spec_from_file_location("previews", Path(__file__).with_name("render_wing_previews.py"))
    previews = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(previews)
    previews.render(mesh_path, tex_path, args.scale, preview_path)

    tris = sum(len(h[5]) for h in halves)
    print(f"{args.id}: {tris} triangles, hinges at y={halves[0][0][1]:.2f}/{halves[1][0][1]:.2f}, "
          f"span {halves[0][1]:.2f}/{halves[1][1]:.2f} -> {mesh_path.relative_to(ROOT)} ({args.dest})")


if __name__ == "__main__":
    main()
