#!/usr/bin/env python3
"""Real-3D animated capes: each cape is a GLSL scene (capes/<id>.glsl) ray-marched on the GPU - extruded Velo logo,
metal chains, glass, lit and reflecting - supersampled, bloomed and tonemapped, then laid into the vanilla cape
layout (64 x 32 cape pixels at 32 px each = 2048 x 1024; the game and launcher crop and scale any 2:1 layout).

Every face gets its own art: the outside, the inside (seen when the cape swings), both side edges and the top and
bottom edges - not a dimmed copy and flat colour.

    pip install moderngl scipy numpy pillow
    python3 tools/cosmetics/cape3d/render_cape3d.py <id> [--preview-only] [--frames N]

Writes server/cosmetics-seed/capes/<id>.gif and build/cape-previews/<id>-*.png/.gif (flat faces + a 3D turntable).
"""
import argparse
import importlib.util
import math
import re
import sys
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / "server/cosmetics-seed/capes"
PREVIEW = ROOT / "build/cape-previews"

PX = 32          # texture pixels per cape pixel (2048 x 1024 cape texture: the outside face is 320 x 512)
SS = 3           # supersampling per axis
FRAMES = 40
DELAY_MS = 60

# (face id, cape-pixel box in the 64x32 layout, size in cape units)
FACES = [
	(0, (1, 1, 11, 17), (10, 16)),    # outside
	(1, (12, 1, 22, 17), (10, 16)),   # inside
	(2, (0, 1, 1, 17), (1, 16)),      # left edge
	(3, (11, 1, 12, 17), (1, 16)),    # right edge
	(4, (1, 0, 11, 1), (10, 1)),      # top edge
	(5, (11, 0, 21, 1), (10, 1)),     # bottom edge
]

VERT = """
#version 330
in vec2 pos;
void main() { gl_Position = vec4(pos, 0.0, 1.0); }
"""


def logo_sdf(size=1024, margin=0.12, path=None):
	"""Signed distance to the logo's silhouette, in logo widths, over a square with {margin} around the logo."""
	logo = Image.open(path or ROOT / "Logo.png").convert("RGBA")
	inner = int(size / (1 + 2 * margin))
	alpha = np.asarray(logo.resize((inner, inner), Image.LANCZOS), dtype=float)[:, :, 3] / 255
	canvas = np.zeros((size, size))
	o = (size - inner) // 2
	canvas[o:o + inner, o:o + inner] = alpha
	canvas = ndimage.gaussian_filter(canvas, 1.2)  # soften the PNG's stair-steps before thresholding
	inside = canvas > 0.5
	d = ndimage.distance_transform_edt(~inside) - ndimage.distance_transform_edt(inside)
	return (d / inner).astype("f4")


TEXT_W, TEXT_H = 2048, 256


def text_sdf(text="VELO CLIENT", spacing=0.32):
	"""Signed distance to a spaced-out wordmark in Sora Bold, in units of the texture's width (centred, fits 90%)."""
	font = ImageFont.truetype(str(ROOT / "src/client/resources/assets/velo-client/font/sora_bold.ttf"), 150)
	widths = [font.getlength(ch) for ch in text]
	gap = 150 * spacing
	total = sum(widths) + gap * (len(text) - 1)
	img = Image.new("L", (TEXT_W, TEXT_H), 0)
	draw = ImageDraw.Draw(img)
	x = (TEXT_W - total) / 2
	for ch, w in zip(text, widths):
		draw.text((x, TEXT_H / 2), ch, font=font, fill=255, anchor="lm")
		x += w + gap
	a = ndimage.gaussian_filter(np.asarray(img, dtype=float) / 255, 0.8)
	inside = a > 0.5
	d = ndimage.distance_transform_edt(~inside) - ndimage.distance_transform_edt(inside)
	return (d / TEXT_W).astype("f4")


# Blocky pixel glyphs (Minecraft-title style) for scenes that set `// @pixeltext "WORD" "WORD2"`.
PIXEL_GLYPHS = {
	"L": ["XX....", "XX....", "XX....", "XX....", "XX....", "XXXXXX", "XXXXXX"],
	"U": ["XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XXXXXX", ".XXXX."],
	"C": [".XXXXX", "XXXXXX", "XX....", "XX....", "XX....", "XXXXXX", ".XXXXX"],
	"K": ["XX..XX", "XX.XXX", "XXXXX.", "XXXX..", "XXXXX.", "XX.XXX", "XX..XX"],
	"Y": ["XX..XX", "XX..XX", "XXXXXX", ".XXXX.", "..XX..", "..XX..", "..XX.."],
	"S": [".XXXXX", "XXXXXX", "XX....", "XXXXX.", ".XXXXX", "....XX", "XXXXX."],
	"M": ["XX...XX", "XXX.XXX", "XXXXXXX", "XX.X.XX", "XX...XX", "XX...XX", "XX...XX"],
	"P": ["XXXXX.", "XXXXXX", "XX..XX", "XXXXXX", "XXXXX.", "XX....", "XX...."],
}


