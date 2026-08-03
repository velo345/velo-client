#!/usr/bin/env bash
# Starts the Velo Client Launcher (the JavaFX desktop app under launcher/,
# not the Minecraft mod itself). This is what the "Velo Client Launcher"
# application-menu entry runs - see install-desktop-shortcut.sh - but you can
# also just run it directly.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec ./gradlew :launcher:run -q --console=plain
