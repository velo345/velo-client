#!/usr/bin/env bash
# Starts the DEV launcher: it bundles the dev client jars (with the unreleased features from src/dev), keeps its own
# profiles in ~/.velo-client-dev (signed in with a copy of your account) and never updates itself.
#
# Gradle only builds it (--no-daemon: the build JVM exits afterwards) and writes the java command line; the launcher
# then runs as its own process like on a player's PC. Through "gradlew :launcher:run" it ran inside the Gradle daemon,
# which held ~2.5 GB next to the launcher the whole time.
#
# 2026-11-10: the menu entry has no terminal, so a cold Gradle build (45-60 s) looked like "nothing happens" and a failed
# build vanished silently. Now: no rebuild when nothing changed since the last one (starts in ~2 s), a desktop
# notification while it builds, and the error (with the log path) when the build fails.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

ARGS=launcher/build/dev-run.args
LOG="${XDG_CACHE_HOME:-$HOME/.cache}/velo-dev-launcher-build.log"
notify() {
	command -v notify-send >/dev/null 2>&1 && notify-send -a "Velo Launcher (DEV)" -i "$PWD/Logo-dev.png" "$@" || true
}

needs_build=1
if [[ -f "$ARGS" && "${VELO_DEV_REBUILD:-0}" != 1 ]]; then
	# Anything that ends up in the launcher or the bundled dev jars, newer than the last command line?
	changed=$(find launcher/src src versions/*/src build.gradle* settings.gradle* gradle.properties launcher/build.gradle* \
		-newer "$ARGS" -type f -print -quit 2>/dev/null)
	[[ -z "$changed" ]] && needs_build=0
fi

if [[ $needs_build == 1 ]]; then
	notify "Building the dev launcher" "Takes about a minute after code changes - the window opens by itself."
	if ! ./gradlew :launcher:devRunArgs -PveloDev=true -q --console=plain --no-daemon >"$LOG" 2>&1; then
		notify -u critical "Dev launcher build failed" "$(grep -m1 -E 'error:|What went wrong' -A2 "$LOG" | tr '\n' ' ' | cut -c1-220) - full log: $LOG"
		exit 1
	fi
	touch "$ARGS"
fi

exec java @"$ARGS"
