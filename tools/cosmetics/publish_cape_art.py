#!/usr/bin/env python3
"""Swaps the art of capes already on a running Velo server for the GIFs in server/cosmetics-seed/capes/
(after make_capes.py) - only the gif file changes; price, status, name and time window stay as the owner
set them. publish_wings.py would overwrite those with seed.json.

    python3 tools/cosmetics/publish_cape_art.py              every cape whose GIF is in cosmetics-seed/capes
    python3 tools/cosmetics/publish_cape_art.py glacier pearl

Needs VELO_SERVER_URL and VELO_ADMIN_TOKEN (environment or the repository's .env).
"""
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from publish_wings import SEED, call, env  # noqa: E402


def main():
    base, token = env("VELO_SERVER_URL"), env("VELO_ADMIN_TOKEN")
    wanted = set(sys.argv[1:])
    seed = json.loads((SEED / "seed.json").read_text())
    request = urllib.request.Request(base.rstrip("/") + "/v1/admin/cosmetics", headers={"X-Velo-Admin-Token": token})
    with urllib.request.urlopen(request, timeout=60) as response:
        live = {i["id"] for i in json.loads(response.read())["items"]}
    done = 0
    for item in seed["items"]:
        rel = item.get("files", {}).get("gif", "")
        name = Path(rel).stem
        if not rel.startswith("capes/") or (wanted and name not in wanted):
            continue
        if item["id"] not in live:
            print(f"skipped {item['id']}: not on the server (publish it with publish_wings.py)")
            continue
        sha = call(base, token, "/v1/admin/cosmetics/file", (SEED / rel).read_bytes(), "application/octet-stream")["sha256"]
        call(base, token, "/v1/admin/cosmetics/item",
             json.dumps({"item": {"id": item["id"], "files": {"gif": sha}}, "clearWindow": False}).encode(), "application/json")
        print(f"updated {item['id']} -> {sha[:12]}")
        done += 1
    print(f"{done} cape(s) updated")


if __name__ == "__main__":
    main()
