#!/usr/bin/env bash
# Builds the mod jar(s) and copies the 1.21.11 one straight into the real
# .minecraft/mods folder, so it can be tested through the normal Minecraft
# Launcher instead of ./gradlew runClient.
#
# This is a Stonecutter multi-version project (see settings.gradle.kts):
# 1.21.11, 26.1, and 26.2 are all configured and all build successfully
# (26.1/26.2 needed a full Mojmap rename plus a retained-render-state GUI
# port - see stonecutter.gradle.kts for the details). All three are built
# here so their jars are available under versions/<version>/build/libs/,
# but only 1.21.11 is deployed into .minecraft/mods - the launcher doesn't
# yet pick a jar per Minecraft version, so shipping all three there would
# just make Fabric Loader refuse to load whichever one doesn't match the
# game version currently selected in the launcher.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

./gradlew :1.21.11:build :26.1:build :26.2:build -q

JAR=$(ls -t versions/1.21.11/build/libs/velo-client-*.jar | grep -v sources | head -1)
DEST="/home/daviseib/.minecraft/mods/$(basename "$JAR")"

# Remove any other velo-client-*.jar so an old version never lingers
# alongside the new one after a version bump.
rm -f /home/daviseib/.minecraft/mods/velo-client-*.jar
cp "$JAR" "$DEST"

echo "Deployed $(basename "$JAR") -> $DEST"
echo "Also built (not deployed): versions/26.1/build/libs/, versions/26.2/build/libs/"
