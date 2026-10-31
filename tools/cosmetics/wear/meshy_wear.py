#!/usr/bin/env python3
"""Makes the bodywear models with Meshy text-to-3D (needs MESHY_API_KEY in .env), the same way as the emote props:
a preview remeshed to a low triangle count (drawn on every player that wears it), then refined - the texture carries
the detail.

    python3 tools/cosmetics/wear/meshy_wear.py [id...]     # all without a work/<id>.glb, or just these

Downloads go to tools/cosmetics/wear/work/<id>.glb; make_wear.py turns them into .vprop + texture for the catalog.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "emotes"))
from meshy_emotes import download  # noqa: E402
from meshy_props import v2, wait  # noqa: E402

WORK = Path(__file__).resolve().parent / "work"
STYLE = ("high quality stylized game asset, clean shapes, detailed hand-painted texture, single object, centered, "
         "no base, no ground plane, no mannequin, no head, no body")

# id -> (prompt, target triangles)
WEAR = {
    "watch": ("a luxury wristwatch with a round steel case, dark blue dial with silver hands, and a closed steel link "
              "bracelet forming a ring, seen standing up", 2500),
    "sneakers": ("a single right high-top basketball sneaker, red leather with white sole, white laces and white swoosh "
                 "accents, side view", 2500),
    "sandals": ("a single right summer sandal, cork footbed sole with two brown leather straps across the top", 1500),
    "top_hat": ("a tall elegant black silk top hat with a wide flat brim and a red satin band", 1800),
    "cap": ("a blue baseball cap with a curved front brim and a small white button on top, no logo", 1800),
    "beanie": ("a cozy knitted orange beanie hat with a folded cuff and a big fluffy white pompom on top", 2000),
    "cat_ears": ("a pair of fluffy black cat ears with pink inner fur on a thin black headband", 1800),
    "bunny_ears": ("a pair of tall white bunny ears with pink insides on a thin white headband, one ear slightly bent", 1800),
    "sunglasses": ("cool black aviator style sunglasses with dark reflective lenses and thin black frame, front view, "
                   "arms folded back straight", 1500),
    "round_glasses": ("round thin golden wire-frame glasses with clear lenses, front view, arms straight back", 1200),
    "backpack": ("a green adventure backpack with a front pocket, buckles and two shoulder straps, compact and boxy", 2500),
}


def make(wear_id):
    prompt, tris = WEAR[wear_id]
    WORK.mkdir(parents=True, exist_ok=True)
    state_file = WORK / f"{wear_id}.json"
    state = json.loads(state_file.read_text()) if state_file.exists() else {}
    if "preview" not in state:
        state["preview"] = v2("POST", "", {"mode": "preview", "prompt": f"{prompt}, {STYLE}", "ai_model": "latest",
                                           "should_remesh": True, "topology": "triangle", "target_polycount": tris,
                                           "origin_at": "bottom"})["result"]
        state_file.write_text(json.dumps(state, indent=1))
    print(wear_id, "preview", state["preview"], flush=True)
    wait(state["preview"])
    if "refine" not in state:
        state["refine"] = v2("POST", "", {"mode": "refine", "preview_task_id": state["preview"], "enable_pbr": False,
                                          "texture_resolution": "2k",
                                          "texture_prompt": prompt + ", detailed stylized hand-painted game texture"})["result"]
        state_file.write_text(json.dumps(state, indent=1))
    print(wear_id, "refine", state["refine"], flush=True)
    task = wait(state["refine"])
    state["task"] = {k: task.get(k) for k in ("id", "model_urls", "thumbnail_url")}
    state_file.write_text(json.dumps(state, indent=1))
    download(task["model_urls"]["glb"], WORK / f"{wear_id}.glb")


def main():
    ids = sys.argv[1:] or [w for w in WEAR if not (WORK / f"{w}.glb").exists()]
    # All previews first (they run in parallel at Meshy), then wait and refine one by one.
    for wear_id in ids:
        make(wear_id)


if __name__ == "__main__":
    main()
