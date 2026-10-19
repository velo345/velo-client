"""
Builds and renders the Velo title-screen panorama in Blender (Cycles, path traced).

Everything is procedural - pixel textures, voxel terrain, the two fighters - so nothing
from the Minecraft jar is shipped. Run through render_panorama.sh, which caps memory.

  blender -b --factory-startup --python panorama_scene.py -- \
      --out DIR --mode base|accent --width 6144 --samples 256 \
      [--frame I --frames N --crop x0,y0,x1,y1]

* base   - the scene lit by sun + sky + warm lights, theme-colored emitters switched off.
* accent - ONLY the theme-colored emitters (crystals, portal, lanterns, windows), drawn
           white on black. The game adds this layer on top tinted with the theme accent.
* --frame renders animation frame I of N (the duel loop); --crop limits it to a pixel
  rectangle of the full panorama (Cycles border render), so frames stay small.
"""

import math
import sys
import argparse

import bpy
import numpy as np
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ap = argparse.ArgumentParser()
ap.add_argument("--out", required=True)
ap.add_argument("--mode", default="base", choices=["base", "accent"])
ap.add_argument("--width", type=int, default=6144)
ap.add_argument("--samples", type=int, default=256)
ap.add_argument("--frame", type=int, default=0)
ap.add_argument("--frames", type=int, default=24)
ap.add_argument("--crop", default="")
ap.add_argument("--terrain-radius", type=int, default=150)
ap.add_argument("--sky-only", action="store_true")
ARGS = ap.parse_args(argv)

ACCENT = ARGS.mode == "accent"
WIDTH = ARGS.width
# Square pixels on a 360-degree central-cylindrical strip: height in tan() units per pixel
# equals 2*pi/WIDTH. 1152/6144 of the width -> about +-30 degrees around the horizon.
HEIGHT = WIDTH * 1152 // 6144
V_SPAN = HEIGHT * (2 * math.pi / WIDTH)
V_MIN = -V_SPAN * 0.58
V_MAX = V_SPAN + V_MIN

SUN_AZ = math.radians(-55)  # from +Y, clockwise toward +X (= image right)
SUN_EL = math.radians(4.2)
CAM = Vector((0.5, 0.5, 2.65))

rng = np.random.default_rng(7)

# --------------------------------------------------------------------------------------
# Pixel textures (16x16, painted with numpy)
# --------------------------------------------------------------------------------------

T = 16
TILES = {}
ROUGH = {}


def jitter(base, amount, seed, shape=(T, T)):
	r = np.random.default_rng(seed).random(shape)
	return np.clip(np.array(base)[None, None, :] * (1 + amount * (r[..., None] * 2 - 1)), 0, 1)


def tile(name, rgb, alpha=None, rough=0.8):
	a = np.ones((T, T)) if alpha is None else alpha
	TILES[name] = np.dstack([rgb, a])
	ROUGH[name] = rough if np.ndim(rough) else np.full((T, T), rough)


def blotches(img, color, count, seed, size=(1, 3)):
	g = np.random.default_rng(seed)
	for _ in range(count):
		w, h = g.integers(size[0], size[1] + 1, 2)
		x, y = g.integers(0, T, 2)
		for dx in range(w):
			for dy in range(h):
				img[(y + dy) % T, (x + dx) % T] = np.array(color) * (0.9 + 0.2 * g.random())
	return img