def pixel_text_sdf(text, cell=29):
	"""Signed distance to a word in PIXEL_GLYPHS (one blank column between letters), centred in a TEXT_W x TEXT_H box,
	in units of the box width - the same mapping as text_sdf, so text2()-style sampling works."""
	cols = []
	for i, ch in enumerate(text):
		g = PIXEL_GLYPHS[ch]
		if i:
			cols.append(["."] * 7)
		for x in range(len(g[0])):
			cols.append([row[x] for row in g])
	w, h = len(cols) * cell, 7 * cell
	img = Image.new("L", (TEXT_W, TEXT_H), 0)
	draw = ImageDraw.Draw(img)
	x0, y0 = (TEXT_W - w) // 2, (TEXT_H - h) // 2
	for cx, col in enumerate(cols):
		for cy, v in enumerate(col):
			if v == "X":
				draw.rectangle((x0 + cx * cell, y0 + cy * cell, x0 + (cx + 1) * cell - 1, y0 + (cy + 1) * cell - 1), fill=255)
	inside = np.asarray(img) > 127
	d = ndimage.distance_transform_edt(~inside) - ndimage.distance_transform_edt(inside)
	return (d / TEXT_W).astype("f4")


class Renderer:
	def __init__(self, cape_id):
		self.ctx = moderngl.create_standalone_context(backend="egl")
		# a cape id, or a path to a .glsl scene kept outside the repo (private one-offs)
		scene_path = Path(cape_id) if cape_id.endswith(".glsl") else HERE / "capes" / f"{cape_id}.glsl"
		scene = scene_path.read_text()
		# `// @logo "file.png"` swaps the Velo logo for another picture (its alpha is the silhouette), relative to the scene
		lm = re.search(r'//\s*@logo\s+"([^"]+)"', scene)
		logo_path = (scene_path.parent / lm.group(1)) if lm else None
		# `#include "kit.glsl"` pulls in a shared file (only one level, enough for the kit).
		scene = re.sub(r'^#include "([^"]+)"\s*$', lambda m: (HERE / m.group(1)).read_text(), scene, flags=re.M)
		src = (HERE / "common.glsl").read_text() + "\n" + scene
		self.prog = self.ctx.program(vertex_shader=VERT, fragment_shader=src)
		quad = np.array([-1, -1, 1, -1, -1, 1, 1, 1], dtype="f4")
		self.vao = self.ctx.vertex_array(self.prog, [(self.ctx.buffer(quad), "2f", "pos")])
		margin = 0.12
		sdf = logo_sdf(margin=margin, path=logo_path)
		self.logo = self.ctx.texture(sdf.shape[::-1], 1, sdf.tobytes(), dtype="f4")
		self.logo.filter = (moderngl.LINEAR, moderngl.LINEAR)
		self.logo.repeat_x = self.logo.repeat_y = False
		self.logo.use(0)
		self.set("uLogo", 0)
		# The logo's own colours (same square, same margin), for scenes that paint it as it really looks.
		art = Image.open(logo_path or ROOT / "Logo.png").convert("RGBA")
		inner = int(1024 / (1 + 2 * margin))
		canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
		canvas.paste(art.resize((inner, inner), Image.LANCZOS), ((1024 - inner) // 2, (1024 - inner) // 2))
		self.logo_rgb = self.ctx.texture(canvas.size, 4, canvas.tobytes())
		self.logo_rgb.build_mipmaps()
		self.logo_rgb.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
		self.logo_rgb.use(1)
		self.set("uLogoArt", 1)
		if "uText" in self.prog:
			# A scene picks its wordmark with a `// @text "STAFF"` line (default VELO CLIENT).
			m = re.search(r'//\s*@text\s+"([^"]+)"', scene)
			tsdf = text_sdf(m.group(1) if m else "VELO CLIENT")
			self.text = self.ctx.texture((TEXT_W, TEXT_H), 1, tsdf.tobytes(), dtype="f4")
			self.text.filter = (moderngl.LINEAR, moderngl.LINEAR)
			self.text.repeat_x = self.text.repeat_y = False
			self.text.use(2)
			self.set("uText", 2)
		m2 = re.search(r'//\s*@text2\s+"([^"]+)"', scene)
		if m2 and "uText2" in self.prog:
			# a second wordmark in the same font (a scene samples it like text2(), from its own uText2)
			self.text2 = self.ctx.texture((TEXT_W, TEXT_H), 1, text_sdf(m2.group(1)).tobytes(), dtype="f4")
			self.text2.filter = (moderngl.LINEAR, moderngl.LINEAR)
			self.text2.repeat_x = self.text2.repeat_y = False
			self.text2.use(3)
			self.set("uText2", 3)
		pm = re.search(r'//\s*@pixeltext\s+"([^"]+)"(?:\s+"([^"]+)")?', scene)
		if pm:
			for k, word in enumerate((pm.group(1), pm.group(2))):
				name = "uText" if k == 0 else "uText2"
				if word and name in self.prog:
					tex = self.ctx.texture((TEXT_W, TEXT_H), 1, pixel_text_sdf(word).tobytes(), dtype="f4")
					tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
					tex.repeat_x = tex.repeat_y = False
					tex.use(2 + k)
					self.set(name, 2 + k)
					setattr(self, name, tex)
		self.set("uLogoMargin", margin)
		self.fbos = {}
		# A cape can bring data its shader needs (a layout, say) in capes/<id>.py: uniforms() -> {name: value}.
		extra = HERE / "capes" / f"{cape_id}.py"
		if extra.exists():
			spec = importlib.util.spec_from_file_location(f"cape_{cape_id}", extra)
			module = importlib.util.module_from_spec(spec)
			spec.loader.exec_module(module)
			for name, value in module.uniforms().items():
				if name not in self.prog:
					continue
				if isinstance(value, int):
					self.prog[name].value = value
				else:
					self.prog[name].write(np.asarray(value, dtype="f4").tobytes())

	def set(self, name, value):
		if name in self.prog:
			self.prog[name].value = value

	def face(self, face_id, size_units, phase):
		w, h = size_units[0] * PX * SS, size_units[1] * PX * SS
		key = (w, h)
		if key not in self.fbos:
			self.fbos[key] = self.ctx.framebuffer(color_attachments=[self.ctx.texture((w, h), 4, dtype="f4")])
		fbo = self.fbos[key]
		fbo.use()
		self.set("uRes", (float(w), float(h)))
		self.set("uFaceSize", (float(size_units[0]), float(size_units[1])))
		self.set("uFace", face_id)
		self.set("uPhase", float(phase))
		self.vao.render(moderngl.TRIANGLE_STRIP)
		img = np.frombuffer(fbo.read(components=4, dtype="f4"), dtype="f4").reshape(h, w, 4)[:, :, :3]
		return np.flipud(img)  # GL rows start at the bottom; uv.y = 1 is the top of the face


def bloom(img, strength):
	"""Light bleeding out of the bright parts: a few gaussian octaves (radii in final pixels) of what's above ~0.8."""
	bright = np.clip(img - 0.8, 0, None)
	glow = np.zeros_like(img)
	for radius, weight in ((1.5, 0.5), (5, 0.35), (14, 0.25), (36, 0.18)):
		glow += weight * ndimage.gaussian_filter(bright, (radius * PX / 32, radius * PX / 32, 0))
	return img + glow * strength


def aces(x):
	return np.clip((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0, 1)


def finish(img, bloom_strength=1.0):
	"""HDR supersampled face -> 8-bit display face at PX per cape pixel."""
	# A single NaN/inf texel (a degenerate normal now and then) would spread through the bloom into a black box.
	img = np.nan_to_num(img, nan=0.0, posinf=4.0, neginf=0.0)
	h, w = img.shape[0] // SS, img.shape[1] // SS
	img = img.reshape(h, SS, w, SS, 3).mean(axis=(1, 3))  # box filter: exact supersample resolve (in HDR)
	if img.shape[0] >= 64 and img.shape[1] >= 64:
		img = bloom(img, bloom_strength)
	return aces(img) ** (1 / 2.2)


def frame_texture(r, phase):
	tex = np.zeros((32 * PX, 64 * PX, 3))
	faces = {}
	for face_id, (x0, y0, x1, y1), size in FACES:
		img = finish(r.face(face_id, size, phase), 0.55 if face_id == 0 else 0.4)
		faces[face_id] = img
		tex[y0 * PX:y1 * PX, x0 * PX:x1 * PX] = img
	return tex, faces


def to_image(arr):
	return Image.fromarray((np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8))


# ---- 3D preview: the finished texture on a cape box, swinging, so edges and the inside show ----

PREVIEW_VERT = """
#version 330
uniform mat4 mvp;
uniform mat3 nrm;
in vec3 pos; in vec2 uv; in vec3 normal;
out vec2 vUv; out vec3 vN;
void main() { gl_Position = mvp * vec4(pos, 1.0); vUv = uv; vN = nrm * normal; }
"""
PREVIEW_FRAG = """
#version 330
uniform sampler2D tex;
in vec2 vUv; in vec3 vN;
out vec4 color;
void main() {
	vec3 c = texture(tex, vUv).rgb;
	float l = 0.72 + 0.28 * max(dot(normalize(vN), normalize(vec3(0.4, 0.6, 0.8))), 0.0);
	color = vec4(c * l, 1.0);
}
"""


def cape_box():
	"""The vanilla cape cuboid (10 x 16 x 1, uv origin 0,0 in a 64 x 32 texture), outside facing -z."""
	verts = []
	W, H, D = 10.0, 16.0, 1.0

	def quad(corners, uv0, uv1, n):
		(u0, v0), (u1, v1) = uv0, uv1
		uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
		for i in (0, 1, 2, 0, 2, 3):
			x, y, z = corners[i]
			u, v = uvs[i]
			verts.extend([x, y, z, u / 64, v / 32, *n])

	x0, x1, y0, y1, z0, z1 = -W / 2, W / 2, 0.0, -H, -D / 2, D / 2
	# outside (towards the viewer behind the player): -z ... viewed from -z, its left is +x
	quad([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (1, 1), (11, 17), (0, 0, -1))
	quad([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (12, 1), (22, 17), (0, 0, 1))
	quad([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (0, 1), (1, 17), (1, 0, 0))
	quad([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (11, 1), (12, 17), (-1, 0, 0))
	quad([(x1, y0, z1), (x0, y0, z1), (x0, y0, z0), (x1, y0, z0)], (1, 0), (11, 1), (0, 1, 0))
	quad([(x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1)], (11, 0), (21, 1), (0, -1, 0))
	return np.array(verts, dtype="f4")


def perspective(fov, aspect, near, far):
	f = 1 / math.tan(fov / 2)
	return np.array([[f / aspect, 0, 0, 0], [0, f, 0, 0], [0, 0, (far + near) / (near - far), 2 * far * near / (near - far)], [0, 0, -1, 0]])


def rot(axis, a):
	c, s = math.cos(a), math.sin(a)
	if axis == "x":
		return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
	return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def turntable(textures, size=(420, 560)):
	ctx = moderngl.create_standalone_context(backend="egl")
	prog = ctx.program(vertex_shader=PREVIEW_VERT, fragment_shader=PREVIEW_FRAG)
	vbo = ctx.buffer(cape_box())
	vao = ctx.vertex_array(prog, [(vbo, "3f 2f 3f", "pos", "uv", "normal")])
	ms = ctx.framebuffer(color_attachments=[ctx.renderbuffer(size, 4, samples=8)], depth_attachment=ctx.depth_renderbuffer(size, samples=8))
	out = ctx.framebuffer(color_attachments=[ctx.texture(size, 4)])
	frames = []
	n = len(textures)
	for i, tex_img in enumerate(textures):
		tex = ctx.texture(tex_img.size, 3, tex_img.tobytes())
		tex.build_mipmaps()
		tex.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
		tex.anisotropy = 8.0
		tex.use(0)
		t = i / n
		# A slow swing from showing the left edge to the right edge, tilted like a cape in the wind.
		yaw = math.pi + math.sin(t * 2 * math.pi) * 0.75
		model = np.eye(4)
		model[:3, :3] = rot("y", yaw) @ rot("x", 0.28 + 0.06 * math.sin(t * 4 * math.pi))
		view = np.eye(4)
		view[:3, 3] = (0, 8.5, -30)
		proj = perspective(0.62, size[0] / size[1], 1, 100)
		mvp = proj @ view @ model
		prog["mvp"].write(mvp.T.astype("f4").tobytes())
		prog["nrm"].write(model[:3, :3].T.astype("f4").tobytes())
		ms.use()
		ctx.enable(moderngl.DEPTH_TEST)
		ctx.clear(0.075, 0.075, 0.095, 1.0)
		vao.render()
		ctx.copy_framebuffer(out, ms)
		img = Image.frombytes("RGBA", size, out.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB")
		frames.append(img)
		tex.release()
	return frames


def save_gif(frames, path, delay):
	from PIL import features
	if not features.check("libimagequant"):
		# this Pillow lacks libimagequant (e.g. the moderngl venv): let the system python, which has it, encode the gif
		import subprocess, tempfile
		with tempfile.TemporaryDirectory() as tmp:
			names = []
			for i, f in enumerate(frames):
				names.append(f"{tmp}/{i:04d}.png")
				f.save(names[-1])
			code = ("import sys;from PIL import Image;fs=[Image.open(n).convert('RGB') for n in sys.argv[3:]];"
				"p=[f.quantize(colors=256,method=Image.Quantize.LIBIMAGEQUANT,dither=Image.Dither.FLOYDSTEINBERG) for f in fs];"
				"p[0].save(sys.argv[1],save_all=True,append_images=p[1:],duration=int(sys.argv[2]),loop=0,disposal=1,optimize=False)")
			if subprocess.run(["/usr/bin/python3", "-c", code, str(path), str(delay), *names]).returncode == 0:
				return
		paletted = [f.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG) for f in frames]
	else:
		paletted = [f.quantize(colors=256, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.FLOYDSTEINBERG) for f in frames]
	paletted[0].save(path, save_all=True, append_images=paletted[1:], duration=delay, loop=0, disposal=1, optimize=False)


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("cape")
	ap.add_argument("--preview-only", action="store_true", help="don't write the server seed gif")
	ap.add_argument("--frames", type=int, default=FRAMES)
	ap.add_argument("--gif-out", help="also write the full cape layout gif here (e.g. a cloak to upload)")
	ap.add_argument("--gif-width", type=int, default=2048, help="width of --gif-out (uploaded animated cloaks: up to 1024)")
	args = ap.parse_args()
	r = Renderer(args.cape)
	args.cape = Path(args.cape).stem if args.cape.endswith(".glsl") else args.cape
	textures, outsides, insides = [], [], []
	for i in range(args.frames):
		tex, faces = frame_texture(r, i / args.frames)
		textures.append(to_image(tex))
		outsides.append(to_image(faces[0]))
		insides.append(to_image(faces[1]))
		print(f"\r{args.cape}: frame {i + 1}/{args.frames}", end="", file=sys.stderr)
	print(file=sys.stderr)
	delay = round(DELAY_MS * FRAMES / args.frames)
	PREVIEW.mkdir(parents=True, exist_ok=True)
	if not args.preview_only:
		OUT.mkdir(parents=True, exist_ok=True)
		save_gif(textures, OUT / f"{args.cape}.gif", delay)
		print(f"wrote {(OUT / f'{args.cape}.gif').relative_to(ROOT)} ({(OUT / f'{args.cape}.gif').stat().st_size // 1024} KB)")
	if args.gif_out:
		w = args.gif_width
		save_gif([t.resize((w, w // 2), Image.LANCZOS) for t in textures], Path(args.gif_out), delay)
		print(f"wrote {args.gif_out} ({Path(args.gif_out).stat().st_size // 1024} KB)")
	# Previews: the outside big, a contact sheet of every face, a 3D swing.
	textures[0].save(PREVIEW / f"{args.cape}-texture.png")
	save_gif(outsides, PREVIEW / f"{args.cape}-outside.gif", delay)
	fw, fh = 10 * PX, 16 * PX
	sheet = Image.new("RGB", (fw * 2 + PX * 4 + 40, fh + 20), (18, 18, 22))
	sheet.paste(outsides[0], (10, 10))
	sheet.paste(insides[0], (fw + 20, 10))
	t = textures[0]
	for k, box in enumerate(((0, 1, 1, 17), (11, 1, 12, 17))):
		sheet.paste(t.crop(tuple(v * PX for v in box)), (fw * 2 + 30 + k * (PX + 8), 10))
	sheet.save(PREVIEW / f"{args.cape}-faces.png")
	spin = turntable(textures)
	save_gif(spin, PREVIEW / f"{args.cape}-3d.gif", delay)
	print(f"previews in {PREVIEW.relative_to(ROOT)}/{args.cape}-*")


if __name__ == "__main__":
	main()
