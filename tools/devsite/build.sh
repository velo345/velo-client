#!/usr/bin/env bash
# Builds what dev.velo-client.com needs next to its static files: the module list (public client modules only -
# add-ons and unreleased dev modules are never listed), the real config.yml, and the plugin jar to download.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python3 - <<'PY'
import json, re, pathlib
mods = {}
for f in pathlib.Path("src/client/java/net/veloclient/velo/client/modules").rglob("*.java"):
    text = f.read_text(encoding="utf-8")
    for m in re.finditer(r'super\("([a-z0-9-]+)",\s*"([^"]+)",\s*"((?:[^"\\]|\\.)*)"', text):
        cat = re.search(r'ModuleCategory\.([A-Z_]+)', text[m.end():m.end() + 400])
        mods.setdefault(m.group(1), {"id": m.group(1), "name": m.group(2), "description": m.group(3).replace('\\"', '"'),
                                    "category": cat.group(1).lower() if cat else "other"})
out = sorted(mods.values(), key=lambda x: x["name"].lower())
pathlib.Path("dev-site/modules.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
print(f"{len(out)} modules")
PY
cp bridge/src/main/resources/config.yml dev-site/config.yml
./gradlew :bridge:build -q
mkdir -p dev-site/downloads
cp bridge/build/libs/VeloBridge-*.jar dev-site/downloads/
ls dev-site/downloads
