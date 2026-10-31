#!/usr/bin/env python3
"""Puts the bodywear made by tools/cosmetics/wear/blender_wear.py into server/cosmetics-seed/seed.json.

    tools/cosmetics/wear/blender_wear.py (through Blender) -> server/cosmetics-seed/wear/<id>.{vprop,png,_mask.png,_preview.png,json}
    python3 tools/cosmetics/make_wear.py

Each item becomes "wear_<id>" (status testing, collection "wardrobe") with its files (mesh, mesh_l for the left foot,
texture, mask when it can be recoloured, preview) and its model: {"bone", "mirror"}. Price and status already in the
seed are kept. "hash" fingerprints the files: when they change, "rev" goes up so a running server refreshes the art.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = ROOT / "server/cosmetics-seed"
WEAR = SEED / "wear"


def main():
    seed_path = SEED / "seed.json"
    seed = json.loads(seed_path.read_text())
    seed["items"] = [i for i in seed["items"] if i.get("type") != "wear" or (WEAR / (i["id"][5:] + ".json")).exists()]
    if not any(c["id"] == "wardrobe" for c in seed["collections"]):
        seed["collections"].append({"id": "wardrobe", "name": "Wardrobe", "tag": "BODYWEAR",
                                    "description": "Hats, ears, glasses, watches, chains, shoes and backpacks - one per slot, mix and match, recolour them."})
    known = {i["id"]: i for i in seed["items"]}
    count = 0
    for meta_path in sorted(WEAR.glob("*.json")):
        wear_id = meta_path.stem
        meta = json.loads(meta_path.read_text())
        files = {"mesh": f"wear/{wear_id}.vprop", "texture": f"wear/{wear_id}.png", "preview": f"wear/{wear_id}_preview.png"}
        if meta.get("mirror"):
            files["mesh_l"] = f"wear/{wear_id}_l.vprop"
        if meta.get("tint"):
            files["mask"] = f"wear/{wear_id}_mask.png"
        model = {"bone": meta["bone"]}
        if meta.get("mirror"):
            model["mirror"] = meta["mirror"]
        digest = hashlib.sha256(b"".join((SEED / f).read_bytes() for f in files.values())).hexdigest()[:16]
        entry = {"id": "wear_" + wear_id, "rev": meta.get("rev", 1), "hash": digest, "type": "wear", "name": meta["name"], "description": meta["description"],
                 "price": meta["price"], "status": "testing", "collection": "yuletide" if wear_id == "santa_hat" else "wardrobe", "accent": meta["accent"], "slot": meta["slot"],
                 "model": model, "files": files}
        old = known.get(entry["id"])
        if old:
            entry["price"], entry["status"] = old.get("price", entry["price"]), old.get("status", entry["status"])
            entry["rev"] = max(entry["rev"], old.get("rev", 1) + (old.get("hash") != digest))
            old.clear()
            old.update(entry)
        else:
            seed["items"].append(entry)
        count += 1
    seed_path.write_text(json.dumps(seed, indent="\t", ensure_ascii=False) + "\n")
    print("bodywear in the seed:", count)


if __name__ == "__main__":
    main()