def paint_textures():
	grass = jitter((0.33, 0.56, 0.20), 0.16, 1)
	blotches(grass, (0.27, 0.47, 0.16), 14, 2)
	blotches(grass, (0.42, 0.66, 0.26), 10, 3, (1, 1))
	tile("grass_top", grass, rough=0.9)

	dirt = jitter((0.45, 0.31, 0.20), 0.18, 4)
	blotches(dirt, (0.35, 0.24, 0.15), 14, 5)
	blotches(dirt, (0.58, 0.44, 0.32), 6, 6, (1, 1))
	tile("dirt", dirt, rough=0.95)

	side = dirt.copy()
	g = np.random.default_rng(8)
	for x in range(T):
		depth = 3 + g.integers(0, 3)
		side[:depth, x] = grass[:depth, x]
	tile("grass_side", side, rough=0.92)

	path = jitter((0.55, 0.45, 0.28), 0.14, 9)
	blotches(path, (0.47, 0.37, 0.22), 12, 10)
	tile("path_top", path, rough=0.95)

	stone = jitter((0.49, 0.49, 0.50), 0.12, 11)
	blotches(stone, (0.40, 0.40, 0.41), 16, 12, (1, 4))
	blotches(stone, (0.58, 0.58, 0.59), 8, 13, (1, 2))
	tile("stone", stone, rough=0.75)

	cob = jitter((0.50, 0.50, 0.51), 0.15, 14)
	g = np.random.default_rng(15)
	pts = g.random((11, 2)) * T
	yy, xx = np.mgrid[0:T, 0:T]
	d = np.full((T, T), 99.0)
	d2 = np.full((T, T), 99.0)
	for p in pts:
		for ox in (-T, 0, T):
			for oy in (-T, 0, T):
				dd = np.hypot(xx - p[0] - ox, yy - p[1] - oy)
				d2 = np.where(dd < d, d, np.minimum(d2, dd))
				d = np.minimum(d, dd)
	edge = (d2 - d) < 1.2
	cob[edge] *= 0.55
	tile("cobble", cob, rough=0.8)

	def bricks(base, seed, moss=0, cracks=0):
		b = jitter(base, 0.10, seed)
		for y in range(T):
			for x in range(T):
				row = y // 8
				off = 4 if row % 2 else 0
				if y % 8 in (0,) or (x + off) % 8 == 0:
					b[y, x] = np.array(base) * 0.55
				elif y % 8 == 1 or (x + off) % 8 == 1:
					b[y, x] = np.minimum(1, np.array(base) * 1.12)
		if moss:
			g2 = np.random.default_rng(seed + 1)
			for _ in range(moss):
				x, y = g2.integers(0, T, 2)
				for k in range(g2.integers(2, 6)):
					b[(y + k // 2) % T, (x + k) % T] = (0.30 + 0.06 * g2.random(), 0.42, 0.20)
		if cracks:
			g2 = np.random.default_rng(seed + 2)
			x, y = 5, 2
			for _ in range(cracks):
				b[y % T, x % T] *= 0.35
				x += g2.integers(-1, 2)
				y += 1
		return b

	tile("stone_bricks", bricks((0.52, 0.52, 0.53), 16), rough=0.55)
	tile("mossy_bricks", bricks((0.50, 0.51, 0.49), 18, moss=7), rough=0.7)
	tile("cracked_bricks", bricks((0.50, 0.50, 0.51), 20, cracks=13), rough=0.6)
	ch = bricks((0.55, 0.55, 0.56), 22)
	ch[3:13, 3:13] = jitter((0.50, 0.50, 0.51), 0.08, 23, (10, 10))
	ch[5:11, 5:11] = jitter((0.60, 0.60, 0.61), 0.06, 24, (6, 6))
	tile("chiseled", ch, rough=0.5)

	def log_side(base, dark, seed):
		l = jitter(base, 0.10, seed)
		g2 = np.random.default_rng(seed)
		for x in range(T):
			if g2.random() < 0.35:
				l[:, x] = np.array(dark) * (0.9 + 0.2 * g2.random())
		return l

	tile("oak_log", log_side((0.42, 0.33, 0.20), (0.30, 0.23, 0.13), 25), rough=0.9)
	top = jitter((0.70, 0.56, 0.35), 0.06, 26)
	rr = np.hypot(xx - 7.5, yy - 7.5)
	top[(rr.astype(int) % 3) == 0] *= 0.8
	top[rr > 6.6] = (0.36, 0.28, 0.17)
	tile("oak_log_top", top, rough=0.85)
	tile("spruce_log", log_side((0.30, 0.21, 0.12), (0.21, 0.14, 0.08), 27), rough=0.9)
	birch = jitter((0.86, 0.85, 0.80), 0.05, 28)
	g = np.random.default_rng(29)
	for _ in range(9):
		x, y = g.integers(0, T, 2)
		birch[y, x:x + g.integers(1, 4)] = (0.17, 0.17, 0.15)
	tile("birch_log", birch, rough=0.85)

	planks = jitter((0.66, 0.52, 0.32), 0.07, 30)
	for y in range(T):
		if y % 4 == 3:
			planks[y] = (0.42, 0.32, 0.19)
	g = np.random.default_rng(31)
	for row in range(4):
		x = g.integers(0, T)
		planks[row * 4:row * 4 + 3, x] = (0.50, 0.38, 0.23)
	tile("oak_planks", planks, rough=0.7)

	def leaves(base, seed, holes=0.18):
		l = jitter(base, 0.25, seed)
		g2 = np.random.default_rng(seed)
		a = (g2.random((T, T)) > holes).astype(float)
		return l, a

	l, a = leaves((0.22, 0.45, 0.13), 32)
	tile("oak_leaves", l, a, rough=0.6)
	l, a = leaves((0.36, 0.52, 0.20), 33)
	tile("birch_leaves", l, a, rough=0.6)
	l, a = leaves((0.16, 0.30, 0.17), 34, 0.12)
	tile("spruce_leaves", l, a, rough=0.6)
	l, a = leaves((0.93, 0.62, 0.74), 35, 0.15)
	tile("cherry_leaves", l, a, rough=0.6)

	tile("sand", blotches(jitter((0.86, 0.80, 0.60), 0.07, 36), (0.78, 0.71, 0.52), 10, 37, (1, 1)), rough=0.9)
	tile("gravel", blotches(jitter((0.52, 0.49, 0.47), 0.2, 38), (0.36, 0.34, 0.33), 20, 39), rough=0.9)
	snow = jitter((0.95, 0.97, 1.0), 0.03, 40)
	tile("snow", snow, rough=0.6)
	ss = stone.copy()
	ss[:4] = snow[:4]
	ss[4, ::2] = snow[4, ::2]
	tile("snow_side", ss, rough=0.7)

	obs = jitter((0.07, 0.05, 0.11), 0.35, 41)
	blotches(obs, (0.20, 0.13, 0.30), 9, 42, (1, 2))
	tile("obsidian", obs, rough=0.12)

	cry = jitter((0.82, 0.86, 1.0), 0.12, 43)
	for k in range(T):
		cry[k, k] = cry[k, (T - 1 - k)] = (1, 1, 1)
	cry[0, :] = cry[-1, :] = cry[:, 0] = cry[:, -1] = (0.6, 0.65, 0.8)
	tile("crystal", cry, rough=0.08)

	lan = np.zeros((T, T, 3)) + (0.17, 0.17, 0.19)
	lan[3:13, 3:13] = (1, 0.95, 0.9)
	lan[6:10, :] = (0.17, 0.17, 0.19)
	tile("lantern", lan, rough=0.3)

	fire = jitter((1.0, 0.55, 0.15), 0.3, 44)
	fire[12:] = (0.36, 0.26, 0.15)
	tile("campfire", fire, rough=0.8)

	glass = np.zeros((T, T, 3)) + (0.85, 0.92, 0.95)
	ga = np.full((T, T), 0.12)
	ga[0, :] = ga[-1, :] = ga[:, 0] = ga[:, -1] = 1
	glass[0, :] = glass[-1, :] = glass[:, 0] = glass[:, -1] = (0.75, 0.82, 0.86)
	tile("glass", glass, ga, rough=0.05)

	# cross-plant sprites
	tg = np.zeros((T, T, 3))
	ta = np.zeros((T, T))
	g = np.random.default_rng(45)
	for blade in range(9):
		x = g.integers(1, T - 1)
		h = g.integers(6, 15)
		lean = g.choice([-1, 0, 1])
		for k in range(h):
			yy_ = T - 1 - k
			xk = int(np.clip(x + lean * (k // 5), 0, T - 1))
			tg[yy_, xk] = np.array((0.30, 0.55, 0.18)) * (0.75 + 0.5 * k / h)
			ta[yy_, xk] = 1
	tile("tallgrass", tg, ta, rough=0.7)

	def flower(petal, seed):
		f = np.zeros((T, T, 3))
		fa = np.zeros((T, T))
		f[8:, 7] = (0.25, 0.48, 0.15)
		fa[8:, 7] = 1
		f[10, 8] = f[11, 6] = (0.28, 0.52, 0.17)
		fa[10, 8] = fa[11, 6] = 1
		for (dx, dy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1)):
			f[5 + dy, 7 + dx] = np.array(petal) * (0.85 if abs(dx) + abs(dy) == 2 else 1)
			fa[5 + dy, 7 + dx] = 1
		f[5, 7] = (0.95, 0.85, 0.2) if petal[0] > 0.8 and petal[1] < 0.5 else (0.6, 0.45, 0.1)
		return f, fa

	tile("poppy", *flower((0.85, 0.12, 0.10), 46), rough=0.6)
	tile("dandelion", *flower((0.98, 0.85, 0.15), 47), rough=0.6)
	tile("cornflower", *flower((0.30, 0.45, 0.95), 48), rough=0.6)

	cloud = np.ones((T, T, 3))
	tile("cloud", cloud, rough=1.0)
	tile("white", np.ones((T, T, 3)) * 0.9, rough=0.5)


paint_textures()
TILE_NAMES = list(TILES)
TILE_INDEX = {n: i for i, n in enumerate(TILE_NAMES)}
PER_ROW = 8
ATLAS_PX = PER_ROW * T
assert len(TILE_NAMES) <= PER_ROW * PER_ROW


def build_atlas():
	img = np.zeros((ATLAS_PX, ATLAS_PX, 4))
	rough = np.zeros((ATLAS_PX, ATLAS_PX, 4))
	for name, i in TILE_INDEX.items():
		cx, cy = (i % PER_ROW) * T, (i // PER_ROW) * T
		img[cy:cy + T, cx:cx + T] = TILES[name]
		rv = ROUGH[name]
		rough[cy:cy + T, cx:cx + T, :3] = rv[..., None]
		rough[cy:cy + T, cx:cx + T, 3] = 1
	# Blender image pixels start bottom-left
	img = img[::-1]
	rough = rough[::-1]
	# textures were painted in sRGB-ish values; store linear for a float image
	lin = img.copy()
	lin[..., :3] = np.where(img[..., :3] <= 0.04045, img[..., :3] / 12.92, ((img[..., :3] + 0.055) / 1.055) ** 2.4)
	a = bpy.data.images.new("atlas", ATLAS_PX, ATLAS_PX, alpha=True, float_buffer=True)
	a.pixels.foreach_set(lin.astype(np.float32).ravel())
	r = bpy.data.images.new("rough", ATLAS_PX, ATLAS_PX, alpha=True, float_buffer=True)
	r.colorspace_settings.name = "Non-Color"
	r.pixels.foreach_set(rough.astype(np.float32).ravel())
	return a, r


def tile_uv(name):
	i = TILE_INDEX[name]
	u0 = (i % PER_ROW) / PER_ROW
	v1 = 1 - (i // PER_ROW) / PER_ROW
	e = 0.0004
	return u0 + e, v1 - 1 / PER_ROW + e, u0 + 1 / PER_ROW - e, v1 - e


# --------------------------------------------------------------------------------------
# Materials
# --------------------------------------------------------------------------------------

def new_mat(name):
	m = bpy.data.materials.new(name)
	m.use_nodes = True
	nt = m.node_tree
	for n in list(nt.nodes):
		nt.nodes.remove(n)
	out = nt.nodes.new("ShaderNodeOutputMaterial")
	return m, nt, out


def atlas_nodes(nt, atlas, rough):
	uv = nt.nodes.new("ShaderNodeUVMap")
	tex = nt.nodes.new("ShaderNodeTexImage")
	tex.image = atlas
	tex.interpolation = "Closest"
	rt = nt.nodes.new("ShaderNodeTexImage")
	rt.image = rough
	rt.interpolation = "Closest"
	nt.links.new(uv.outputs["UV"], tex.inputs["Vector"])
	nt.links.new(uv.outputs["UV"], rt.inputs["Vector"])
	return tex, rt


def pixel_bump(nt, tex, strength=0.35):
	"""Height from texel brightness - gives every pixel a little relief, like a PBR pack."""
	bw = nt.nodes.new("ShaderNodeRGBToBW")
	nt.links.new(tex.outputs["Color"], bw.inputs["Color"])
	bump = nt.nodes.new("ShaderNodeBump")
	bump.inputs["Strength"].default_value = strength
	bump.inputs["Distance"].default_value = 0.02
	nt.links.new(bw.outputs["Val"], bump.inputs["Height"])
	return bump


def make_materials(atlas, rough):
	mats = []

	m, nt, out = new_mat("solid")
	tex, rt = atlas_nodes(nt, atlas, rough)
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	nt.links.new(tex.outputs["Color"], b.inputs["Base Color"])
	nt.links.new(rt.outputs["Color"], b.inputs["Roughness"])
	nt.links.new(pixel_bump(nt, tex).outputs["Normal"], b.inputs["Normal"])
	nt.links.new(b.outputs[0], out.inputs[0])
	mats.append(m)

	m, nt, out = new_mat("cutout")
	tex, rt = atlas_nodes(nt, atlas, rough)
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	nt.links.new(tex.outputs["Color"], b.inputs["Base Color"])
	nt.links.new(rt.outputs["Color"], b.inputs["Roughness"])
	b.inputs["Subsurface Weight"].default_value = 0.0
	tr = nt.nodes.new("ShaderNodeBsdfTranslucent")
	nt.links.new(tex.outputs["Color"], tr.inputs["Color"])
	mix = nt.nodes.new("ShaderNodeMixShader")
	mix.inputs["Fac"].default_value = 0.25
	nt.links.new(b.outputs[0], mix.inputs[1])
	nt.links.new(tr.outputs[0], mix.inputs[2])
	gt = nt.nodes.new("ShaderNodeMath")
	gt.operation = "GREATER_THAN"
	gt.inputs[1].default_value = 0.5
	nt.links.new(tex.outputs["Alpha"], gt.inputs[0])
	tp = nt.nodes.new("ShaderNodeBsdfTransparent")
	mix2 = nt.nodes.new("ShaderNodeMixShader")
	nt.links.new(gt.outputs[0], mix2.inputs["Fac"])
	nt.links.new(tp.outputs[0], mix2.inputs[1])
	nt.links.new(mix.outputs[0], mix2.inputs[2])
	nt.links.new(mix2.outputs[0], out.inputs[0])
	mats.append(m)

	# Theme-colored emitters: polished dark crystal in the base pass, pure white glow in
	# the accent pass (the game tints that layer with the theme accent).
	m, nt, out = new_mat("accent")
	tex, rt = atlas_nodes(nt, atlas, rough)
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	tint = nt.nodes.new("ShaderNodeMixRGB")
	tint.blend_type = "MULTIPLY"
	tint.inputs["Fac"].default_value = 1.0
	tint.inputs["Color2"].default_value = (0.55, 0.58, 0.66, 1)
	nt.links.new(tex.outputs["Color"], tint.inputs["Color1"])
	nt.links.new(tint.outputs["Color"], b.inputs["Base Color"])
	b.inputs["Roughness"].default_value = 0.08
	b.inputs["Coat Weight"].default_value = 0.8
	b.inputs["Subsurface Weight"].default_value = 0.3
	if ACCENT:
		bw = nt.nodes.new("ShaderNodeRGBToBW")
		nt.links.new(tex.outputs["Color"], bw.inputs["Color"])
		nt.links.new(bw.outputs["Val"], b.inputs["Emission Color"])
		b.inputs["Emission Strength"].default_value = 9.0
	nt.links.new(b.outputs[0], out.inputs[0])
	mats.append(m)

	# Warm emitters (campfire) - base pass only.
	m, nt, out = new_mat("warm")
	tex, rt = atlas_nodes(nt, atlas, rough)
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	nt.links.new(tex.outputs["Color"], b.inputs["Base Color"])
	if not ACCENT:
		nt.links.new(tex.outputs["Color"], b.inputs["Emission Color"])
		b.inputs["Emission Strength"].default_value = 6.0
	nt.links.new(b.outputs[0], out.inputs[0])
	mats.append(m)

	m, nt, out = new_mat("glass")
	tex, rt = atlas_nodes(nt, atlas, rough)
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	nt.links.new(tex.outputs["Color"], b.inputs["Base Color"])
	b.inputs["Roughness"].default_value = 0.03
	nt.links.new(tex.outputs["Alpha"], b.inputs["Alpha"])
	nt.links.new(b.outputs[0], out.inputs[0])
	mats.append(m)
	return mats


def water_material():
	m, nt, out = new_mat("water")
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	b.inputs["Base Color"].default_value = (0.02, 0.09, 0.13, 1)
	b.inputs["Roughness"].default_value = 0.035
	b.inputs["IOR"].default_value = 1.33
	b.inputs["Transmission Weight"].default_value = 0.35
	coord = nt.nodes.new("ShaderNodeTexCoord")
	mapping = nt.nodes.new("ShaderNodeMapping")
	mapping.inputs["Scale"].default_value = (1.0, 2.6, 1.0)
	noise = nt.nodes.new("ShaderNodeTexNoise")
	noise.inputs["Scale"].default_value = 1.6
	noise.inputs["Detail"].default_value = 6
	nt.links.new(coord.outputs["Object"], mapping.inputs["Vector"])
	nt.links.new(mapping.outputs["Vector"], noise.inputs["Vector"])
	bump = nt.nodes.new("ShaderNodeBump")
	bump.inputs["Strength"].default_value = 0.06
	bump.inputs["Distance"].default_value = 0.1
	nt.links.new(noise.outputs["Fac"], bump.inputs["Height"])
	nt.links.new(bump.outputs["Normal"], b.inputs["Normal"])
	nt.links.new(b.outputs[0], out.inputs[0])
	return m


def portal_material():
	"""Additive veil: invisible in the base pass, a swirling glow in the accent pass."""
	m, nt, out = new_mat("portal")
	tp = nt.nodes.new("ShaderNodeBsdfTransparent")
	if not ACCENT:
		nt.links.new(tp.outputs[0], out.inputs[0])
		return m
	coord = nt.nodes.new("ShaderNodeTexCoord")
	wave = nt.nodes.new("ShaderNodeTexNoise")
	wave.inputs["Scale"].default_value = 2.2
	wave.inputs["Detail"].default_value = 8
	wave.inputs["Distortion"].default_value = 3.0
	nt.links.new(coord.outputs["Object"], wave.inputs["Vector"])
	ramp = nt.nodes.new("ShaderNodeValToRGB")
	ramp.color_ramp.elements[0].position = 0.3
	ramp.color_ramp.elements[0].color = (0.25, 0.25, 0.25, 1)
	ramp.color_ramp.elements[1].position = 0.75
	ramp.color_ramp.elements[1].color = (1, 1, 1, 1)
	nt.links.new(wave.outputs["Fac"], ramp.inputs["Fac"])
	em = nt.nodes.new("ShaderNodeEmission")
	em.inputs["Strength"].default_value = 3.5
	nt.links.new(ramp.outputs["Color"], em.inputs["Color"])
	add = nt.nodes.new("ShaderNodeAddShader")
	nt.links.new(tp.outputs[0], add.inputs[0])
	nt.links.new(em.outputs[0], add.inputs[1])
	nt.links.new(add.outputs[0], out.inputs[0])
	return m


def voxel_material():
	"""Fighters: per-voxel color + (metal, roughness, emission) from color attributes."""
	m, nt, out = new_mat("voxel")
	col = nt.nodes.new("ShaderNodeAttribute")
	col.attribute_name = "col"
	pbr = nt.nodes.new("ShaderNodeAttribute")
	pbr.attribute_name = "pbr"
	sep = nt.nodes.new("ShaderNodeSeparateColor")
	nt.links.new(pbr.outputs["Color"], sep.inputs["Color"])
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	nt.links.new(col.outputs["Color"], b.inputs["Base Color"])
	nt.links.new(sep.outputs["Red"], b.inputs["Metallic"])
	nt.links.new(sep.outputs["Green"], b.inputs["Roughness"])
	b.inputs["Coat Weight"].default_value = 0.0
	if ACCENT:
		# accent-flagged voxels (sword enchant glint) glow in the accent pass only
		nt.links.new(sep.outputs["Blue"], b.inputs["Emission Strength"])
		b.inputs["Emission Color"].default_value = (1, 1, 1, 1)
		b.inputs["Base Color"].default_value = (0, 0, 0, 1)
	nt.links.new(b.outputs[0], out.inputs[0])
	return m


# --------------------------------------------------------------------------------------
# Mesh builder (numpy -> mesh, no shared vertices, flat faces)
# --------------------------------------------------------------------------------------

class MeshBuf:
	def __init__(self):
		self.co = []
		self.uv = []
		self.mat = []
		self.col = []
		self.pbr = []

	def quad(self, corners, uvrect=None, mat=0, col=None, pbr=None):
		self.co.extend(corners)
		if uvrect is not None:
			u0, v0, u1, v1 = uvrect
			self.uv.extend([(u0, v0), (u1, v0), (u1, v1), (u0, v1)])
		else:
			self.uv.extend([(0, 0)] * 4)
		self.mat.append(mat)
		if col is not None:
			self.col.extend([col] * 4)
			self.pbr.extend([pbr] * 4)

	def build(self, name, materials, link=True):
		n = len(self.co)
		f = n // 4
		me = bpy.data.meshes.new(name)
		me.vertices.add(n)
		me.vertices.foreach_set("co", np.asarray(self.co, np.float32).ravel())
		me.loops.add(n)
		me.loops.foreach_set("vertex_index", np.arange(n, dtype=np.int32))
		me.polygons.add(f)
		me.polygons.foreach_set("loop_start", np.arange(0, n, 4, dtype=np.int32))
		me.polygons.foreach_set("material_index", np.asarray(self.mat, np.int32))
		uvl = me.uv_layers.new(name="UVMap")
		uvl.data.foreach_set("uv", np.asarray(self.uv, np.float32).ravel())
		if self.col:
			ca = me.color_attributes.new("col", "FLOAT_COLOR", "CORNER")
			ca.data.foreach_set("color", np.asarray(self.col, np.float32).ravel())
			pa = me.color_attributes.new("pbr", "FLOAT_COLOR", "CORNER")
			pa.data.foreach_set("color", np.asarray(self.pbr, np.float32).ravel())
		me.update(calc_edges=True)
		me.validate()
		for m in materials:
			me.materials.append(m)
		ob = bpy.data.objects.new(name, me)
		if link:
			bpy.context.scene.collection.objects.link(ob)
		return ob


# face definitions: direction, 4 corners (unit cube), texture slot (0 top, 1 side, 2 bottom)
FACES = [
	((0, 0, 1), [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)], 0),
	((0, 0, -1), [(0, 1, 0), (1, 1, 0), (1, 0, 0), (0, 0, 0)], 2),
	((0, -1, 0), [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)], 1),
	((1, 0, 0), [(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)], 1),
	((0, 1, 0), [(1, 1, 0), (0, 1, 0), (0, 1, 1), (1, 1, 1)], 1),
	((-1, 0, 0), [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)], 1),
]

# block -> (top, side, bottom, material slot)
BLOCKS = {
	"grass": ("grass_top", "grass_side", "dirt", 0),
	"dirt": ("dirt", "dirt", "dirt", 0),
	"path": ("path_top", "dirt", "dirt", 0),
	"stone": ("stone", "stone", "stone", 0),
	"cobble": ("cobble", "cobble", "cobble", 0),
	"bricks": ("stone_bricks", "stone_bricks", "stone_bricks", 0),
	"mossy": ("mossy_bricks", "mossy_bricks", "mossy_bricks", 0),
	"cracked": ("cracked_bricks", "cracked_bricks", "cracked_bricks", 0),
	"chiseled": ("chiseled", "chiseled", "chiseled", 0),
	"oak_log": ("oak_log_top", "oak_log", "oak_log_top", 0),
	"birch_log": ("oak_log_top", "birch_log", "oak_log_top", 0),
	"spruce_log": ("oak_log_top", "spruce_log", "oak_log_top", 0),
	"planks": ("oak_planks", "oak_planks", "oak_planks", 0),
	"oak_leaves": ("oak_leaves",) * 3 + (1,),
	"birch_leaves": ("birch_leaves",) * 3 + (1,),
	"spruce_leaves": ("spruce_leaves",) * 3 + (1,),
	"cherry_leaves": ("cherry_leaves",) * 3 + (1,),
	"sand": ("sand", "sand", "sand", 0),
	"gravel": ("gravel", "gravel", "gravel", 0),
	"snow": ("snow", "snow_side", "stone", 0),
	"obsidian": ("obsidian", "obsidian", "obsidian", 0),
	"crystal": ("crystal", "crystal", "crystal", 2),
	"lantern": ("lantern", "lantern", "lantern", 2),
	"campfire": ("campfire", "campfire", "campfire", 3),
	"glass": ("glass", "glass", "glass", 4),
}
TRANSPARENT = {"oak_leaves", "birch_leaves", "spruce_leaves", "cherry_leaves", "glass"}


def mesh_blocks(blocks, buf):
	for (x, y, z), kind in blocks.items():
		top, side, bottom, slot = BLOCKS[kind]
		for d, corners, ts in FACES:
			nb = blocks.get((x + d[0], y + d[1], z + d[2]))
			if nb is not None and nb not in TRANSPARENT:
				continue
			if nb is not None and nb == kind and kind == "glass":
				continue
			name = (top, side, bottom)[ts]
			buf.quad([(x + c[0], y + c[1], z + c[2]) for c in corners], tile_uv(name), slot)


def box(buf, lo, hi, name, slot):
	for _, corners, _ in FACES:
		buf.quad([tuple(lo[i] + c[i] * (hi[i] - lo[i]) for i in range(3)) for c in corners], tile_uv(name), slot)


def lantern_post(buf, x, y):
	cx, cy = x + 0.5, y + 0.5
	p, l = 2 / 16, 3 / 16
	box(buf, (cx - p, cy - p, 1), (cx + p, cy + p, 2.15), "spruce_log", 0)
	box(buf, (cx - l - 1 / 16, cy - l - 1 / 16, 2.15), (cx + l + 1 / 16, cy + l + 1 / 16, 2.22), "obsidian", 0)
	box(buf, (cx - l, cy - l, 2.22), (cx + l, cy + l, 2.62), "lantern", 2)
	box(buf, (cx - l - 1 / 16, cy - l - 1 / 16, 2.62), (cx + l + 1 / 16, cy + l + 1 / 16, 2.69), "obsidian", 0)
	box(buf, (cx - 1 / 32, cy - 1 / 32, 2.69), (cx + 1 / 32, cy + 1 / 32, 2.8), "obsidian", 0)


def cross_plant(buf, x, y, z, name, g):
	ox, oy = 0.5 + (g.random() - 0.5) * 0.4, 0.5 + (g.random() - 0.5) * 0.4
	s = 0.45
	uv = tile_uv(name)
	for a in (math.pi / 4, -math.pi / 4):
		dx, dy = math.cos(a) * s, math.sin(a) * s
		p0 = (x + ox - dx, y + oy - dy, z)
		p1 = (x + ox + dx, y + oy + dy, z)
		buf.quad([p0, p1, (p1[0], p1[1], z + 0.92), (p0[0], p0[1], z + 0.92)], uv, 1)


# --------------------------------------------------------------------------------------
# World generation
# --------------------------------------------------------------------------------------

def value_noise(xs, ys, scale, seed):
	g = np.random.default_rng(seed)
	lat = g.random((512, 512))
	fx, fy = xs / scale + 256, ys / scale + 256
	ix, iy = np.floor(fx).astype(int), np.floor(fy).astype(int)
	tx, ty = fx - ix, fy - iy
	tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
	a = lat[iy % 512, ix % 512]
	b = lat[iy % 512, (ix + 1) % 512]
	c = lat[(iy + 1) % 512, ix % 512]
	d = lat[(iy + 1) % 512, (ix + 1) % 512]
	return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty


def fbm(xs, ys, scale, seed, octaves=5):
	v, amp, tot = 0, 1, 0
	for o in range(octaves):
		v = v + amp * value_noise(xs, ys, scale / (2 ** o), seed + o)
		tot += amp
		amp *= 0.5
	return v / tot


def az(x, y):
	return math.atan2(x - CAM.x, y - CAM.y)


ARENA = (0.5, 8.5)
LAKE = (-12.0, 16.5, 9.5, 7.5)  # center x, y, radius x, y
PORTAL_Y = 14


def heightmap(R):
	xs, ys = np.meshgrid(np.arange(-R, R), np.arange(-R, R))
	r = np.hypot(xs - CAM.x, ys - CAM.y)
	ang = np.arctan2(xs - CAM.x, ys - CAM.y)
	dang = np.angle(np.exp(1j * (ang - SUN_AZ)))
	hills = (fbm(xs, ys, 26, 100) - 0.45) * 7
	mnt = np.clip((r - 52) / 38, 0, 1) ** 1.4 * (18 + fbm(xs, ys, 30, 200) * 42)
	# a valley where the sun sets, so it sits low over the lake instead of behind a peak
	mnt *= 1 - 0.82 * np.exp(-(dang / 0.30) ** 2)
	h = hills + mnt
	# flat clearing around the camera and arena
	flat = np.clip((np.hypot(xs - ARENA[0], ys - ARENA[1] + 2) - 14) / 10, 0, 1)
	# keep a clear sightline over the lake toward the sunset
	flat = np.minimum(flat, np.where(r < 50, np.clip(np.abs(dang) / 0.45, 0.25, 1), 1))
	h = h * flat ** 1.5
	# lake basin
	lx, ly, rx, ry = LAKE
	e = np.hypot((xs - lx) / rx, (ys - ly) / ry)
	basin = np.clip(1 - e, 0, 1)
	h = np.where(e < 1.25, np.minimum(h, (e - 1) * 6), h)
	h = h - basin * 3.5
	h = np.round(h).astype(int)
	h = np.maximum(h, -4)
	return xs, ys, h, e


def tree_oak(blocks, x, y, z, g, leaves="oak_leaves", log="oak_log"):
	hgt = int(g.integers(4, 7))
	for k in range(hgt):
		blocks[(x, y, z + k)] = log
	top = z + hgt
	for dz in range(-2, 2):
		rad = 2 if dz < 0 else 1
		for dx in range(-rad, rad + 1):
			for dy in range(-rad, rad + 1):
				if abs(dx) == rad and abs(dy) == rad and (dz >= 0 or g.random() < 0.5):
					continue
				p = (x + dx, y + dy, top + dz)
				if p not in blocks:
					blocks[p] = leaves
	blocks[(x, y, top + 1)] = leaves


def tree_spruce(blocks, x, y, z, g):
	hgt = int(g.integers(7, 11))
	for k in range(hgt):
		blocks[(x, y, z + k)] = "spruce_log"
	rad = 0
	for k in range(hgt, 2, -1):
		layer = (hgt - k)
		rad = [0, 1, 1, 2, 1, 2, 3, 2, 3][min(layer, 8)]
		for dx in range(-rad, rad + 1):
			for dy in range(-rad, rad + 1):
				if abs(dx) + abs(dy) > rad + (1 if rad > 1 else 0):
					continue
				p = (x + dx, y + dy, z + k)
				if p not in blocks:
					blocks[p] = "spruce_leaves"
	blocks[(x, y, z + hgt)] = "spruce_leaves"


ACCENT_LIGHTS = []  # (pos, watts, radius)
LANTERNS = []  # small post lanterns, built as sub-block boxes
WARM_LIGHTS = []


def build_world(R):
	xs, ys, H, lake_e = heightmap(R)
	blocks = {}
	g = np.random.default_rng(3)
	surface = {}
	n = H.shape[0]
	for j in range(n):
		for i in range(n):
			x, y = int(xs[j, i]), int(ys[j, i])
			h = int(H[j, i])
			nb = [H[j, max(i - 1, 0)], H[j, min(i + 1, n - 1)], H[max(j - 1, 0), i], H[min(j + 1, n - 1), i]]
			low = min(min(nb), h)
			steep = h - min(nb) >= 3
			near_lake = lake_e[j, i] < 1.35
			for z in range(min(low - 1, h - 1), h + 1):
				if z == h:
					if near_lake and h <= 0:
						kind = "sand"
					elif h >= 30 + (x * 7 + y * 3) % 4:
						kind = "snow"
					elif steep or h > 22:
						kind = "stone"
					else:
						kind = "grass"
				elif z >= h - 2 and not steep and h < 22:
					kind = "sand" if near_lake and h <= 0 else "dirt"
				else:
					kind = "stone"
				if lake_e[j, i] < 0.85 and z == h and h < 0:
					kind = "gravel" if (x + y) % 3 == 0 else "sand"
				blocks[(x, y, z)] = kind
			surface[(x, y)] = h

	r_of = lambda x, y: math.hypot(x - CAM.x, y - CAM.y)

	# --- arena: worn stone-brick floor, broken ring wall, four crystal pillars
	ax, ay = ARENA
	for x in range(-7, 8):
		for y in range(1, 17):
			d = math.hypot(x + 0.5 - ax, y + 0.5 - ay)
			if d <= 6.2:
				roll = g.random()
				blocks[(x, y, 0)] = "mossy" if roll < 0.18 else "cracked" if roll < 0.32 else "bricks"
			elif d <= 7.2 and g.random() < 0.55 and surface.get((x, y), 0) == 0:
				blocks[(x, y, 0)] = "path" if g.random() < 0.5 else "gravel"
	for k in range(36):
		a = k / 36 * 2 * math.pi
		x = int(math.floor(ax + math.cos(a) * 6.7))
		y = int(math.floor(ay + math.sin(a) * 6.7))
		# keep the front open so the duel is visible from the camera
		if y < ay - 3 and abs(x - ax) < 5:
			continue
		if g.random() < 0.7:
			blocks[(x, y, 1)] = "mossy" if g.random() < 0.3 else "bricks"
			if g.random() < 0.25:
				blocks[(x, y, 2)] = "cracked"
	for (px, py) in ((-6, 7), (7, 7), (-4, 13), (5, 13)):
		hgt = 3
		for k in range(1, hgt + 1):
			blocks[(px, py, k)] = "chiseled" if k == 1 else "bricks"
		blocks[(px, py, hgt + 1)] = "crystal"
		ACCENT_LIGHTS.append(((px + 0.5, py + 0.5, hgt + 2.4), 110, 0.3))

	# --- ruined portal behind the fighters
	frame = []
	for x in range(-2, 4):
		frame.append((x, 0))
		frame.append((x, 6))
	for z in range(1, 6):
		frame.append((-2, z))
		frame.append((3, z))
	for (x, z) in frame:
		if (x, z) in ((3, 6), (-2, 6)) or ((x, z) == (2, 6)):
			continue
		blocks[(x, PORTAL_Y, z + 1)] = "obsidian"
	blocks[(4, PORTAL_Y, 1)] = "obsidian"
	blocks[(4, PORTAL_Y - 1, 1)] = "obsidian"
	blocks[(-3, PORTAL_Y + 1, 1)] = "obsidian"
	for x in range(-3, 5):
		blocks[(x, PORTAL_Y, 0)] = "bricks"
	ACCENT_LIGHTS.append(((0.5, PORTAL_Y - 0.6, 4.2), 420, 1.2))

	# --- lantern posts along the path from the camera to the arena / to the house
	for (lx, ly) in ((-3, -8), (4, -8), (-3, -15), (4, -15), (-6, 2), (7, 2)):
		LANTERNS.append((lx, ly))
		ACCENT_LIGHTS.append(((lx + 0.5, ly + 0.5, 2.55), 22, 0.1))
	for y in range(-14, 2):
		for x in (0, 1):
			if surface.get((x, y), 9) == 0 and (x, y, 0) in blocks and blocks[(x, y, 0)] == "grass":
				blocks[(x, y, 0)] = "path"

	# --- house behind the camera, windows glowing in the theme color
	hx, hy = -3, -22
	for x in range(hx, hx + 8):
		for y in range(hy, hy + 6):
			for z in range(1, 5):
				edge_x = x in (hx, hx + 7)
				edge_y = y in (hy, hy + 5)
				if not (edge_x or edge_y):
					continue
				corner = edge_x and edge_y
				kind = "oak_log" if corner else ("cobble" if z == 1 else "planks")
				if not corner and z in (2, 3) and ((edge_y and x in (hx + 2, hx + 5)) or (edge_x and y in (hy + 2, hy + 3))):
					kind = "glass"
				if y == hy + 5 and x in (hx + 3, hx + 4) and z in (1, 2):
					continue
				blocks[(x, y, z)] = kind
			blocks[(x, y, 0)] = "planks" if hx < x < hx + 7 and hy < y < hy + 5 else blocks.get((x, y, 0), "cobble")
	for k in range(4):
		for x in range(hx - 1 + k, hx + 9 - k):
			for y in range(hy - 1, hy + 7):
				blocks[(x, y, 5 + k)] = "planks" if k < 3 else "oak_log"
	# glowing interior panels behind the windows
	for (x, y) in ((hx + 2, hy + 1), (hx + 5, hy + 1), (hx + 2, hy + 4), (hx + 5, hy + 4), (hx + 1, hy + 2), (hx + 6, hy + 3)):
		blocks[(x, y, 1)] = "lantern"
	ACCENT_LIGHTS.append(((hx + 4, hy + 3, 3.0), 260, 1.0))
	# campfire by the house (warm, base pass)
	blocks[(hx + 10, hy + 6, 1)] = "campfire"
	WARM_LIGHTS.append(((hx + 10.5, hy + 6.5, 2.0), 220, 0.4))
	for (dx, dy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
		blocks[(hx + 10 + dx, hy + 6 + dy, 1)] = "cobble"

	# --- stone tower with a crystal beacon
	tx, ty = -24, -13
	for z in range(1, 17):
		for x in range(tx - 3, tx + 4):
			for y in range(ty - 3, ty + 4):
				d = math.hypot(x - tx, y - ty)
				if 2.2 <= d <= 3.2:
					if z > 13 and (x + y + z) % 2 and z == 16:
						continue
					if z in (6, 10) and (x == tx or y == ty) and d > 2.8:
						blocks[(x, y, z)] = "lantern"
						continue
					blocks[(x, y, z)] = "mossy" if g.random() < 0.2 else "bricks"
	for x in range(tx - 1, tx + 2):
		for y in range(ty - 1, ty + 2):
			blocks[(x, y, 15)] = "bricks"
	blocks[(tx, ty, 16)] = "crystal"
	blocks[(tx, ty, 17)] = "crystal"
	ACCENT_LIGHTS.append(((tx + 0.5, ty + 0.5, 19.0), 900, 0.5))

	# --- vegetation
	g2 = np.random.default_rng(11)
	occupied = set()
	for (x, y), h in surface.items():
		r = r_of(x + 0.5, y + 0.5)
		if blocks.get((x, y, h)) != "grass" or (x, y, h + 1) in blocks:
			continue
		if math.hypot(x - ax, y - ay) < 8.5 or (-5 <= x <= 6 and -26 <= y <= 1) or math.hypot(x - tx, y - ty) < 6:
			continue
		a = math.degrees(az(x + 0.5, y + 0.5))
		forest = 35 < a < 165 and 11 < r < 48
		density = 0.11 if forest else 0.012 if r < 60 else 0.02 if h < 20 else 0
		if r < 6 or abs(math.degrees(math.atan2(math.sin(math.radians(a) - SUN_AZ), math.cos(math.radians(a) - SUN_AZ)))) < 26:
			density = 0
		if g2.random() < density and all((x + dx, y + dy) not in occupied for dx in (-2, -1, 0, 1, 2) for dy in (-2, -1, 0, 1, 2)):
			occupied.add((x, y))
			roll = g2.random()
			if h > 8 or r > 58:
				tree_spruce(blocks, x, y, h + 1, g2)
			elif forest and roll < 0.35:
				tree_oak(blocks, x, y, h + 1, g2, "birch_leaves", "birch_log")
			elif -150 < a < -100 and roll < 0.6:
				tree_oak(blocks, x, y, h + 1, g2, "cherry_leaves", "oak_log")
			else:
				tree_oak(blocks, x, y, h + 1, g2)
	plants = []
	for (x, y), h in surface.items():
		if blocks.get((x, y, h)) != "grass" or (x, y, h + 1) in blocks:
			continue
		r = r_of(x + 0.5, y + 0.5)
		if r > 45 or r < 4.5 or math.hypot(x - ax, y - ay) < 6.6:
			continue
		roll = g2.random()
		if roll < 0.30:
			plants.append((x, y, h + 1, "tallgrass"))
		elif roll < 0.335:
			plants.append((x, y, h + 1, ("poppy", "dandelion", "cornflower")[int(g2.integers(0, 3))]))
	return blocks, plants


# --------------------------------------------------------------------------------------
# Fighters (1/16-block voxels with per-voxel color + PBR)
# --------------------------------------------------------------------------------------

PX = 0.9 / 16


def shade(c, g, k=0.06):
	f = 1 + (g.random() * 2 - 1) * k
	return tuple(min(1.0, max(0.0, v * f)) for v in c)


def srgb_to_lin(c):
	return tuple(v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c)


def voxel_part(name, size, offset, painter, mats, parent=None, origin=(0, 0, 0)):
	"""size in pixels (w, d, h); offset = position of the part's min corner relative to its pivot (pixels)."""
	w, d, h = size
	buf = MeshBuf()
	cells = {}
	for x in range(w):
		for y in range(d):
			for z in range(h):
				res = painter(x, y, z, w, d, h)
				if res is not None:
					cells[(x, y, z)] = res
	for (x, y, z), (col, pbr) in cells.items():
		for dvec, corners, _ in FACES:
			if (x + dvec[0], y + dvec[1], z + dvec[2]) in cells:
				continue
			pts = [((x + c[0] + offset[0]) * PX, (y + c[1] + offset[1]) * PX, (z + c[2] + offset[2]) * PX) for c in corners]
			buf.quad(pts, None, 0, srgb_to_lin(col) + (1.0,), pbr + (1.0,))
	ob = buf.build(name, mats)
	ob.location = origin
	if parent is not None:
		ob.parent = parent
	return ob


def make_skin(kind):
	"""Returns per-part painters for a fighter look."""
	g = np.random.default_rng(hash(kind) % 1000)
	if kind == "knight":  # netherite armor, red Velo cape, diamond sword
		armor = (0.24, 0.22, 0.25)
		armor_hi = (0.38, 0.35, 0.40)
		trim = (0.86, 0.22, 0.25)
		apbr = (1.0, 0.32, 0.0)
		skin = (0.78, 0.58, 0.44)
	else:  # diamond armor, shield
		armor = (0.33, 0.78, 0.80)
		armor_hi = (0.62, 0.95, 0.93)
		trim = (0.12, 0.40, 0.48)
		apbr = (0.55, 0.22, 0.0)
		skin = (0.55, 0.38, 0.27)
	cloth = (0.16, 0.18, 0.26)
	cpbr = (0.0, 0.85, 0.0)
	spbr = (0.0, 0.6, 0.0)

	def a(c=None):
		return (shade(c or armor, g), apbr)

	def head(x, y, z, w, d, h):
		front = y == d - 1
		if front and 1 <= z <= 5 and 1 <= x <= 6:
			# face under the visor
			if z == 3 and x in (1, 2, 5, 6):
				return (((0.95, 0.95, 0.95) if x in (1, 6) else (0.12, 0.25, 0.55 if kind == "knight" else 0.15)), spbr)
			if z == 1 and 3 <= x <= 4:
				return ((0.45, 0.28, 0.22), spbr)
			return (shade(skin, g, 0.04), spbr)
		if z == h - 1 and 3 <= x <= 4 and kind == "knight":
			return (shade(trim, g), apbr)
		if front and z == 6:
			return a(armor_hi)
		return a()

	def body(x, y, z, w, d, h):
		if z <= 1:
			return ((0.30, 0.22, 0.14), (0.0, 0.6, 0.0)) if not (x in (3, 4) and y == d - 1) else ((0.80, 0.70, 0.30), (1.0, 0.3, 0.0))
		if y == d - 1 and (x in (3, 4)) and z >= 4:
			return a(trim)
		if z == h - 1 or (y == d - 1 and z == h - 2):
			return a(armor_hi)
		return a()

	def arm(x, y, z, w, d, h):
		if z <= 2:
			return (shade(skin, g, 0.04), spbr)
		if z >= h - 4:
			return a(armor_hi if z == h - 1 else armor)
		if z == h - 5:
			return a(trim)
		return (shade(cloth, g), cpbr)

	def leg(x, y, z, w, d, h):
		if z <= 3:
			return a(armor_hi if z == 3 else armor)
		if z >= h - 2:
			return a()
		if y == d - 1 and x in (1, 2) and z in (6, 7):
			return a(armor_hi)
		return a() if kind == "knight" or z > 6 else (shade(cloth, g), cpbr)

	return head, body, arm, leg


SWORD = [
	"..............##",
	".............#dD",
	"............#dD#",
	"...........#dD#.",
	"..........#dD#..",
	".........#dD#...",
	"........#dD#....",
	".......#dD#.....",
	"..##..#dD#......",
	"..#g##dD#.......",
	"...#gdD#........",
	"....#g#.........",
	"...#h#g#........",
	"..#h#.##........",
	"##h#............",
	"#b#.............",
]


def sword_painter(blade, edge, outline, glint):
	def p(x, y, z, w, d, h):
		row = SWORD[15 - z]
		ch = row[x]
		if ch == ".":
			return None
		col = {"#": outline, "d": blade, "D": edge, "g": (0.30, 0.25, 0.30), "h": (0.40, 0.28, 0.16), "b": (0.25, 0.20, 0.25)}[ch]
		met = 0.8 if ch in "dDg" else 0.0
		return (col, (met, 0.18 if ch in "dD" else 0.5, glint if ch in "dD" else 0.0))
	return p


def shield_painter(x, y, z, w, d, h):
	if y == 0:  # wooden back
		return ((0.45, 0.33, 0.20), (0.0, 0.7, 0.0))
	edge = x in (0, w - 1) or z in (0, h - 1)
	if edge:
		return ((0.62, 0.63, 0.66), (1.0, 0.3, 0.0))
	# a stylised chevron emblem on a dark blue field
	cx = abs(x - (w - 1) / 2)
	if abs((h - 1 - z) * 0.55 - cx - 2.5) < 1.2 and z > 4:
		return ((0.90, 0.90, 0.92), (0.0, 0.5, 0.0))
	return ((0.10, 0.20, 0.45), (0.0, 0.55, 0.0))


def cape_painter(x, y, z, w, d, h):
	if z <= 1 or x in (0, w - 1):
		return ((0.55, 0.06, 0.09), (0.0, 0.8, 0.0))
	if 3 <= x <= w - 4 and 6 <= z <= 11 and abs((x - (w - 1) / 2)) < (z - 5) * 0.55 and abs((x - (w - 1) / 2)) > (z - 5) * 0.55 - 1.6:
		return ((0.98, 0.85, 0.85), (0.0, 0.6, 0.0))  # a white "V"
	return ((0.86, 0.14, 0.20), (0.0, 0.8, 0.0))


class Fighter:
	def __init__(self, kind, pos, yaw, mats):
		self.kind = kind
		head_p, body_p, arm_p, leg_p = make_skin(kind)
		root = bpy.data.objects.new(kind, None)
		bpy.context.scene.collection.objects.link(root)
		root.location = pos
		root.rotation_euler = (0, 0, yaw)
		self.root = root
		self.base_z = pos[2]
		u = PX
		self.body = voxel_part(kind + "_body", (8, 4, 12), (-4, -2, 0), body_p, mats, root, (0, 0, 12 * u))
		self.head = voxel_part(kind + "_head", (8, 8, 8), (-4, -4, 0), head_p, mats, self.body, (0, 0, 12 * u))
		self.arm_r = voxel_part(kind + "_arm_r", (4, 4, 12), (-2, -2, -10), arm_p, mats, self.body, (6 * u, 0, 10 * u))
		self.arm_l = voxel_part(kind + "_arm_l", (4, 4, 12), (-2, -2, -10), arm_p, mats, self.body, (-6 * u, 0, 10 * u))
		self.leg_r = voxel_part(kind + "_leg_r", (4, 4, 12), (-2, -2, -12), leg_p, mats, root, (2 * u, 0, 12 * u))
		self.leg_l = voxel_part(kind + "_leg_l", (4, 4, 12), (-2, -2, -12), leg_p, mats, root, (-2 * u, 0, 12 * u))
		if kind == "knight":
			sp = sword_painter((0.45, 0.92, 0.88), (0.80, 1.0, 0.97), (0.10, 0.30, 0.32), 2.5)
		else:
			sp = sword_painter((0.80, 0.80, 0.84), (0.97, 0.97, 1.0), (0.25, 0.25, 0.30), 0.0)
		# sword: sprite in the arm's y/z plane, held in the fist, blade pointing forward
		self.sword = voxel_part(kind + "_sword", (16, 1, 16), (-2.5, -0.5, -2.5), sp, mats, self.arm_r, (0, 0, -9 * u))
		self.sword.rotation_euler = (0, 0, math.radians(90))
		self.sword_rest = (math.radians(-45 + 90), 0, math.radians(90))
		self.shield = None
		self.cape = None
		if kind != "knight":
			self.shield = voxel_part(kind + "_shield", (12, 2, 20), (-6, 0, -10), shield_painter, mats, self.arm_l, (-3 * u, 0, -6 * u))
			self.shield.rotation_euler = (0, 0, math.radians(90))
		else:
			self.cape = voxel_part(kind + "_cape", (10, 1, 16), (-5, -1, -16), cape_painter, mats, self.body, (0, -2 * u, 12 * u))

	def pose(self, p):
		R = math.radians
		self.root.location.z = self.base_z + p.get("bob", 0)
		self.root.location.x = self.root_x0 + p.get("step", 0) * math.sin(self.root.rotation_euler.z + math.pi / 2) * 0 + p.get("dx", 0)
		self.body.rotation_euler = (R(p.get("lean", 0)), 0, R(p.get("twist", 0)))
		self.head.rotation_euler = (R(p.get("nod", 0)), 0, R(-p.get("twist", 0) * 0.6))
		self.arm_r.rotation_euler = (R(p.get("rp", 0)), R(p.get("rr", 0)), R(p.get("ry", 0)))
		self.arm_l.rotation_euler = (R(p.get("lp", 0)), R(p.get("lr", 0)), R(p.get("ly", 0)))
		self.leg_r.rotation_euler = (R(p.get("legr", 0)), 0, R(-8))
		self.leg_l.rotation_euler = (R(p.get("legl", 0)), 0, R(8))
		self.sword.rotation_euler = (R(p.get("sp", 0)), 0, R(90))
		if self.shield is not None:
			self.shield.rotation_euler = (R(p.get("shp", 0)), 0, R(p.get("shy", 90)))
		if self.cape is not None:
			self.cape.rotation_euler = (R(-p.get("cape", 15)), 0, 0)


def keys(t, frames):
	"""Periodic smooth interpolation through (t, value) keyframes (t in [0,1))."""
	frames = sorted(frames)
	ext = [(ft - 1, v) for ft, v in frames] + frames + [(ft + 1, v) for ft, v in frames]
	for i in range(len(ext) - 1):
		t0, v0 = ext[i]
		t1, v1 = ext[i + 1]
		if t0 <= t <= t1:
			# Catmull-Rom between neighbours for smooth motion
			tm1, vm1 = ext[i - 1] if i > 0 else ext[i]
			t2, v2 = ext[i + 2] if i + 2 < len(ext) else ext[i + 1]
			s = 0 if t1 == t0 else (t - t0) / (t1 - t0)
			m0 = (v1 - vm1) / max(1e-6, (t1 - tm1)) * (t1 - t0)
			m1 = (v2 - v0) / max(1e-6, (t2 - t0)) * (t1 - t0)
			s2, s3 = s * s, s * s * s
			return (2 * s3 - 3 * s2 + 1) * v0 + (s3 - 2 * s2 + s) * m0 + (-2 * s3 + 3 * s2) * v1 + (s3 - s2) * m1
	return frames[0][1]


def duel_pose(t):
	"""Knight (left) slashes at 0.38, guard (right) blocks with the shield, then counters at 0.80."""
	k = {
		"rp": keys(t, [(0.0, 60), (0.22, 165), (0.30, 172), (0.40, 35), (0.50, 50), (0.68, 70), (0.80, 95), (0.90, 70)]),
		"rr": keys(t, [(0.0, 0), (0.22, -20), (0.40, 20), (0.55, 0), (0.80, -15)]),
		"ry": keys(t, [(0.0, 10), (0.30, -10), (0.40, 25), (0.60, 10), (0.80, -20), (0.9, 5)]),
		"lp": keys(t, [(0.0, 25), (0.30, 40), (0.40, 15), (0.80, 30)]),
		"lr": keys(t, [(0.0, -8), (0.5, -14)]),
		"twist": keys(t, [(0.0, 0), (0.25, 22), (0.40, -24), (0.55, -8), (0.80, 10), (0.9, 4)]),
		"lean": keys(t, [(0.0, 6), (0.25, -3), (0.40, 14), (0.60, 6), (0.80, -4)]),
		"legr": keys(t, [(0.0, 18), (0.40, 30), (0.60, 18), (0.80, 10)]),
		"legl": keys(t, [(0.0, -16), (0.40, -26), (0.60, -16), (0.80, -12)]),
		"dx": keys(t, [(0.0, 0.0), (0.38, 0.22), (0.60, 0.08), (0.80, -0.10), (0.95, 0.0)]),
		"bob": keys(t, [(0.0, 0.0), (0.2, 0.03), (0.4, -0.04), (0.6, 0.01), (0.8, -0.03)]),
		"cape": keys(t, [(0.0, 18), (0.2, 26), (0.4, 36), (0.6, 24), (0.8, 16), (0.9, 20)]),
		"sp": keys(t, [(0.0, 5), (0.3, -20), (0.42, 25), (0.6, 5), (0.8, -10)]),
		"nod": keys(t, [(0.0, 0), (0.4, 8), (0.8, -6)]),
	}
	g = {
		"lp": keys(t, [(0.0, 40), (0.25, 85), (0.35, 92), (0.52, 70), (0.70, 25), (0.92, 45)]),
		"ly": keys(t, [(0.0, -25), (0.3, -40), (0.5, -30), (0.75, -10)]),
		"lr": keys(t, [(0.0, -5)]),
		"shy": keys(t, [(0.0, 90)]),
		"shp": keys(t, [(0.0, 0)]),
		"rp": keys(t, [(0.0, 50), (0.4, 40), (0.58, 120), (0.68, 140), (0.80, 20), (0.92, 40)]),
		"rr": keys(t, [(0.0, 10), (0.68, -25), (0.80, 25)]),
		"ry": keys(t, [(0.0, 0), (0.68, 15), (0.80, -25), (0.95, 0)]),
		"twist": keys(t, [(0.0, 0), (0.38, 14), (0.65, -20), (0.80, 22), (0.95, 4)]),
		"lean": keys(t, [(0.0, 4), (0.38, -8), (0.6, 0), (0.80, 14)]),
		"legr": keys(t, [(0.0, -18), (0.4, -26), (0.8, -12)]),
		"legl": keys(t, [(0.0, 16), (0.4, 22), (0.8, 30)]),
		"dx": keys(t, [(0.0, 0.0), (0.40, 0.16), (0.55, 0.05), (0.80, -0.20), (0.95, -0.02)]),
		"bob": keys(t, [(0.1, 0.0), (0.3, 0.03), (0.5, -0.04), (0.7, 0.01), (0.9, -0.03)]),
		"sp": keys(t, [(0.0, 0), (0.68, -25), (0.82, 20)]),
		"nod": keys(t, [(0.0, 0), (0.4, -8), (0.8, 8)]),
	}
	return k, g


def sparks(t, mats_accent):
	"""Tiny glowing debris at the two weapon impacts (accent colored)."""
	buf = MeshBuf()
	g = np.random.default_rng(int(t * 1000) + 5)
	for impact, centre in ((0.40, (0.55, 6.95, 2.35)), (0.80, (0.15, 6.85, 2.15))):
		dt = (t - impact) % 1.0
		if dt > 0.12:
			continue
		life = dt / 0.12
		for _ in range(22):
			v = g.normal(0, 1, 3)
			v /= np.linalg.norm(v) + 1e-6
			dist = (0.15 + 0.85 * g.random()) * (0.12 + life * 0.75)
			p = np.array(centre) + v * dist + np.array((0, 0, -0.6 * life * life))
			s = 0.035 * (1 - life * 0.7)
			for dvec, corners, _ in FACES:
				buf.quad([tuple(p + (np.array(c) - 0.5) * s) for c in corners], tile_uv("crystal"), 0)
	if buf.co:
		buf.build("sparks", [mats_accent])


# --------------------------------------------------------------------------------------
# Scene assembly
# --------------------------------------------------------------------------------------

def setup_render():
	sc = bpy.context.scene
	sc.render.engine = "CYCLES"
	prefs = bpy.context.preferences.addons["cycles"].preferences
	prefs.compute_device_type = "OPTIX"
	prefs.get_devices()
	for d in prefs.devices:
		d.use = d.type == "OPTIX"
	sc.cycles.device = "GPU"
	sc.cycles.samples = ARGS.samples
	sc.cycles.use_adaptive_sampling = True
	sc.cycles.adaptive_threshold = 0.01
	sc.cycles.use_denoising = True
	sc.cycles.denoiser = "OPENIMAGEDENOISE"
	sc.cycles.denoising_use_gpu = True
	sc.cycles.max_bounces = 8
	sc.cycles.diffuse_bounces = 3
	sc.cycles.glossy_bounces = 4
	sc.cycles.transmission_bounces = 6
	sc.cycles.transparent_max_bounces = 24
	sc.cycles.caustics_reflective = False
	sc.cycles.caustics_refractive = False
	sc.cycles.blur_glossy = 1.0
	sc.cycles.use_auto_tile = True
	sc.cycles.tile_size = 1024
	sc.cycles.seed = 11
	sc.render.resolution_x = WIDTH
	sc.render.resolution_y = HEIGHT
	sc.render.resolution_percentage = 100
	sc.render.film_transparent = False
	sc.render.image_settings.file_format = "PNG"
	sc.render.image_settings.color_mode = "RGB"
	sc.render.image_settings.color_depth = "8"
	sc.render.image_settings.compression = 90
	if ACCENT:
		sc.view_settings.view_transform = "Standard"
		sc.view_settings.look = "None"
		sc.view_settings.exposure = 0
	else:
		sc.view_settings.view_transform = "AgX"
		try:
			sc.view_settings.look = "AgX - Medium High Contrast"
		except TypeError:
			pass
		sc.view_settings.exposure = 0.75
	if ARGS.crop:
		x0, y0, x1, y1 = [int(v) for v in ARGS.crop.split(",")]
		sc.render.use_border = True
		sc.render.use_crop_to_border = True
		sc.render.border_min_x = x0 / WIDTH
		sc.render.border_max_x = x1 / WIDTH
		# border y is measured from the bottom
		sc.render.border_min_y = 1 - y1 / HEIGHT
		sc.render.border_max_y = 1 - y0 / HEIGHT


def setup_world():
	sc = bpy.context.scene
	w = bpy.data.worlds.new("sky")
	sc.world = w
	w.use_nodes = True
	nt = w.node_tree
	bg = nt.nodes["Background"]
	if ACCENT:
		bg.inputs["Strength"].default_value = 0.0
		bg.inputs["Color"].default_value = (0, 0, 0, 1)
	else:
		sky = nt.nodes.new("ShaderNodeTexSky")
		sky.sky_type = "NISHITA"
		sky.sun_elevation = SUN_EL
		sky.sun_rotation = SUN_AZ % (2 * math.pi)  # same azimuth convention (checked with --sky-only)
		sky.altitude = 300
		sky.air_density = 1.0
		sky.dust_density = 3.4
		sky.ozone_density = 1.2
		sky.sun_size = math.radians(1.6)
		sky.sun_intensity = 0.45
		nt.links.new(sky.outputs["Color"], bg.inputs["Color"])
		bg.inputs["Strength"].default_value = 0.6
	w.mist_settings.start = 26
	w.mist_settings.depth = 190
	w.mist_settings.falloff = "QUADRATIC"
	sc.view_layers[0].use_pass_mist = True


def setup_compositor():
	"""Aerial perspective: distant terrain fades into a warm haze (mist pass)."""
	sc = bpy.context.scene
	sc.use_nodes = True
	nt = sc.node_tree
	for n in list(nt.nodes):
		nt.nodes.remove(n)
	rl = nt.nodes.new("CompositorNodeRLayers")
	comp = nt.nodes.new("CompositorNodeComposite")
	mist_pow = nt.nodes.new("CompositorNodeMath")
	mist_pow.operation = "MULTIPLY"
	mist_pow.inputs[1].default_value = 0.55
	nt.links.new(rl.outputs["Mist"], mist_pow.inputs[0])
	mix = nt.nodes.new("CompositorNodeMixRGB")
	mix.blend_type = "MIX"
	if ACCENT:
		mix.inputs[2].default_value = (0, 0, 0, 1)
	else:
		mix.inputs[2].default_value = (0.74, 0.60, 0.52, 1)
	nt.links.new(mist_pow.outputs[0], mix.inputs["Fac"])
	nt.links.new(rl.outputs["Image"], mix.inputs[1])
	nt.links.new(mix.outputs[0], comp.inputs["Image"])


def add_lights():
	sc = bpy.context.scene
	if not ACCENT:
		sun = bpy.data.lights.new("sun", "SUN")
		sun.energy = 5.0
		sun.color = (1.0, 0.72, 0.48)
		sun.angle = math.radians(1.2)
		ob = bpy.data.objects.new("sun", sun)
		sc.collection.objects.link(ob)
		d = Vector((math.sin(SUN_AZ) * math.cos(SUN_EL), math.cos(SUN_AZ) * math.cos(SUN_EL), math.sin(SUN_EL)))
		ob.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
		for i, (p, watts, rad) in enumerate(WARM_LIGHTS):
			l = bpy.data.lights.new(f"warm{i}", "POINT")
			l.energy = watts
			l.color = (1.0, 0.55, 0.22)
			l.shadow_soft_size = rad
			o = bpy.data.objects.new(f"warm{i}", l)
			o.location = p
			sc.collection.objects.link(o)
	else:
		for i, (p, watts, rad) in enumerate(ACCENT_LIGHTS):
			l = bpy.data.lights.new(f"acc{i}", "POINT")
			l.energy = watts
			l.color = (1, 1, 1)
			l.shadow_soft_size = rad
			o = bpy.data.objects.new(f"acc{i}", l)
			o.location = p
			sc.collection.objects.link(o)


def add_clouds(mat_cloud):
	"""Flat Minecraft-style cloud slabs high above."""
	buf = MeshBuf()
	g = np.random.default_rng(21)
	uv = tile_uv("cloud")
	cells = set()
	for _ in range(70):
		cx, cy = g.uniform(-260, 260, 2)
		if math.hypot(cx, cy) < 40:
			continue
		w, d = g.integers(2, 7, 2)
		for i in range(w):
			for j in range(d):
				if g.random() < 0.85:
					cells.add((int(cx // 12) + i, int(cy // 12) + j))
	for (i, j) in cells:
		x0, y0, z0 = i * 12, j * 12, 58
		for dvec, corners, _ in FACES:
			if (i + dvec[0], j + dvec[1]) in cells and dvec[2] == 0:
				continue
			buf.quad([(x0 + c[0] * 12, y0 + c[1] * 12, z0 + c[2] * 4) for c in corners], uv, 0)
	buf.build("clouds", [mat_cloud])


def cloud_material():
	m, nt, out = new_mat("cloud")
	b = nt.nodes.new("ShaderNodeBsdfPrincipled")
	b.inputs["Base Color"].default_value = (1, 1, 1, 1)
	b.inputs["Roughness"].default_value = 1
	b.inputs["Alpha"].default_value = 0.82
	tr = nt.nodes.new("ShaderNodeBsdfTranslucent")
	mix = nt.nodes.new("ShaderNodeMixShader")
	mix.inputs["Fac"].default_value = 0.35
	nt.links.new(b.outputs[0], mix.inputs[1])
	nt.links.new(tr.outputs[0], mix.inputs[2])
	if ACCENT:
		holdout = nt.nodes.new("ShaderNodeBsdfDiffuse")
		holdout.inputs["Color"].default_value = (0, 0, 0, 1)
		nt.links.new(holdout.outputs[0], out.inputs[0])
	else:
		nt.links.new(mix.outputs[0], out.inputs[0])
	return m


def add_camera():
	sc = bpy.context.scene
	cd = bpy.data.cameras.new("pano")
	cd.type = "PANO"
	cd.panorama_type = "CENTRAL_CYLINDRICAL"
	cd.central_cylindrical_range_u_min = -math.pi
	cd.central_cylindrical_range_u_max = math.pi
	cd.central_cylindrical_range_v_min = V_MIN
	cd.central_cylindrical_range_v_max = V_MAX
	cd.clip_start = 0.05
	cd.clip_end = 1000
	ob = bpy.data.objects.new("pano", cd)
	sc.collection.objects.link(ob)
	ob.location = CAM
	ob.rotation_euler = (math.radians(90), 0, 0)  # looking along +Y
	sc.camera = ob


def main():
	bpy.ops.wm.read_factory_settings(use_empty=True)
	atlas, rough = build_atlas()
	mats = make_materials(atlas, rough)
	setup_render()
	setup_world()
	setup_compositor()
	add_camera()
	if ARGS.sky_only:
		bpy.context.scene.render.filepath = ARGS.out
		bpy.ops.render.render(write_still=True)
		return

	blocks, plants = build_world(ARGS.terrain_radius)
	buf = MeshBuf()
	mesh_blocks(blocks, buf)
	g = np.random.default_rng(99)
	for (x, y, z, name) in plants:
		cross_plant(buf, x, y, z, name, g)
	for (x, y) in LANTERNS:
		lantern_post(buf, x, y)
	buf.build("world", mats)
	print(f"[pano] faces: {len(buf.mat)}")

	wp = bpy.data.meshes.new("water")
	s = 60
	wp.from_pydata([(-s - 15, -s + 20, 0.875), (s - 15, -s + 20, 0.875), (s - 15, s + 20, 0.875), (-s - 15, s + 20, 0.875)], [], [(0, 1, 2, 3)])
	wp.materials.append(water_material())
	wo = bpy.data.objects.new("water", wp)
	bpy.context.scene.collection.objects.link(wo)

	veil = bpy.data.meshes.new("portal")
	py = PORTAL_Y + 0.5
	veil.from_pydata([(-1, py, 2), (3, py, 2), (3, py, 7), (-1, py, 7)], [], [(0, 1, 2, 3)])
	veil.materials.append(portal_material())
	vo = bpy.data.objects.new("portal", veil)
	bpy.context.scene.collection.objects.link(vo)

	add_clouds(cloud_material())
	add_lights()

	vmat = voxel_material()
	knight = Fighter("knight", (-0.55, 6.9, 1.0), math.radians(-90), [vmat])
	guard = Fighter("guard", (1.55, 7.1, 1.0), math.radians(90), [vmat])
	knight.root_x0 = knight.root.location.x
	guard.root_x0 = guard.root.location.x
	t = (ARGS.frame % ARGS.frames) / ARGS.frames
	kp, gp = duel_pose(t)
	knight.pose(kp)
	gp_dx = gp.pop("dx", 0)
	guard.pose(dict(gp, dx=-gp_dx))
	sparks(t, mats[2])

	bpy.context.scene.render.filepath = ARGS.out
	bpy.ops.render.render(write_still=True)
	print("[pano] wrote", ARGS.out)


main()
