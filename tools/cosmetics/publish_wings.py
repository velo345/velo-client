#!/usr/bin/env python3
"""Puts cosmetics from server/cosmetics-seed/seed.json on a running Velo server - no client update
and no server rebuild needed. Uploads each item's files, then creates / updates the item (status and
everything else as written in seed.json; items added by import_wings.py start as "testing").

    python3 tools/cosmetics/publish_wings.py <id> [<id> ...]     e.g. wings_neon_cyan  (or just neon_cyan)
    python3 tools/cosmetics/publish_wings.py --all

Needs VELO_SERVER_URL (e.g. https://client.asteriasmp.net) and VELO_ADMIN_TOKEN, from the environment
or the .env file in the repository root (never commit that file).

Status, price and time window are better changed afterwards in the launcher's Owner panel - publishing
an item again overwrites them with what seed.json says.
"""
import argparse
import json
import os
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = ROOT / "server/cosmetics-seed"
CLIENT = ROOT / "src/client/resources/assets/velo-client"


def env(name):
    if os.environ.get(name):
        return os.environ[name]
    dotenv = ROOT / ".env"
    if dotenv.exists():
        for line in dotenv.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == name:
                return value.strip().strip('"')
    raise SystemExit(f"{name} isn't set (environment or .env)")


def seed_path(rel):
    """seed.json paths: client/... point into the mod's resources, anything else is next to seed.json."""
    if rel.startswith("client/wings/textures/"):
        return CLIENT / "textures/cosmetics/wings" / rel.split("/")[-1]
    if rel.startswith("client/wings/"):
        return CLIENT / "cosmetics/wings" / rel.split("/")[-1]
    if rel.startswith("client/capes/"):
        return CLIENT / "textures/store/cape" / rel.split("/")[-1]
    return SEED / rel


def call(base, token, path, data, content_type):
    request = urllib.request.Request(base.rstrip("/") + path, data=data, method="POST",
                                     headers={"Content-Type": content_type, "X-Velo-Admin-Token": token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{path}: HTTP {e.code} {e.read().decode(errors='replace')}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ids", nargs="*")
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    seed = json.loads((SEED / "seed.json").read_text())
    wanted = {i if i.startswith(("wings_", "cape_")) else "wings_" + i for i in args.ids}
    items = [i for i in seed["items"] if args.all or i["id"] in wanted]
    if not items:
        raise SystemExit("Nothing to publish - give item ids from seed.json or --all")
    base, token = env("VELO_SERVER_URL"), env("VELO_ADMIN_TOKEN")

    for collection in seed["collections"]:
        if any(i.get("collection") == collection["id"] for i in items):
            call(base, token, "/v1/admin/cosmetics/collection", json.dumps(collection).encode(), "application/json")
    for item in items:
        files = {}
        for role, rel in item["files"].items():
            files[role] = call(base, token, "/v1/admin/cosmetics/file", seed_path(rel).read_bytes(),
                               "application/octet-stream")["sha256"]
        body = dict(item, files=files)
        call(base, token, "/v1/admin/cosmetics/item", json.dumps({"item": body, "clearWindow": False}).encode(), "application/json")
        print(f"published {item['id']} ({item.get('status', 'testing')})")


if __name__ == "__main__":
    main()
