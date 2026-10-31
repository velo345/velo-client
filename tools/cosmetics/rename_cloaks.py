#!/usr/bin/env python3
"""Store wording: "cape" -> "cloak" in the live catalog's item and collection names and descriptions (Mojang doesn't
want capes sold; cloaks with our own model and physics are fine). Only text changes - ids, art, prices stay.

    python3 tools/cosmetics/rename_cloaks.py            shows what would change
    python3 tools/cosmetics/rename_cloaks.py --apply    changes it
"""
import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from publish_wings import call, env  # noqa: E402

WORD = re.compile(r"\b(Cape|cape|CAPE)(s|S)?\b")


def cloak(text):
	if not text:
		return text
	text = text.replace("Wavey Capes", "\0")
	text = WORD.sub(lambda m: {"Cape": "Cloak", "cape": "cloak", "CAPE": "CLOAK"}[m.group(1)] + (m.group(2) or ""), text)
	return text.replace("\0", "Wavey Capes")


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--apply", action="store_true")
	args = ap.parse_args()
	base, token = env("VELO_SERVER_URL"), env("VELO_ADMIN_TOKEN")
	request = urllib.request.Request(base.rstrip("/") + "/v1/admin/cosmetics", headers={"X-Velo-Admin-Token": token})
	with urllib.request.urlopen(request, timeout=60) as response:
		live = json.loads(response.read())
	for item in live["items"]:
		change = {k: cloak(item.get(k)) for k in ("name", "description") if cloak(item.get(k)) != item.get(k)}
		if change:
			print(f"item {item['id']}: {change}")
			if args.apply:
				call(base, token, "/v1/admin/cosmetics/item", json.dumps({"item": {"id": item["id"], **change}, "clearWindow": False}).encode(), "application/json")
	for c in live.get("collections", []):
		change = {k: cloak(c.get(k)) for k in ("name", "description") if cloak(c.get(k)) != c.get(k)}
		if change:
			print(f"collection {c['id']}: {change}")
			if args.apply:
				call(base, token, "/v1/admin/cosmetics/collection", json.dumps({"id": c["id"], **change}).encode(), "application/json")
	print("done" if args.apply else "\n(dry run - add --apply to change it)")


if __name__ == "__main__":
	main()
