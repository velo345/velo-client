#!/usr/bin/env python3
"""Publishes the Velo Client Addons (Velo+) - to the PRIVATE repo, never the public one.

    python3 tools/release/publish_addons.py            # the jars of the current mod.version
    python3 tools/release/publish_addons.py --no-sync  # don't tell the server to fetch them right away

Needs the Addons built (`./gradlew :1.21.11:build :26.1:build :26.2:build :26.3:build`, with the private
submodule src/addon checked out) and `gh` logged in with access to velo345/velo-client-plus. It creates (or
updates) the release `v<version>` there with every `velo-addons-<version>+<minecraft>.jar`, then asks the
Velo server to fetch it (`POST /v1/admin/addons/sync`; needs VELO_SERVER_URL and VELO_ADMIN_TOKEN from the
environment or .env) - otherwise the server picks it up within the hour. Velo+ members then get it through the
launcher; nobody needs access to the private repo.
"""
import argparse
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPO = os.environ.get("VELO_PLUS_GITHUB_REPO", "velo345/velo-client-plus")


def env(name):
    if os.environ.get(name):
        return os.environ[name]
    dotenv = ROOT / ".env"
    if dotenv.exists():
        for line in dotenv.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == name:
                return value.strip().strip('"')
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-sync", action="store_true")
    args = parser.parse_args()
    version = re.search(r'^mod\.version\s*=\s*"([^"]+)"', (ROOT / "stonecutter.properties.toml").read_text(), re.M).group(1)
    jars = sorted(p for p in (ROOT / "versions").glob(f"*/build/libs/velo-addons-{version}+*.jar") if "sources" not in p.name)
    if not jars:
        sys.exit(f"No velo-addons-{version}+*.jar built - build all versions first (with the src/addon submodule checked out)")
    tag = f"v{version}"
    names = [p.name for p in jars]
    print("Publishing", ", ".join(names), "to", REPO, tag)
    exists = subprocess.run(["gh", "release", "view", tag, "-R", REPO], capture_output=True).returncode == 0
    if exists:
        subprocess.check_call(["gh", "release", "upload", tag, "-R", REPO, "--clobber", *map(str, jars)])
    else:
        subprocess.check_call(["gh", "release", "create", tag, "-R", REPO, "--title", f"Velo Client Addons {version}",
                               "--notes", f"Velo Client Addons {version} - for Velo+ (served by the Velo server, never public).", *map(str, jars)])
    base, token = env("VELO_SERVER_URL"), env("VELO_ADMIN_TOKEN")
    if args.no_sync or not base or not token:
        print("Done (the server fetches it within the hour).")
        return
    request = urllib.request.Request(base.rstrip("/") + "/v1/admin/addons/sync", data=b"{}", method="POST",
                                     headers={"Content-Type": "application/json", "X-Velo-Admin-Token": token})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            print("Server:", response.read().decode())
    except urllib.error.HTTPError as e:
        print("The server couldn't sync yet:", e.code, e.read().decode(errors="replace"))


if __name__ == "__main__":
    main()
