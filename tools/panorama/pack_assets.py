"""
Packs the Blender renders into the title-screen textures (see TitleScreenTheme#drawPanorama).

  python3 pack_assets.py <render dir> <assets/velo-client/textures/gui/title>

<render dir> holds base.png + accent.png (full panorama) and frames/base_N.png + frames/glow_N.png
(the duel crop, N = 0..23). Glow layers are stored at half resolution and get alpha 1 wherever
they are lit: the game draws them with the premultiplied pipeline, so RGB is added on top while
alpha 1/255 barely darkens what is underneath (alpha 0 would be discarded by vanilla's GUI shader). Duel frames get a feathered
alpha edge so they melt into the static render; the static glow layer gets the inverse feather
in that spot so the two additive layers never double up.
"""

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

# "Drift" (panorama_space.py): the floating astronaut's crop. (The old knight duel was 2776, 372, 640 x 432.)
# 2026-11-25: 48 frames of a slow 360 degree roll (crop 704 x 704); frames are stored at half resolution (the scene is
# blurred by BLUR_RADIUS anyway), their glow at a quarter.
DUEL_X, DUEL_Y, FW, FH = 2720, 173, 704, 704
# 2026-11-26: 120 frames of a calm zero-g drift (12 s at 10 fps; the 48-frame roll looked laggy when slowed down)
FRAMES, COLUMNS = 120, 8
FRAME_DIV = 2
FEATHER = 30
NOISE_FLOOR = 4
# A blur on the scene and its glow (identically on the duel frames, so they still match the static
# render) softens the voxel texels behind the menu - and smoother PNGs compress smaller.
BLUR_RADIUS = 4.5

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
dst.mkdir(parents=True, exist_ok=True)
# --frames-only: only the animated crop's atlases (the static panorama stays as it is - same crop, same feather)
FRAMES_ONLY = "--frames-only" in sys.argv


def rgb(path, blur=False):
	image = Image.open(path).convert("RGB")
	if blur:
		image = image.filter(ImageFilter.GaussianBlur(BLUR_RADIUS))
	return np.asarray(image).astype(np.int16)


def feather_mask():
	ys = np.minimum(np.arange(FH), FH - 1 - np.arange(FH)) / FEATHER
	xs = np.minimum(np.arange(FW), FW - 1 - np.arange(FW)) / FEATHER
	m = np.clip(np.minimum(ys[:, None], xs[None, :]), 0, 1)
	return m * m * (3 - 2 * m)


def glow_rgba(colors, mask=None):
	"""Glow is soft light - stored at half resolution, the game's linear filter scales it back up."""
	c = colors.astype(np.float32)
	if mask is not None:
		c *= mask[..., None]
	h, w = c.shape[:2]
	c = c.reshape(h // 2, 2, w // 2, 2, 3).mean(axis=(1, 3))
	c = np.where(c <= NOISE_FLOOR, 0, np.round(c)).astype(np.uint8)
	alpha = (c.max(axis=2) > 0).astype(np.uint8)
	return np.dstack([c, alpha])


def save(array, name):
	Image.fromarray(array).save(dst / name, optimize=True, compress_level=9)
	(dst / (name + ".mcmeta")).write_text(json.dumps({"texture": {"blur": True}}, indent=2) + "\n")
	print(name, array.shape, round((dst / name).stat().st_size / 1e6, 2), "MB")


mask = feather_mask()

if not FRAMES_ONLY:
	base = rgb(src / "base.png", blur=True).astype(np.uint8)
	save(base, "pano_base.png")

	glow = rgb(src / "accent.png", blur=True)
	hole = np.ones(glow.shape[:2], np.float32)
	hole[DUEL_Y:DUEL_Y + FH, DUEL_X:DUEL_X + FW] = 1 - mask
	save(glow_rgba(glow, hole), "pano_glow.png")

rows = FRAMES // COLUMNS
SW, SH = FW // FRAME_DIV, FH // FRAME_DIV
duel = np.zeros((rows * SH, COLUMNS * SW, 4), np.uint8)
duel_glow = np.zeros((rows * SH // 2, COLUMNS * SW // 2, 4), np.uint8)
alpha = np.round(mask * 255).astype(np.uint8)


def shrink(array):
	return np.asarray(Image.fromarray(array).resize((array.shape[1] // FRAME_DIV, array.shape[0] // FRAME_DIV), Image.LANCZOS))


for i in range(FRAMES):
	x, y = (i % COLUMNS) * SW, (i // COLUMNS) * SH
	frame = rgb(src / "frames" / f"base_{i}.png", blur=True).astype(np.uint8)
	duel[y:y + SH, x:x + SW] = shrink(np.dstack([frame, alpha]))
	glow_full = rgb(src / "frames" / f"glow_{i}.png", blur=True).astype(np.float32)
	glow_half = np.asarray(Image.fromarray(np.clip(glow_full, 0, 255).astype(np.uint8)).resize((SW, SH), Image.LANCZOS)).astype(np.int16)
	small_mask = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).resize((SW, SH), Image.LANCZOS)).astype(np.float32) / 255
	duel_glow[y // 2:(y + SH) // 2, x // 2:(x + SW) // 2] = glow_rgba(glow_half, small_mask)
	if i == 0 and not FRAMES_ONLY:
		static = base[DUEL_Y:DUEL_Y + FH, DUEL_X:DUEL_X + FW].astype(np.int16)
		diff = np.abs(static - frame.astype(np.int16))[FEATHER:-FEATHER, FEATHER:-FEATHER]
		print("frame 0 vs static panorama: mean abs diff", round(float(diff.mean()), 2))
save(duel, "pano_duel.png")
save(duel_glow, "pano_duel_glow.png")
