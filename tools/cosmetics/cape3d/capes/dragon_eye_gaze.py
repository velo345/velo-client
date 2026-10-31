"""Renders the Dragon Eye cape's gaze atlas: the whole cape texture region (22 x 17 cape pixels) with the iris turned to
7 x 3 directions, eye wide open. The game blends the four nearest cells to look at the player standing behind the
wearer (see GazeCapes.java).

    python3 tools/cosmetics/cape3d/capes/dragon_eye_gaze.py  ->  server/cosmetics-seed/capes/dragon_eye_gaze.png
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import render_cape3d as R  # noqa: E402

COLS, ROWS = 7, 3

r = R.Renderer("dragon_eye")
r.set("uGazeOn", 1.0)
cw, ch = 22 * R.PX, 17 * R.PX
atlas = Image.new("RGB", (cw * COLS, ch * ROWS))
for row in range(ROWS):
	for col in range(COLS):
		gx = -1 + 2 * col / (COLS - 1)
		gy = 1 - 2 * row / (ROWS - 1)
		r.set("uGaze", (gx, gy))
		tex, _ = R.frame_texture(r, 0.3)
		cell = R.to_image(tex).crop((0, 0, cw, ch))
		atlas.paste(cell, (col * cw, row * ch))
		print(f"\rcell {row * COLS + col + 1}/{COLS * ROWS}", end="", file=sys.stderr)
out = R.ROOT / "server/cosmetics-seed/capes/dragon_eye_gaze.png"
atlas.save(out, optimize=True)
print(f"\nwrote {out.relative_to(R.ROOT)} {atlas.size} ({out.stat().st_size // 1024} KB)")
