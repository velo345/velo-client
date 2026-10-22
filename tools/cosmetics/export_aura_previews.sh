#!/usr/bin/env bash
# Renders every aura with the launcher's own renderer (./gradlew :launcher:auraPreviews) and packs the frames into
# looping animated WebP previews, copied to the website (velo-site/assets/auras) and the owner panel.
# Run again after adding or changing an aura in AuraEffects.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FRAMES="$(mktemp -d)"
trap 'rm -rf "$FRAMES"' EXIT
"$ROOT/gradlew" -p "$ROOT" :launcher:auraPreviews -PauraOut="$FRAMES" -q
for dest in "$ROOT/velo-site/assets/auras" "$ROOT/panel/src/main/resources/web/auras"; do mkdir -p "$dest"; done
for dir in "$FRAMES"/*/; do
	id="$(basename "$dir")"
	python3 -I - "$dir" "$ROOT/velo-site/assets/auras/$id.webp" <<'PY'
import sys, glob
from PIL import Image
frames = [Image.open(p).convert("RGBA") for p in sorted(glob.glob(sys.argv[1] + "/*.png"))]
frames[0].save(sys.argv[2], save_all=True, append_images=frames[1:], duration=42, loop=0, quality=60, method=6, lossless=False)
PY
	cp "$ROOT/velo-site/assets/auras/$id.webp" "$ROOT/panel/src/main/resources/web/auras/$id.webp"
	echo "aura $id -> $(du -h "$ROOT/velo-site/assets/auras/$id.webp" | cut -f1)"
done
