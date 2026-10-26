#!/usr/bin/env python3
"""Makes the emote props (the boombox, the teacup, the pumpkin...) with Meshy text-to-3D (needs MESHY_API_KEY in .env).

    python3 tools/cosmetics/emotes/meshy_props.py [id...]     # all props without a work/props/<id>.glb, or just these

Each prop is a preview remeshed by Meshy to a low triangle count (they're drawn for every emoting player, so they
have to stay cheap), then refined: the texture carries the detail. Downloads go to work/props/<id>.glb; then
import_props.py turns them into .vmesh + texture for the game.
"""
import json
import sys
import time
import urllib.error
import urllib.request

from meshy_emotes import WORK, download, env

API2 = "https://api.meshy.ai/openapi/v2/text-to-3d"
STYLE = ("stylized game asset, clean readable shapes, hand-painted texture with bright saturated colours, "
         "single object, no base, no ground plane")

# id -> (prompt, target triangles)
PROPS = {
    "boombox": ("a retro 80s boombox stereo with two big speakers, chrome handle on top, black and silver with neon pink accents", 2500),
    "teacup": ("a fancy porcelain teacup on a saucer, white with gold rim and small blue flower pattern, steaming tea inside", 1500),
    "airhorn": ("a handheld air horn: red pressurised can with a black plastic trumpet horn on top", 1500),
    "soccer_ball": ("a classic soccer ball with black pentagons and white hexagons", 1200),
    "tombstone": ("a cartoon graveyard tombstone, rounded top, cracked grey stone with moss and the letters RIP carved in", 1800),
    "pumpkin": ("a carved halloween jack-o'-lantern pumpkin with a spooky grin, glowing orange inside, green stem", 2000),
    "gift_box": ("a christmas gift box wrapped in red paper with a big shiny gold ribbon bow on top", 1500),
    "santa_hat": ("a red santa claus hat with white fluffy fur brim and a white pompom, slightly bent tip", 1500),
    "trophy": ("a shiny golden championship trophy cup with two handles on a black base", 2000),
    "snowball": ("a packed snowball, white snow with icy blue highlights, slightly lumpy round shape", 600),
}


def v2(method, path="", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API2 + path, data=data, method=method, headers={
        "Authorization": "Bearer " + env("MESHY_API_KEY"), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Meshy {method} {path}: {e.code} {e.read().decode(errors='replace')[:400]}")


def wait(task_id):
    while True:
        task = v2("GET", "/" + task_id)
        status = task.get("status")
        print(f"  {task_id}: {status} {task.get('progress', 0)}%", flush=True)
        if status == "SUCCEEDED":
            return task
        if status in ("FAILED", "CANCELED"):
            raise SystemExit(f"task {status}: {task.get('task_error')}")
        time.sleep(8)


def make(prop_id):
    prompt, tris = PROPS[prop_id]
    out = WORK / "props"
    out.mkdir(parents=True, exist_ok=True)
    state_file = out / f"{prop_id}.json"
    state = json.loads(state_file.read_text()) if state_file.exists() else {}
    if "preview" not in state:
        state["preview"] = v2("POST", "", {"mode": "preview", "prompt": f"{prompt}, {STYLE}", "ai_model": "latest",
                                           "should_remesh": True, "topology": "triangle", "target_polycount": tris,
                                           "origin_at": "bottom"})["result"]
        state_file.write_text(json.dumps(state, indent=1))
    print(prop_id, "preview", state["preview"])
    wait(state["preview"])
    if "refine" not in state:
        state["refine"] = v2("POST", "", {"mode": "refine", "preview_task_id": state["preview"], "enable_pbr": False,
                                          "texture_resolution": "2k", "texture_prompt": prompt + ", stylized hand-painted game texture"})["result"]
        state_file.write_text(json.dumps(state, indent=1))
    print(prop_id, "refine", state["refine"])
    task = wait(state["refine"])
    state["task"] = task
    state_file.write_text(json.dumps(state, indent=1))
    download(task["model_urls"]["glb"], out / f"{prop_id}.glb")
    if task.get("thumbnail_url"):
        with urllib.request.urlopen(task["thumbnail_url"], timeout=120) as r:
            (out / f"{prop_id}_thumb.png").write_bytes(r.read())


def main():
    ids = sys.argv[1:] or [p for p in PROPS if not (WORK / "props" / f"{p}.glb").exists()]
    for prop_id in ids:
        make(prop_id)


if __name__ == "__main__":
    main()
