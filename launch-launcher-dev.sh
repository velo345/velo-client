#!/usr/bin/env bash
# Starts the DEV launcher: it bundles the dev client jars (with the unreleased features from src/dev), keeps its own
# profiles in ~/.velo-client-dev (signed in with a copy of your account) and never updates itself.
#
# Gradle only builds it (--no-daemon: the build JVM exits afterwards) and writes the java command line; the launcher
# then runs as its own process like on a player's PC. Through "gradlew :launcher:run" it ran inside the Gradle daemon,
# which held ~2.5 GB next to the launcher the whole time.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
./gradlew :launcher:devRunArgs -PveloDev=true -q --console=plain --no-daemon
exec java @launcher/build/dev-run.args
