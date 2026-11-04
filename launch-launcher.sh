#!/usr/bin/env bash
# Starts the Velo Client Launcher (the JavaFX desktop app under launcher/, not the Minecraft mod itself).
# The installed release launcher (the .deb in /opt) wins when it's there - that's what players run. Otherwise it's
# built from this checkout: Gradle only builds it (--no-daemon, so the ~2.5 GB build JVM exits) and writes the java
# command line; the launcher then runs as its own process. ("gradlew :launcher:run" kept it inside the Gradle daemon.)
set -euo pipefail
INSTALLED="/opt/velo-client-launcher/bin/Velo Client Launcher"
if [[ -x "$INSTALLED" && "${VELO_FROM_SOURCE:-}" != "1" ]]; then
	exec "$INSTALLED" "$@"
fi
cd "$(dirname "${BASH_SOURCE[0]}")"
./gradlew :launcher:devRunArgs -q --console=plain --no-daemon
exec java @launcher/build/dev-run.args
