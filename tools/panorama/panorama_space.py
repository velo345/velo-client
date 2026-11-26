"""
"Drift" - the Velo title-screen panorama in space (2026-11-25), rendered in Blender (Cycles, path traced).

A Minecraft astronaut floats weightless in front of you; a second player sits on a floating island and
watches the planets: the Overworld (oceans, continents, ice caps, block clouds), a red desert planet with a ring of
blocks, a small amethyst moon that glows in the theme colour, a far ice world. Stars and a nebula band behind,
asteroids of stone and ore drifting around. Everything is blocks and 1/16 voxels - procedural, nothing from the
Minecraft jar is shipped.

Same output contract as panorama_scene.py (which it borrows its building blocks from): a 360-degree
central-cylindrical strip, base + accent passes, --frame/--frames/--crop for the animated loop (here the astronaut).

  ./render.sh --script panorama_space.py --out DIR --mode base|accent --width 6144 --samples 256
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Vector, Euler, Quaternion  # noqa: E402

import panorama_scene as P  # noqa: E402  (building blocks only: its main() is guarded)

ARGS = P.ARGS
ACCENT = P.ACCENT
CAM = P.CAM
R = math.radians


# --------------------------------------------------------------------------------------
# Extra block textures
# --------------------------------------------------------------------------------------

def add_tiles():
	j, b, t = P.jitter, P.blotches, P.tile
	t("water", b(j((0.16, 0.34, 0.76), 0.08, 201), (0.12, 0.27, 0.66), 12, 202), rough=0.15)
	t("ice", b(j((0.64, 0.80, 0.96), 0.06, 203), (0.80, 0.90, 1.0), 8, 204, (1, 2)), rough=0.2)
	t("red_sand", b(j((0.76, 0.39, 0.20), 0.10, 205), (0.62, 0.30, 0.15), 14, 206), rough=0.95)
	t("terracotta", j((0.56, 0.28, 0.20), 0.05, 207), rough=0.9)
	t("terracotta_light", j((0.80, 0.58, 0.42), 0.05, 208), rough=0.9)
	t("terracotta_dark", j((0.33, 0.20, 0.16), 0.05, 209), rough=0.9)
	t("end_stone", b(j((0.88, 0.87, 0.66), 0.07, 210), (0.76, 0.75, 0.55), 12, 211), rough=0.9)
	t("deepslate", b(j((0.27, 0.27, 0.30), 0.12, 212), (0.20, 0.20, 0.23), 16, 213, (1, 3)), rough=0.85)
	am = j((0.60, 0.40, 0.86), 0.12, 214)
	for k in range(P.T):
		am[k, k] = (0.86, 0.72, 1.0)
		am[k, (P.T - 1 - k) // 2] = (0.78, 0.62, 0.98)
	am[0, :] = am[-1, :] = am[:, 0] = am[:, -1] = (0.42, 0.26, 0.62)
	t("amethyst", am, rough=0.1)
	for name, spot, seed in (("diamond_ore", (0.45, 0.92, 0.90), 215), ("gold_ore", (0.98, 0.84, 0.30), 217), ("coal_ore", (0.10, 0.10, 0.11), 219)):
		o = b(j((0.49, 0.49, 0.50), 0.12, seed), (0.40, 0.40, 0.41), 12, seed + 1, (1, 3))
		o = b(o, spot, 6, seed + 50, (1, 2))
		t(name, o, rough=0.6 if name != "coal_ore" else 0.85)
	t("suit_white", j((0.92, 0.92, 0.94), 0.03, 221), rough=0.5)
	t("netherrack", b(b(j((0.43, 0.14, 0.13), 0.14, 231), (0.32, 0.09, 0.09), 16, 232), (0.55, 0.22, 0.20), 8, 233, (1, 1)), rough=0.9)
	t("blackstone", b(j((0.16, 0.13, 0.15), 0.12, 234), (0.24, 0.20, 0.22), 10, 235, (1, 2)), rough=0.8)
	mg = j((0.36, 0.08, 0.04), 0.15, 236)
	gm = np.random.default_rng(237)
	for _ in range(7):
		x0, y0 = gm.integers(0, P.T, 2)
		for k in range(int(gm.integers(3, 7))):
			mg[(y0 + k) % P.T, (x0 + int(gm.integers(-1, 2)) * k // 2) % P.T] = (1.0, 0.55, 0.12)
	t("magma", mg, rough=0.6)
	t("redstone_ore", b(b(j((0.42, 0.40, 0.41), 0.12, 238), (0.33, 0.31, 0.32), 12, 239, (1, 3)), (0.92, 0.10, 0.08), 7, 240, (1, 2)), rough=0.6)
	rc = j((0.88, 0.24, 0.27), 0.12, 241)
	for k in range(P.T):
		rc[k, k] = (1.0, 0.62, 0.62)
	rc[0, :] = rc[-1, :] = rc[:, 0] = rc[:, -1] = (0.52, 0.10, 0.13)
	t("red_crystal", rc, rough=0.08)
	P.TILE_NAMES[:] = list(P.TILES)
	P.TILE_INDEX.clear()
	P.TILE_INDEX.update({n: i for i, n in enumerate(P.TILE_NAMES)})
	assert len(P.TILE_NAMES) <= P.PER_ROW * P.PER_ROW, len(P.TILE_NAMES)
	P.BLOCKS.update({
		"water": ("water", "water", "water", 0),
		"ice": ("ice", "ice", "ice", 0),
		"red_sand": ("red_sand", "red_sand", "red_sand", 0),
		"terracotta": ("terracotta", "terracotta", "terracotta", 0),
		"terracotta_light": ("terracotta_light", "terracotta_light", "terracotta_light", 0),
		"terracotta_dark": ("terracotta_dark", "terracotta_dark", "terracotta_dark", 0),
		"end_stone": ("end_stone", "end_stone", "end_stone", 0),
		"deepslate": ("deepslate", "deepslate", "deepslate", 0),
		"amethyst": ("amethyst", "amethyst", "amethyst", 2),
		"diamond_ore": ("diamond_ore",) * 3 + (0,),
		"gold_ore": ("gold_ore",) * 3 + (0,),
		"coal_ore": ("coal_ore",) * 3 + (0,),
		"cloud_block": ("white", "white", "white", 0),
		"netherrack": ("netherrack",) * 3 + (0,),
		"blackstone": ("blackstone",) * 3 + (0,),
		"magma": ("magma",) * 3 + (3,),
		"redstone_ore": ("redstone_ore",) * 3 + (0,),
		"red_crystal": ("red_crystal",) * 3 + (2,),
	})


# --------------------------------------------------------------------------------------
# Placement helpers
# --------------------------------------------------------------------------------------

def sky_dir(azimuth_deg, elevation_deg):
	"""Direction from the camera: azimuth from +Y (image centre) clockwise toward +X (image right)."""
	a, e = R(azimuth_deg), R(elevation_deg)
	return Vector((math.sin(a) * math.cos(e), math.cos(a) * math.cos(e), math.sin(e)))


def at(azimuth_deg, elevation_deg, dist):
	return CAM + sky_dir(azimuth_deg, elevation_deg) * dist


def hash3(x, y, z, seed):
	h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (seed * 2654435761)
	h = (h ^ (h >> 13)) * 1274126177
	return ((h ^ (h >> 16)) & 0xFFFFFF) / float(0xFFFFFF)


def noise3(p, scale, seed):
	"""Smooth value noise on a 3D point (trilinear over a hashed lattice)."""
	q = np.asarray(p, float) / scale
	i = np.floor(q).astype(int)
	f = q - i
	f = f * f * (3 - 2 * f)
	v = 0.0
	for dx in (0, 1):
		for dy in (0, 1):
			for dz in (0, 1):
				w = (f[0] if dx else 1 - f[0]) * (f[1] if dy else 1 - f[1]) * (f[2] if dz else 1 - f[2])
				v += w * hash3(int(i[0] + dx), int(i[1] + dy), int(i[2] + dz), seed)
	return v


def fbm3(p, scale, seed, octaves=4):
	v, amp, tot = 0.0, 1.0, 0.0
	for o in range(octaves):
		v += noise3(p, scale / (2 ** o), seed + o * 17) * amp
		tot += amp
		amp *= 0.5
	return v / tot


# --------------------------------------------------------------------------------------
# Planets: spheres of big cells (each cell one block texture stretched over `size` blocks)
# --------------------------------------------------------------------------------------

def cell_mesh(buf, cells, size, centre):
	for (x, y, z), kind in cells.items():
		top, side, bottom, slot = P.BLOCKS[kind]
		for d, corners, ts in P.FACES:
			if (x + d[0], y + d[1], z + d[2]) in cells:
				continue
			name = (top, side, bottom)[ts]
			buf.quad([(centre.x + (x + c[0]) * size, centre.y + (y + c[1]) * size, centre.z + (z + c[2]) * size) for c in corners],
					P.tile_uv(name), slot)


def sphere_cells(r, painter, shell=1.9):
	cells = {}
	n = int(math.ceil(r)) + 1
	for x in range(-n, n + 1):
		for y in range(-n, n + 1):
			for z in range(-n, n + 1):
				d = math.sqrt((x + 0.5) ** 2 + (y + 0.5) ** 2 + (z + 0.5) ** 2)
				if r - shell < d <= r:
					kind = painter(x, y, z, d)
					if kind:
						cells[(x, y, z)] = kind
	return cells


def red_world(x, y, z, d):
	"""The big red planet: dunes and terracotta bands, magma rivers glowing in the cracks, a pale terracotta polar cap."""
	p = (x * 3.1, y * 3.1, z * 3.1)
	lat = abs(z + 0.5) / max(d, 1e-6)
	if lat > 0.84 - 0.08 * fbm3(p, 20, 503):
		return "terracotta_light"
	n = fbm3(p, 30, 511)
	river = abs(fbm3(p, 22, 513) - 0.5)
	if river < 0.025:
		return "magma"
	if n < 0.42:
		return "terracotta_dark"
	if n < 0.55:
		return "red_sand"
	if n < 0.64:
		return "terracotta"
	return "netherrack"


def overworld(x, y, z, d):
	p = (x * 3.1, y * 3.1, z * 3.1)
	lat = abs(z + 0.5) / max(d, 1e-6)
	land = fbm3(p, 34, 501)
	if lat > 0.86 - 0.08 * fbm3(p, 20, 503):
		return "snow" if land > 0.45 else "ice"
	if land < 0.48:
		return "water"
	if land < 0.51:
		return "sand"
	if land > 0.66:
		return "stone" if land < 0.72 else "snow"
	return "grass"


def overworld_clouds(x, y, z, d):
	p = (x * 2.0, y * 2.0, z * 2.0)
	return "cloud_block" if fbm3(p, 14, 521) > 0.60 else None


def mars(x, y, z, d):
	p = (x * 3.0, y * 3.0, z * 3.0)
	band = (z + 0.5) / max(d, 1e-6) + (fbm3(p, 26, 601) - 0.5) * 0.35
	k = int(math.floor(band * 6))
	pick = ("terracotta", "red_sand", "terracotta_light", "red_sand", "terracotta_dark", "red_sand")[k % 6]
	if fbm3(p, 12, 607) > 0.68:
		return "terracotta_dark"
	return pick


def amethyst_moon(x, y, z, d):
	return "red_crystal" if hash3(x, y, z, 701) > 0.22 else "blackstone"


def ice_world(x, y, z, d):
	return "blackstone" if fbm3((x * 3, y * 3, z * 3), 18, 801) > 0.45 else "netherrack"


def ring(buf, centre, inner, outer, tilt, size, seed):
	"""A planet ring of loose cubes, opened toward the camera ({@code tilt} = how open, 0 edge-on .. 1 face-on)."""
	g = np.random.default_rng(seed)
	view = (centre - CAM).normalized()
	u = view.cross(Vector((0, 0, 1))).normalized()
	# tilt = how much of the ring's depth shows (0.3: a flat ellipse three times wider than tall)
	w = (Vector((0, 0, 1)) * tilt - view * math.sqrt(1 - tilt * tilt)).normalized()
	w = (w - u * w.dot(u)).normalized()
	# a slight roll so it isn't level with the horizon
	roll = R(-16)
	u, w = u * math.cos(roll) + w * math.sin(roll), w * math.cos(roll) - u * math.sin(roll)
	count = int((outer ** 2 - inner ** 2) * 0.55)
	kinds = ("terracotta_light", "terracotta", "red_sand", "netherrack", "blackstone")
	for _ in range(count):
		a = g.uniform(0, 2 * math.pi)
		rad = math.sqrt(g.uniform(inner ** 2, outer ** 2))
		if 0.62 < (rad - inner) / (outer - inner) < 0.7:
			continue  # a gap in the ring
		p = u * (math.cos(a) * rad) + w * (math.sin(a) * rad) + view * g.normal(0, 0.35)
		s = size * g.uniform(0.5, 1.2)
		kind = kinds[int(g.integers(0, len(kinds)))]
		top, side, bottom, slot = P.BLOCKS[kind]
		lo = centre + p - Vector((s, s, s)) * 0.5
		for d, corners, ts in P.FACES:
			buf.quad([(lo.x + c[0] * s, lo.y + c[1] * s, lo.z + c[2] * s) for c in corners], P.tile_uv((top, side, bottom)[ts]), slot)


# --------------------------------------------------------------------------------------
# Floating islands and asteroids (block units)
# --------------------------------------------------------------------------------------

ACCENT_LIGHTS = []
WARM_LIGHTS = []
OCCLUDERS = []  # (direction, angular radius deg) of things nothing may float in front of


def island(blocks, plants, cx, cy, ztop, radius, seed, grass=True, deep=1.35, jag=0.25):
	g = np.random.default_rng(seed)
	cx, cy, ztop = int(round(cx)), int(round(cy)), int(round(ztop))
	n = int(radius) + 2
	tops = {}
	for x in range(cx - n, cx + n + 1):
		for y in range(cy - n, cy + n + 1):
			r = math.hypot(x - cx, y - cy) / radius + (hash3(x, y, 0, seed) - 0.5) * jag
			if r > 1:
				continue
			# a smooth cone underneath (neighbouring columns differ by a block at most): a floating rock, not pillars
			depth = int(round(1.5 + (1 - r * r) * radius * deep + (noise3((x * 1.0, y * 1.0, 0.0), 2.5, seed) - 0.5) * 1.4))
			top = ztop
			tops[(x, y)] = top
			for z in range(top - depth, top + 1):
				# red rock: sand on top, banded terracotta, netherrack / blackstone below, glowing magma veins
				if z == top:
					kind = "red_sand"
				else:
					roll = hash3(x, y, z, seed + 3)
					layer = ("terracotta", "terracotta_light", "terracotta", "terracotta_dark")[(top - z) % 4]
					kind = "magma" if roll > 0.975 else "redstone_ore" if roll > 0.955 else (
						layer if z >= top - max(3, depth * 0.5) else "blackstone" if roll > 0.5 else "netherrack")
				blocks[(x, y, z)] = kind
	if False:
		for (x, y), top in tops.items():
			roll = g.random()
			if roll < 0.28 and (x, y, top + 1) not in blocks:
				plants.append((x, y, top + 1, "tallgrass"))
			elif roll < 0.31 and (x, y, top + 1) not in blocks:
				plants.append((x, y, top + 1, ("poppy", "dandelion", "cornflower")[int(g.integers(0, 3))]))
	return tops


def plateau(blocks, cx, cy, ztop, half, seed):
	"""A square rock (seen corner-on it shows two clean faces, no stair-step cracks) with an
	uneven underside narrowing to a point."""
	cx, cy, ztop = int(round(cx)), int(round(cy)), int(round(ztop))
	tops = {}
	for x in range(cx - half, cx + half + 1):
		for y in range(cy - half, cy + half + 1):
			dx, dy = abs(x - cx), abs(y - cy)
			c = max(dx, dy) / (half + 0.5)
			depth = int(round(2 + (1 - c) * half * 1.6 + (hash3(x, y, 2, seed) - 0.5) * 1.5))
			tops[(x, y)] = ztop
			for z in range(ztop - depth, ztop + 1):
				if z == ztop:
					kind = "red_sand"
				else:
					roll = hash3(x, y, z, seed + 3)
					layer = ("terracotta", "terracotta_light", "terracotta", "terracotta_dark")[(ztop - z) % 4]
					kind = "magma" if roll > 0.975 else "redstone_ore" if roll > 0.955 else (
						layer if z >= ztop - 3 else "blackstone" if roll > 0.5 else "netherrack")
				blocks[(x, y, z)] = kind
	return tops


def asteroid(blocks, c, rad, seed):
	g = np.random.default_rng(seed)
	n = int(rad) + 1
	ax = g.uniform(0.6, 1.3, 3)
	for x in range(-n, n + 1):
		for y in range(-n, n + 1):
			for z in range(-n, n + 1):
				d = math.sqrt((x / ax[0]) ** 2 + (y / ax[1]) ** 2 + (z / ax[2]) ** 2)
				if d > rad * (0.75 + 0.45 * hash3(x, y, z, seed)):
					continue
				roll = hash3(x, y, z, seed + 9)
				kind = "magma" if roll > 0.975 else "redstone_ore" if roll > 0.94 else "terracotta_dark" if roll > 0.8 else "blackstone" if roll > 0.55 else "netherrack"
				blocks[(int(c[0]) + x, int(c[1]) + y, int(c[2]) + z)] = kind


def blocked(direction, margin=1.5):
	for d, rad in OCCLUDERS:
		if math.degrees(direction.angle(d)) < rad + margin:
			return True
	return False


# --------------------------------------------------------------------------------------
# The astronaut (1/16 voxels, same rig as the duel fighters)
# --------------------------------------------------------------------------------------

WHITE = (0.93, 0.93, 0.95)
WHITE2 = (0.82, 0.83, 0.86)
GREY = (0.40, 0.42, 0.46)
DARKGREY = (0.20, 0.21, 0.24)
RED = (0.86, 0.14, 0.20)
VISOR = (0.03, 0.04, 0.07)
SUIT = (0.0, 0.55, 0.0)
METAL = (0.9, 0.25, 0.0)


def suit_painters():
	g = np.random.default_rng(42)
	sh = lambda c, k=0.03: P.shade(c, g, k)

	def head(x, y, z, w, d, h):
		front = y == d - 1
		# big helmet visor wrapping the front, glossy and dark (it reflects the planets)
		if front and 1 <= x <= 6 and 1 <= z <= 6:
			if x == 6 and z == 6:
				return ((0.55, 0.65, 0.75), (1.0, 0.02, 0.0))  # a highlight glint
			return (VISOR, (1.0, 0.04, 0.0))
		if (x in (0, w - 1)) and y >= d - 3 and 2 <= z <= 5:
			return (VISOR, (1.0, 0.04, 0.0))
		if z == h - 1 and 3 <= x <= 4:
			return (sh(RED), SUIT)
		if x == 0 and y == 2 and z == 5:
			return ((0.95, 0.95, 1.0), (0.0, 0.3, 4.0))  # helmet lamp (glows in the theme colour)
		if z <= 0:
			return (sh(GREY), METAL)
		return (sh(WHITE), SUIT)

	def body(x, y, z, w, d, h):
		front = y == d - 1
		if z <= 1:
			return (sh(GREY), METAL)
		if front and 2 <= x <= 5 and 5 <= z <= 9:
			if z == 8 and x in (2, 3):
				return ((0.9, 0.95, 1.0), (0.0, 0.3, 3.0))  # suit lights
			if z == 8 and x == 5:
				return (RED, (0.0, 0.4, 0.0))
			return (sh(DARKGREY), METAL)
		if front and x in (0, 1) and z >= 9:
			return (sh(RED), SUIT)
		if z == h - 1:
			return (sh(WHITE2), SUIT)
		return (sh(WHITE), SUIT)

	def arm(x, y, z, w, d, h):
		if z <= 2:
			return (sh(GREY), METAL)  # gloves
		if z == 3:
			return (sh(DARKGREY), METAL)
		if z in (h - 4, h - 5):
			return (sh(RED), SUIT)
		return (sh(WHITE), SUIT)

	def leg(x, y, z, w, d, h):
		if z <= 2:
			return (sh(GREY), METAL)  # boots
		if z == 3:
			return (sh(DARKGREY), METAL)
		if z == 6 and y == d - 1:
			return (sh(RED), SUIT)
		return (sh(WHITE), SUIT)

	def pack(x, y, z, w, d, h):
		if z <= 1 and x in (1, 4) and y == 1:
			return ((1.0, 0.95, 0.9), (0.0, 0.3, 6.0))  # thruster glow
		if z <= 1 and x not in (1, 4):
			return None
		if y == 0 and 1 <= x <= 4 and z in (5, 6):
			return (sh(RED), SUIT)
		if z == h - 1 or x in (0, w - 1):
			return (sh(GREY), METAL)
		return (sh(WHITE2), SUIT)

	return head, body, arm, leg, pack


class Astronaut:
	def __init__(self, name, pos, yaw, mats):
		head_p, body_p, arm_p, leg_p, pack_p = suit_painters()
		u = P.PX
		root = bpy.data.objects.new(name, None)
		bpy.context.scene.collection.objects.link(root)
		root.location = pos
		root.rotation_euler = (0, 0, yaw)
		self.root = root
		self.base = Vector(pos)
		self.yaw = yaw
		self.body = P.voxel_part(name + "_body", (8, 4, 12), (-4, -2, 0), body_p, mats, root, (0, 0, 12 * u))
		self.head = P.voxel_part(name + "_head", (8, 8, 8), (-4, -4, 0), head_p, mats, self.body, (0, 0, 12 * u))
		self.arm_r = P.voxel_part(name + "_arm_r", (4, 4, 12), (-2, -2, -10), arm_p, mats, self.body, (6 * u, 0, 10 * u))
		self.arm_l = P.voxel_part(name + "_arm_l", (4, 4, 12), (-2, -2, -10), arm_p, mats, self.body, (-6 * u, 0, 10 * u))
		self.leg_r = P.voxel_part(name + "_leg_r", (4, 4, 12), (-2, -2, -12), leg_p, mats, root, (2 * u, 0, 12 * u))
		self.leg_l = P.voxel_part(name + "_leg_l", (4, 4, 12), (-2, -2, -12), leg_p, mats, root, (-2 * u, 0, 12 * u))
		# jetpack on the back
		self.pack = P.voxel_part(name + "_pack", (6, 3, 9), (-3, -3, 0), pack_p, mats, self.body, (0, -2 * u, 2 * u))

	def pose(self, t):
		"""2026-11-26 (owner: too fast, "just raising and lowering hands on repeat"): a calm zero-g drift. One slow full roll
		per loop around an axis that itself wobbles a little (so it never reads as a plain spin), a figure-8 drift, the
		head looking around, and arms and legs drifting on their own, out of step with each other. Only whole harmonics of
		the loop, so frame N-1 flows into frame 0."""
		tau = 2 * math.pi * t
		base_q = Euler((R(-18), R(10), self.yaw + R(-10))).to_quaternion()
		pivot_local = Vector((0, 0, 1.0))  # the middle of the figure (32 px tall)
		pivot = self.base + base_q @ pivot_local
		axis = (CAM - pivot).normalized() + Vector((0.18 * math.sin(tau + 0.6), 0.12 * math.cos(tau), 0.45 + 0.1 * math.sin(2 * tau)))
		axis.normalize()
		q = Quaternion(axis, -tau) @ base_q
		self.root.rotation_mode = "QUATERNION"
		self.root.rotation_quaternion = q
		drift = Vector((0.05 * math.sin(tau), 0.03 * math.sin(2 * tau + 0.4), 0.06 * math.sin(tau + 1.1)))
		self.root.location = pivot + drift - q @ pivot_local
		s1, s2, s3 = math.sin, math.sin, math.sin
		self.body.rotation_euler = (R(-6 + 4 * s1(tau + 0.8) + 2 * s2(3 * tau)), R(3 * s1(2 * tau + 1.3)), R(5 * s1(tau)))
		self.head.rotation_euler = (R(8 + 7 * s1(2 * tau + 1.0)), R(4 * s1(tau + 2.1)), R(14 * s1(tau + 0.3) + 6 * s3(3 * tau + 0.9)))
		# arms float loosely and never mirror each other
		self.arm_r.rotation_euler = (R(105 + 24 * s1(tau + 0.4) + 9 * s3(3 * tau + 1.1)), R(-24 + 12 * s1(2 * tau + 0.7)), R(-10 + 5 * s1(tau + 2.0)))
		self.arm_l.rotation_euler = (R(78 + 20 * s1(tau + 2.3) + 8 * s2(2 * tau + 0.3)), R(26 - 10 * s1(3 * tau + 2.0)), R(9 + 5 * s1(2 * tau + 0.9)))
		# legs drift apart and together slowly, a little bent
		self.leg_r.rotation_euler = (R(16 + 13 * s1(tau + 1.0) + 5 * s3(3 * tau)), R(4 * s1(2 * tau + 0.5)), R(-9 + 4 * s1(tau + 2.4)))
		self.leg_l.rotation_euler = (R(4 + 11 * s1(tau + 2.7) + 5 * s2(2 * tau + 1.5)), R(-4 * s1(tau + 1.2)), R(9 + 4 * s1(2 * tau + 0.2)))


# --------------------------------------------------------------------------------------
# Sky, compositor, lights
# --------------------------------------------------------------------------------------

def space_world():
	sc = bpy.context.scene
	w = bpy.data.worlds.new("space")
	sc.world = w
	w.use_nodes = True
	nt = w.node_tree
	bg = nt.nodes["Background"]
	if ACCENT:
		bg.inputs["Color"].default_value = (0, 0, 0, 1)
		bg.inputs["Strength"].default_value = 0.0
		return
	tc = nt.nodes.new("ShaderNodeTexCoord")
	nodes, links = nt.nodes, nt.links

	def stars(scale, size, gain, seed_offset):
		vor = nodes.new("ShaderNodeTexVoronoi")
		vor.voronoi_dimensions = "3D"
		vor.feature = "F1"
		vor.inputs["Scale"].default_value = scale
		vor.inputs["Randomness"].default_value = 1.0
		add = nodes.new("ShaderNodeVectorMath")
		add.operation = "ADD"
		add.inputs[1].default_value = (seed_offset, seed_offset * 0.7, seed_offset * 1.3)
		links.new(tc.outputs["Generated"], add.inputs[0])
		links.new(add.outputs[0], vor.inputs["Vector"])
		# a point where the cell centre is: 1 - dist/size, clipped, sharpened
		m1 = nodes.new("ShaderNodeMath")
		m1.operation = "DIVIDE"
		m1.inputs[1].default_value = size
		links.new(vor.outputs["Distance"], m1.inputs[0])
		m2 = nodes.new("ShaderNodeMath")
		m2.operation = "SUBTRACT"
		m2.use_clamp = True
		m2.inputs[0].default_value = 1.0
		links.new(m1.outputs[0], m2.inputs[1])
		m3 = nodes.new("ShaderNodeMath")
		m3.operation = "POWER"
		m3.inputs[1].default_value = 3.0
		links.new(m2.outputs[0], m3.inputs[0])
		# only some cells hold a (bright enough) star
		sep = nodes.new("ShaderNodeSeparateColor")
		links.new(vor.outputs["Color"], sep.inputs["Color"])
		p = nodes.new("ShaderNodeMath")
		p.operation = "POWER"
		p.inputs[1].default_value = 6.0
		links.new(sep.outputs["Red"], p.inputs[0])
		mul = nodes.new("ShaderNodeMath")
		mul.operation = "MULTIPLY"
		links.new(m3.outputs[0], mul.inputs[0])
		links.new(p.outputs[0], mul.inputs[1])
		g = nodes.new("ShaderNodeMath")
		g.operation = "MULTIPLY"
		g.inputs[1].default_value = gain
		links.new(mul.outputs[0], g.inputs[0])
		return g, sep

	small, _ = stars(520, 0.07, 110.0, 0.0)
	big, sep = stars(160, 0.06, 260.0, 3.7)
	sum_ = nodes.new("ShaderNodeMath")
	sum_.operation = "ADD"
	links.new(small.outputs[0], sum_.inputs[0])
	links.new(big.outputs[0], sum_.inputs[1])
	# star tint: blue-white to warm, by the cell's hash
	tint = nodes.new("ShaderNodeValToRGB")
	tint.color_ramp.elements[0].color = (1.0, 0.92, 0.88, 1)
	tint.color_ramp.elements[1].color = (1.0, 0.78, 0.62, 1)
	links.new(sep.outputs["Green"], tint.inputs["Fac"])
	star_col = nodes.new("ShaderNodeMixRGB")
	star_col.blend_type = "MULTIPLY"
	star_col.inputs["Fac"].default_value = 1.0
	links.new(tint.outputs["Color"], star_col.inputs["Color1"])
	links.new(sum_.outputs[0], star_col.inputs["Color2"])
	# nebula: soft fbm clouds in violet / magenta / teal, strongest along a tilted band
	neb = nodes.new("ShaderNodeTexNoise")
	neb.noise_dimensions = "3D"
	neb.inputs["Scale"].default_value = 1.8
	neb.inputs["Detail"].default_value = 9.0
	neb.inputs["Roughness"].default_value = 0.62
	links.new(tc.outputs["Generated"], neb.inputs["Vector"])
	ramp = nodes.new("ShaderNodeValToRGB")
	cr = ramp.color_ramp
	cr.elements[0].position = 0.42
	cr.elements[0].color = (0, 0, 0, 1)
	cr.elements[1].position = 0.78
	cr.elements[1].color = (0.50, 0.06, 0.05, 1)
	e = cr.elements.new(0.62)
	e.color = (0.16, 0.02, 0.03, 1)
	e = cr.elements.new(0.92)
	e.color = (1.0, 0.42, 0.18, 1)
	links.new(neb.outputs["Fac"], ramp.inputs["Fac"])
	band_dot = nodes.new("ShaderNodeVectorMath")
	band_dot.operation = "DOT_PRODUCT"
	band_dot.inputs[1].default_value = (0.25, -0.35, 0.90)
	norm = nodes.new("ShaderNodeVectorMath")
	norm.operation = "NORMALIZE"
	sub = nodes.new("ShaderNodeVectorMath")
	sub.operation = "SUBTRACT"
	sub.inputs[1].default_value = (0.5, 0.5, 0.5)
	links.new(tc.outputs["Generated"], sub.inputs[0])
	links.new(sub.outputs[0], norm.inputs[0])
	links.new(norm.outputs[0], band_dot.inputs[0])
	band = nodes.new("ShaderNodeMapRange")
	band.inputs["From Min"].default_value = -0.55
	band.inputs["From Max"].default_value = 0.0
	band.inputs["To Min"].default_value = 0.0
	band.inputs["To Max"].default_value = 1.0
	links.new(band_dot.outputs["Value"], band.inputs["Value"])
	band2 = nodes.new("ShaderNodeMapRange")
	band2.inputs["From Min"].default_value = 0.55
	band2.inputs["From Max"].default_value = 0.0
	band2.inputs["To Min"].default_value = 0.0
	band2.inputs["To Max"].default_value = 1.0
	links.new(band_dot.outputs["Value"], band2.inputs["Value"])
	bandm = nodes.new("ShaderNodeMath")
	bandm.operation = "MINIMUM"
	links.new(band.outputs["Result"], bandm.inputs[0])
	links.new(band2.outputs["Result"], bandm.inputs[1])
	neb_mask = nodes.new("ShaderNodeMath")
	neb_mask.operation = "MULTIPLY_ADD"
	neb_mask.inputs[1].default_value = 0.85
	neb_mask.inputs[2].default_value = 0.18
	links.new(bandm.outputs[0], neb_mask.inputs[0])
	neb_col = nodes.new("ShaderNodeMixRGB")
	neb_col.blend_type = "MULTIPLY"
	neb_col.inputs["Fac"].default_value = 1.0
	links.new(ramp.outputs["Color"], neb_col.inputs["Color1"])
	links.new(neb_mask.outputs[0], neb_col.inputs["Color2"])
	neb_gain = nodes.new("ShaderNodeMixRGB")
	neb_gain.blend_type = "MULTIPLY"
	neb_gain.inputs["Fac"].default_value = 1.0
	neb_gain.inputs["Color2"].default_value = (0.32, 0.32, 0.32, 1)
	links.new(neb_col.outputs["Color"], neb_gain.inputs["Color1"])
	total = nodes.new("ShaderNodeMixRGB")
	total.blend_type = "ADD"
	total.inputs["Fac"].default_value = 1.0
	links.new(neb_gain.outputs["Color"], total.inputs["Color1"])
	links.new(star_col.outputs["Color"], total.inputs["Color2"])
	base = nodes.new("ShaderNodeMixRGB")
	base.blend_type = "ADD"
	base.inputs["Fac"].default_value = 1.0
	base.inputs["Color2"].default_value = (0.010, 0.003, 0.004, 1)
	links.new(total.outputs["Color"], base.inputs["Color1"])
	links.new(base.outputs["Color"], bg.inputs["Color"])
	bg.inputs["Strength"].default_value = 1.0
	# keep the sky out of indirect light (only the sun lights the scene)
	lp = nodes.new("ShaderNodeLightPath")
	mixs = nodes.new("ShaderNodeMixShader")
	dark = nodes.new("ShaderNodeBackground")
	dark.inputs["Color"].default_value = (0.020, 0.008, 0.008, 1)
	dark.inputs["Strength"].default_value = 1.0
	out = nodes["World Output"]
	links.new(lp.outputs["Is Camera Ray"], mixs.inputs["Fac"])
	links.new(dark.outputs[0], mixs.inputs[1])
	links.new(bg.outputs[0], mixs.inputs[2])
	links.new(mixs.outputs[0], out.inputs["Surface"])


def compositor():
	sc = bpy.context.scene
	sc.use_nodes = True
	nt = sc.node_tree
	for n in list(nt.nodes):
		nt.nodes.remove(n)
	rl = nt.nodes.new("CompositorNodeRLayers")
	comp = nt.nodes.new("CompositorNodeComposite")
	if ACCENT:
		nt.links.new(rl.outputs["Image"], comp.inputs["Image"])
		return
	glare = nt.nodes.new("CompositorNodeGlare")
	glare.glare_type = "FOG_GLOW"
	glare.quality = "HIGH"
	glare.threshold = 1.2
	glare.size = 7
	try:
		glare.mix = -0.55
	except AttributeError:
		pass
	nt.links.new(rl.outputs["Image"], glare.inputs["Image"])
	nt.links.new(glare.outputs["Image"], comp.inputs["Image"])


SUN_AZ = -62.0
SUN_EL = 21.0


def lights(mats):
	sc = bpy.context.scene
	if not ACCENT:
		d = sky_dir(SUN_AZ, SUN_EL)
		sun = bpy.data.lights.new("sun", "SUN")
		sun.energy = 4.0
		sun.color = (1.0, 0.93, 0.85)
		sun.angle = R(0.6)
		ob = bpy.data.objects.new("sun", sun)
		sc.collection.objects.link(ob)
		ob.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
		# key light from behind the camera, so the islands and players aren't silhouettes against the sun
		dk = sky_dir(175, 28)
		key = bpy.data.lights.new("key", "SUN")
		key.energy = 3.2
		key.color = (1.0, 0.9, 0.82)
		key.angle = R(3)
		obk = bpy.data.objects.new("key", key)
		sc.collection.objects.link(obk)
		obk.rotation_euler = (-dk).to_track_quat("-Z", "Y").to_euler()
		# cool bounce from the Overworld on the far side
		d2 = sky_dir(35, 10)
		fill = bpy.data.lights.new("planetshine", "SUN")
		fill.energy = 0.9
		fill.color = (1.0, 0.42, 0.30)
		fill.angle = R(12)
		ob2 = bpy.data.objects.new("planetshine", fill)
		sc.collection.objects.link(ob2)
		ob2.rotation_euler = (-d2).to_track_quat("-Z", "Y").to_euler()
		# the sun's disc itself
		disc = bpy.data.meshes.new("sundisc")
		buf = P.MeshBuf()
		c = CAM + d * 900
		s = 14
		for dv, corners, _ in P.FACES:
			buf.quad([(c.x + (cc[0] - 0.5) * s, c.y + (cc[1] - 0.5) * s, c.z + (cc[2] - 0.5) * s) for cc in corners], P.tile_uv("white"), 0)
		m, nt, out = P.new_mat("sun")
		em = nt.nodes.new("ShaderNodeEmission")
		em.inputs["Color"].default_value = (1.0, 0.95, 0.85, 1)
		em.inputs["Strength"].default_value = 40.0
		nt.links.new(em.outputs[0], out.inputs[0])
		buf.build("sundisc", [m])
		for i, (p, watts, rad) in enumerate(WARM_LIGHTS):
			l = bpy.data.lights.new(f"warm{i}", "POINT")
			l.energy = watts
			l.color = (1.0, 0.6, 0.3)
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


# --------------------------------------------------------------------------------------
# Scene
# --------------------------------------------------------------------------------------

HERO_AZ, HERO_EL, HERO_DIST = 0.0, -2.0, 4.3
SITTER_AZ = 50.0


def build_space(mats):
	big = P.MeshBuf()
	# --- planets
	planets = [
		(35, 11, 520, 26, 3.0, red_world, "redworld"),
		(-112, 6, 470, 17, 3.0, mars, "mars"),
		(152, 15, 300, 10, 2.0, amethyst_moon, "moon"),
		(-168, 3, 640, 6, 3.0, ice_world, "ice"),
	]
	for az, el, dist, r, size, painter, name in planets:
		c = at(az, el, dist)
		cells = sphere_cells(r, painter)
		cell_mesh(big, cells, size, c - Vector((0.5, 0.5, 0.5)) * size)
		OCCLUDERS.append((sky_dir(az, el), math.degrees(math.atan(r * size / dist))))
		if name == "redworld":
			WARM_LIGHTS.append((tuple(c - sky_dir(az, el) * (r * size + 6)), 0, 1.0))
		if name == "mars":
			ring(big, c, r * size * 1.55, r * size * 2.35, 0.32, size * 0.9, 77)
			OCCLUDERS.append((sky_dir(az, el), math.degrees(math.atan(r * size * 2.35 / dist))))
		if name == "moon":
			ACCENT_LIGHTS.append((tuple(c), 0, 1.0))  # (emission does the work; kept for symmetry)
	big.build("planets", mats)

	# --- islands and asteroids
	blocks = {}
	plants = []
	# the seated player's island: right of the astronaut and close, so they're easy to see
	# a square rock seen corner-on; the player sits in the middle of its LEFT face (toward the big red planet's
	# side of the sky), legs over the edge, so we see them three-quarter from the front
	ic = at(SITTER_AZ, -6, 9.0)
	tops = plateau(blocks, ic.x, ic.y, ic.z - 1, 2, 37)
	away = ic - CAM
	away.z = 0
	away.normalize()
	left = Vector((away.y, -away.x, 0)) if sky_dir(SITTER_AZ - 10, 0).dot(Vector((away.y, -away.x, 0))) > 0 else Vector((-away.y, away.x, 0))
	axes = [Vector((1, 0, 0)), Vector((-1, 0, 0)), Vector((0, 1, 0)), Vector((0, -1, 0))]
	toc = max((n for n in axes if n.dot(away) < 0), key=lambda n: n.dot(left))
	cxi, cyi = int(round(ic.x)), int(round(ic.y))
	ex, ey = cxi + int(toc.x) * 2, cyi + int(toc.y) * 2
	# kneeling on the rock's top, one block in from the centre toward the planet
	tp = sky_dir(35, 0)
	tp.z = 0
	tp.normalize()
	sit = Vector((cxi + 0.5 + tp.x * 0.8, cyi + 0.5 + tp.y * 0.8, tops[(cxi, cyi)] + 1))
	# a plain rock where the sitter used to be
	old = at(-40, -6, 19.0)
	island(blocks, plants, old.x, old.y, old.z - 1, 2.6, 33)
	far1 = at(78, 2, 28)
	island(blocks, plants, far1.x, far1.y, far1.z, 4.5, 41)
	far2 = at(-150, -4, 34)
	island(blocks, plants, far2.x, far2.y, far2.z, 4.5, 51, grass=False)
	far3 = at(118, -10, 30)
	island(blocks, plants, far3.x, far3.y, far3.z, 3.5, 61)

	g = np.random.default_rng(13)
	placed = 0
	while placed < 30:
		az = g.uniform(-180, 180)
		el = g.uniform(-28, 24)
		dist = g.uniform(20, 170)
		if abs(az - HERO_AZ) < 22 and dist < 40:
			continue
		if abs(az - SITTER_AZ) < 14 and dist < 30:
			continue
		dvec = sky_dir(az, el)
		if blocked(dvec, 1.0):
			continue
		rad = g.uniform(0.7, 1.3) + (dist / 170) * g.uniform(0.5, 4.0)
		asteroid(blocks, CAM + dvec * dist, rad, int(g.integers(1000, 9999)))
		placed += 1

	buf = P.MeshBuf()
	P.mesh_blocks(blocks, buf)
	gp = np.random.default_rng(99)
	for (x, y, z, name) in plants:
		P.cross_plant(buf, x, y, z, name, gp)
	buf.build("space", mats)
	return sit, toc


def neon_cape(fighter):
	"""Swaps the fighter's made-up voxel cape for the real Velo Neon cloak (outer face from the store art, the
	brightest frame of its pulse; velo_neon_cape.png is cut from server/cosmetics-seed/capes/velo_neon.gif)."""
	old = fighter.cape
	old.hide_render = True
	u = P.PX
	x0, x1, y0, y1, z0, z1 = -5 * u, 5 * u, -1 * u, 0.0, -16 * u, 0.0
	v = [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)]
	# the outer face (-y, the side you see from behind the player) first; the rest are dark edges
	faces = [(0, 1, 2, 3), (5, 4, 7, 6), (4, 0, 3, 7), (1, 5, 6, 2), (3, 2, 6, 7), (4, 5, 1, 0)]
	me = bpy.data.meshes.new("velo_neon_cape")
	me.from_pydata(v, [], faces)
	uv = me.uv_layers.new(name="UVMap")
	for poly in me.polygons:
		for k, li in enumerate(poly.loop_indices):
			uv.data[li].uv = [(0, 0), (1, 0), (1, 1), (0, 1)][k] if poly.index == 0 else (0.01, 0.99)
		poly.material_index = 0 if poly.index == 0 else 1
	img = bpy.data.images.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), "velo_neon_cape.png"))
	img.colorspace_settings.name = "sRGB"
	m = bpy.data.materials.new("velo_neon")
	m.use_nodes = True
	nt = m.node_tree
	b = nt.nodes["Principled BSDF"]
	tex = nt.nodes.new("ShaderNodeTexImage")
	tex.image = img
	tex.interpolation = "Linear"
	nt.links.new(tex.outputs["Color"], b.inputs["Base Color"])
	nt.links.new(tex.outputs["Color"], b.inputs["Emission Color"])
	b.inputs["Emission Strength"].default_value = 2.2 if ACCENT else 1.2
	b.inputs["Roughness"].default_value = 0.35
	edge = bpy.data.materials.new("velo_neon_edge")
	edge.use_nodes = True
	eb = edge.node_tree.nodes["Principled BSDF"]
	eb.inputs["Base Color"].default_value = (0.03, 0.03, 0.035, 1)
	if ACCENT:
		eb.inputs["Base Color"].default_value = (0, 0, 0, 1)
	me.materials.append(m)
	me.materials.append(edge)
	ob = bpy.data.objects.new("velo_neon_cape", me)
	bpy.context.scene.collection.objects.link(ob)
	ob.parent = old.parent
	ob.location = old.location
	ob.rotation_euler = old.rotation_euler
	return ob


def main():
	bpy.ops.wm.read_factory_settings(use_empty=True)
	add_tiles()
	atlas, rough = P.build_atlas()
	mats = P.make_materials(atlas, rough)
	P.setup_render()
	sc = bpy.context.scene
	if not ACCENT:
		sc.view_settings.exposure = 0.55
	space_world()
	compositor()
	P.add_camera()
	sit, toc = build_space(mats)
	vmat = P.voxel_material()

	t = (ARGS.frame % ARGS.frames) / ARGS.frames
	hero_pos = at(HERO_AZ, HERO_EL, HERO_DIST) - Vector((0, 0, 0.95))
	hero = Astronaut("astronaut", hero_pos, R(200), [vmat])
	hero.pose(t)
	ACCENT_LIGHTS.append((tuple(hero_pos + Vector((0, -0.35, 0.95))), 14, 0.08))

	# (the rig faces local +y, positive nod looks up)
	# the original pose: kneeling (legs folded back under), back to the camera, watching the big red planet
	look = sky_dir(35, 11)
	root_pos = Vector((sit.x, sit.y, sit.z - 0.55))
	sitter = P.Fighter("knight", tuple(root_pos), math.atan2(-look.x, look.y), [vmat])
	sitter.root_x0 = root_pos.x
	sitter.base_z = root_pos.z
	sitter.pose({"legr": -88, "legl": -82, "rp": 18, "lp": 12, "lr": -6, "rr": 6, "nod": 10, "cape": 4})
	sitter.sword.hide_render = True
	neon_cape(sitter)
	sitter.root.location = root_pos

	lights(mats)
	sc.render.filepath = ARGS.out
	bpy.ops.render.render(write_still=True)
	print("[space] wrote", ARGS.out)


main()
