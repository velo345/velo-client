#!/usr/bin/env bash
# Runs panorama_scene.py in Blender inside a memory-capped, low-priority scope so a runaway
# render can only kill Blender, never the desktop. Usage: ./render.sh <scene args...>
set -euo pipefail
BLENDER="${BLENDER:-$HOME/.cache/velo-blender/blender-4.5.14-linux-x64/blender}"
HERE="$(cd "$(dirname "$0")" && pwd)"
exec systemd-run --user --scope --quiet -p MemoryMax=4G -p MemorySwapMax=0 \
	nice -n 15 "$BLENDER" -b --factory-startup -t 4 --python "$HERE/panorama_scene.py" -- "$@"
