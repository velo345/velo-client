#!/usr/bin/env bash
# Puts the DEV client jars (unreleased src/dev features) into the REAL launcher: its game-jar cache and every profile's
# mods folder, plus .minecraft. Run ./build-dev.sh first. ./build-and-deploy.sh puts the release jars back.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
VERSION=$(ls -t versions/26.2/build/libs/velo-client-*-dev+26.2.jar | grep -v sources | head -1 | sed -E 's/.*velo-client-([^+]+)-dev\+.*/\1/')
GAMEJARS="$HOME/.velo-client/updates/gamejars"
mkdir -p "$GAMEJARS"
for VDIR in versions/*/; do
	V=$(basename "$VDIR")
	J="${VDIR}build/libs/velo-client-${VERSION}-dev+${V}.jar"
	[[ -f "$J" ]] && cp "$J" "$GAMEJARS/$V.jar"
done
echo "version=$VERSION" > "$GAMEJARS/cache.properties"
for INST in "$HOME"/.velo-client/instances/*/; do
	[[ -f "$INST/instance.json" && -d "$INST/mods" ]] || continue
	V=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('mcVersion',''))" "$INST/instance.json" 2>/dev/null)
	J="versions/$V/build/libs/velo-client-${VERSION}-dev+${V}.jar"
	OLD=$(ls "$INST/mods" | grep -E '^velo-client.*\.jar$' | head -1 || true)
	[[ -f "$J" && -n "$OLD" ]] && cp "$J" "$INST/mods/$OLD"
done
J="versions/26.2/build/libs/velo-client-${VERSION}-dev+26.2.jar"
if [[ -d "$HOME/.minecraft/mods" && -f "$J" ]]; then
	rm -f "$HOME/.minecraft/mods"/velo-client-*.jar
	cp "$J" "$HOME/.minecraft/mods/"
fi
echo "Dev jars ($VERSION-dev) deployed into the real launcher (every version and profile) and .minecraft"
