#!/usr/bin/env bash
# Builds the mod jar(s) and copies the 26.2 one straight into the real
# .minecraft/mods folder, so it can be tested through the normal Minecraft
# Launcher instead of ./gradlew runClient.
#
# This is a Stonecutter multi-version project (see settings.gradle.kts):
# 1.21.11, 26.1, and 26.2 are all configured and all build successfully
# (26.1/26.2 needed a full Mojmap rename plus a retained-render-state GUI
# port - see stonecutter.gradle.kts for the details). All three are built
# here so their jars are available under versions/<version>/build/libs/,
# but only 26.2 is deployed into .minecraft/mods (per explicit instruction -
# that's the version actually being played/tested day-to-day now, including
# its Vulkan-capable rendering backend) - the launcher doesn't yet pick a
# jar per Minecraft version, so shipping all three there would just make
# Fabric Loader refuse to load whichever one doesn't match the game version
# currently selected in the launcher.
#
# The "Velo Client Addons" jar (Velo+ modules; source in the private submodule src/addon) is
# deployed alongside it too so it can be tested; pass --no-addons to deploy
# velo-client alone (i.e. what a player without Velo+ gets). The addons are never attached to
# the public GitHub releases - see tools/release/publish_addons.py.
set -euo pipefail

DEPLOY_ADDONS=1
if [[ "${1:-}" == "--no-addons" ]]; then
	DEPLOY_ADDONS=0
fi

cd "$(dirname "${BASH_SOURCE[0]}")"

./gradlew :1.21.11:build :26.1:build :26.2:build :26.3:build -q

JAR=$(ls -t versions/26.2/build/libs/velo-client-*.jar | grep -v sources | head -1)
DEST="/home/daviseib/.minecraft/mods/$(basename "$JAR")"

# Remove any other velo-client-*.jar so an old version never lingers
# alongside the new one after a version bump.
rm -f /home/daviseib/.minecraft/mods/velo-client-*.jar
cp "$JAR" "$DEST"

echo "Deployed $(basename "$JAR") -> $DEST"

rm -f /home/daviseib/.minecraft/mods/velo-addons-*.jar
if [[ "$DEPLOY_ADDONS" == 1 ]]; then
	# The Addons (Velo+) are built from the private submodule src/addon - skipped when it isn't checked out.
	ADDONS_JAR=$(ls -t versions/26.2/build/libs/velo-addons-*.jar 2>/dev/null | grep -v sources | head -1 || true)
	if [[ -n "$ADDONS_JAR" ]]; then
		cp "$ADDONS_JAR" "/home/daviseib/.minecraft/mods/$(basename "$ADDONS_JAR")"
		echo "Deployed $(basename "$ADDONS_JAR") (Velo+ add-on) -> /home/daviseib/.minecraft/mods/"
	else
		echo "No Velo Client Addons built (src/addon submodule not checked out) - skipped"
	fi
fi
echo "Also built (not deployed): versions/1.21.11/build/libs/, versions/26.1/build/libs/, versions/26.3/build/libs/"
