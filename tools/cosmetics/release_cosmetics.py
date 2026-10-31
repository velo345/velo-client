#!/usr/bin/env python3
"""Velo Client 1.0 cosmetics release, in one go on a running server:

  1. puts every cape from server/cosmetics-seed that the server doesn't have yet (the new HD capes) on it,
  2. swaps in the redrawn art (GIF, plus the Dragon Eye's gaze atlas) for capes the server already has,
  3. releases every cosmetic that is in "testing" - except the Christmas ones (the yuletide collection and the Santa
     Hat), which wait for December.
     Hidden items (the emotes, old capes) stay hidden.

    python3 tools/cosmetics/release_cosmetics.py            shows what it would do
    python3 tools/cosmetics/release_cosmetics.py --apply    does it

Needs VELO_SERVER_URL and VELO_ADMIN_TOKEN (environment or the repository's .env). The welcome gift (one free
cosmetic per account for three months) needs no step here: it runs on its own from store.json's welcomeGift window.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from publish_wings import SEED, call, env  # noqa: E402

HOLD_BACK = {"yuletide"}   # collections released later
HOLD_BACK_IDS = {"wear_santa_hat"}   # Christmas items outside that collection


def live_items(base, token):
	request = urllib.request.Request(base.rstrip("/") + "/v1/admin/cosmetics", headers={"X-Velo-Admin-Token": token})
	with urllib.request.urlopen(request, timeout=60) as response:
		return {i["id"]: i for i in json.loads(response.read())["items"]}


def upload(base, token, rel):
	return call(base, token, "/v1/admin/cosmetics/file", (SEED / rel).read_bytes(), "application/octet-stream")["sha256"]


def update(base, token, item):
	call(base, token, "/v1/admin/cosmetics/item", json.dumps({"item": item, "clearWindow": False}).encode(), "application/json")


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--apply", action="store_true")
	args = ap.parse_args()
	base, token = env("VELO_SERVER_URL"), env("VELO_ADMIN_TOKEN")
	seed = json.loads((SEED / "seed.json").read_text())
	live = live_items(base, token)
	capes = [i for i in seed["items"] if i["type"] == "cape"]

	new = [i for i in capes if i["id"] not in live]
	print(f"1. new capes to put on the server ({len(new)}): {', '.join(i['id'] for i in new) or '-'}")
	art = [i for i in capes if i["id"] in live and all((SEED / f).exists() for f in i["files"].values())]
	print(f"2. capes getting their new art ({len(art)})")
	if args.apply:
		for item in new:
			files = {role: upload(base, token, rel) for role, rel in item["files"].items()}
			body = {k: v for k, v in item.items() if k not in ("files", "rev")}
			body["files"] = files
			update(base, token, body)
			print(f"   added {item['id']}")
		for item in art:
			files = {role: upload(base, token, rel) for role, rel in item["files"].items()}
			update(base, token, {"id": item["id"], "files": files})
			print(f"   new art for {item['id']}")

	live = live_items(base, token) if args.apply else {**live, **{i["id"]: {**i, "status": "testing"} for i in new}}
	held_back = lambda i, v: v.get("collection") in HOLD_BACK or i in HOLD_BACK_IDS
	release = sorted(i for i, v in live.items() if v.get("status") == "testing" and not held_back(i, v))
	held = sorted(i for i, v in live.items() if v.get("status") == "testing" and held_back(i, v))
	print(f"3. releasing {len(release)}: {', '.join(release)}")
	print(f"   held back for Christmas ({len(held)}): {', '.join(held)}")
	if args.apply:
		for item_id in release:
			update(base, token, {"id": item_id, "status": "released"})
		print("done")
	else:
		print("\n(dry run - add --apply to do it)")


if __name__ == "__main__":
	main()
