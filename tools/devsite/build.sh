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
# the shown config leaves out the mod log / detection options (they stay in the plugin's own file)
python3 - <<'PY2'
import re, pathlib
text = pathlib.Path("bridge/src/main/resources/config.yml").read_text(encoding="utf-8")
text = re.sub(r"  # a line when someone joins with a mod from your forbid lists.*?\n  on-detection: false\n", "", text, flags=re.S)
text = re.sub(r"\n# Which mods Velo players join with.*?\nmod-log:\n  enabled: true\n", "\n", text, flags=re.S)
pathlib.Path("dev-site/config.yml").write_text(text, encoding="utf-8")
PY2
# the owner panel's 3D NPC preview: the website's try-on viewer
mkdir -p dev-site/vendor
cp velo-site/assets/skin3d.js dev-site/skin3d.js
cp velo-site/assets/vendor/three.module.min.js dev-site/vendor/three.module.min.js
./gradlew :bridge:build -q
mkdir -p dev-site/downloads
cp bridge/build/libs/VeloBridge-*.jar dev-site/downloads/
ls dev-site/downloads
