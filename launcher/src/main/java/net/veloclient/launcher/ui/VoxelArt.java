package net.veloclient.launcher.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * A small software ray tracer for little block dioramas (the version tiles): textured voxels and flat sprites (plants,
 * items), lit like a shader pack would - sun with soft shadows, sky light with ambient occlusion, glowing point lights,
 * water that mirrors the sky, distance haze, then bloom, filmic tone mapping and a vignette. CPU only, all cores,
 * a fraction of a second per picture; callers cache the result.
 */
final class VoxelArt {

	/** A 16x16 texture in linear light: r, g, b, a per texel (0..1). */
	static final class Tex {
		final float[] px = new float[16 * 16 * 4];

		/** From sRGB ARGB pixels (16x16), optionally multiplied by a tint (grass, leaves, water). */
		static Tex of(int[] argb, int tint) {
			Tex t = new Tex();
			float tr = tint == 0 ? 1 : lin(((tint >> 16) & 0xFF) / 255f);
			float tg = tint == 0 ? 1 : lin(((tint >> 8) & 0xFF) / 255f);
			float tb = tint == 0 ? 1 : lin((tint & 0xFF) / 255f);
			for (int i = 0; i < 256; i++) {
				int p = argb[i];
				t.px[i * 4] = lin(((p >> 16) & 0xFF) / 255f) * tr;
				t.px[i * 4 + 1] = lin(((p >> 8) & 0xFF) / 255f) * tg;
				t.px[i * 4 + 2] = lin((p & 0xFF) / 255f) * tb;
				t.px[i * 4 + 3] = ((p >>> 24) & 0xFF) / 255f;
			}
			return t;
		}

		/** One flat colour (for a version whose game files aren't downloaded yet). */
		static Tex flat(int rgb) {
			int[] argb = new int[256];
			java.util.Arrays.fill(argb, 0xFF000000 | rgb);
			return of(argb, 0);
		}

		/** Nearest texel at (u, v) in 0..1 (v down). */
		int at(float u, float v) {
			int x = Math.min(15, Math.max(0, (int) (u * 16)));
			int y = Math.min(15, Math.max(0, (int) (v * 16)));
			return (y * 16 + x) * 4;
		}
	}

	static final class Block {
		Tex top;
		Tex side;
		Tex bottom;
		boolean cutout;
		boolean water;
		float emit;

		Block(Tex top, Tex side, Tex bottom) {
			this.top = top;
			this.side = side;
			this.bottom = bottom;
		}
	}

	/** A flat textured card standing in the scene: base centre (x, y, z), size in blocks, turned by yaw (degrees). */
	static final class Sprite {
		final float x, y, z, w, h, yaw, tilt;
		final Tex tex;
		final boolean cross;
		final float emit;

		Sprite(float x, float y, float z, float w, float h, float yaw, float tilt, Tex tex, boolean cross, float emit) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.w = w;
			this.h = h;
			this.yaw = yaw;
			this.tilt = tilt;
			this.tex = tex;
			this.cross = cross;
			this.emit = emit;
		}
	}

	record Light(float x, float y, float z, float r, float g, float b, float range) {
	}

	/** Everything a picture needs. Colours are linear RGB (0..1+). */
	static final class Scene {
		final int nx, ny, nz;
		final Block[] grid;
		final List<Sprite> sprites = new ArrayList<>();
		final List<Light> lights = new ArrayList<>();
		float[] camera = {0, 0, 0};
		float[] target = {0, 0, 1};
		float fov = 50;
		float[] sun = {0.4f, 0.6f, 0.5f};
		float[] sunColor = {3.2f, 2.9f, 2.5f};
		float[] skyZenith = {0.12f, 0.3f, 0.75f};
		float[] skyHorizon = {0.7f, 0.82f, 0.95f};
		float[] ambient = {0.35f, 0.42f, 0.55f};
		float fog = 0.012f;
		float exposure = 1f;
		float bloom = 0.35f;
		/** Above the grid's top: open sky (false = a cave ceiling, no sky light from above). */
		boolean openSky = true;

		Scene(int nx, int ny, int nz) {
			this.nx = nx;
			this.ny = ny;
			this.nz = nz;
			this.grid = new Block[nx * ny * nz];
		}

		void set(int x, int y, int z, Block b) {
			if (x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz) {
				grid[(y * nz + z) * nx + x] = b;
			}
		}

		Block get(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
				return null;
			}
			return grid[(y * nz + z) * nx + x];
		}
	}

	private VoxelArt() {
	}

	static float lin(float s) {
		return (float) Math.pow(s, 2.2);
	}

	// ---------------------------------------------------------------- tracing

	/** A hit: distance, normal, colour (linear), alpha-tested; null when the ray leaves the scene. */
	private static final class Hit {
		float t = Float.MAX_VALUE;
		float nx, ny, nz;
		float r, g, b, emit;
		boolean water;
		boolean sprite;
	}

	private static final float EPS = 1e-4f;

	/** Nearest opaque thing along the ray (cut-out texels are passed through). */
	private static Hit trace(Scene s, float ox, float oy, float oz, float dx, float dy, float dz, float maxT, boolean skipWater) {
		Hit best = new Hit();
		best.t = maxT;
		boolean found = traceVoxels(s, ox, oy, oz, dx, dy, dz, best, skipWater);
		found |= traceSprites(s, ox, oy, oz, dx, dy, dz, best);
		return found ? best : null;
	}

	private static boolean traceVoxels(Scene s, float ox, float oy, float oz, float dx, float dy, float dz, Hit best, boolean skipWater) {
		// clip the ray to the grid's box
		float t0 = 0, t1 = best.t;
		float[] o = {ox, oy, oz};
		float[] d = {dx, dy, dz};
		float[] hi = {s.nx, s.ny, s.nz};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(d[a]) < 1e-9f) {
				if (o[a] < 0 || o[a] > hi[a]) {
					return false;
				}
				continue;
			}
			float ta = (0 - o[a]) / d[a];
			float tb = (hi[a] - o[a]) / d[a];
			if (ta > tb) {
				float tmp = ta;
				ta = tb;
				tb = tmp;
			}
			t0 = Math.max(t0, ta);
			t1 = Math.min(t1, tb);
			if (t0 > t1) {
				return false;
			}
		}
		float t = t0 + EPS;
		float px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
		int x = (int) Math.floor(px), y = (int) Math.floor(py), z = (int) Math.floor(pz);
		int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
		float tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
		float tmx = t + (dx > 0 ? (x + 1 - px) : (px - x)) * tdx;
		float tmy = t + (dy > 0 ? (y + 1 - py) : (py - y)) * tdy;
		float tmz = t + (dz > 0 ? (z + 1 - pz) : (pz - z)) * tdz;
		int face = -1; // 0 x, 1 y, 2 z - the axis crossed to enter the current cell
		// the entry face when the ray starts outside
		if (t0 > 0) {
			float ex = Math.abs(t0 - (dx > 0 ? (0 - ox) / dx : (s.nx - ox) / dx));
			float ey = Math.abs(t0 - (dy > 0 ? (0 - oy) / dy : (s.ny - oy) / dy));
			float ez = Math.abs(t0 - (dz > 0 ? (0 - oz) / dz : (s.nz - oz) / dz));
			face = ex <= ey && ex <= ez ? 0 : ey <= ez ? 1 : 2;
		}
		float tEnter = t;
		for (int steps = 0; steps < 512; steps++) {
			if (x < 0 || y < 0 || z < 0 || x >= s.nx || y >= s.ny || z >= s.nz || tEnter > best.t) {
				return false;
			}
			Block b = s.grid[(y * s.nz + z) * s.nx + x];
			if (b != null && !(skipWater && b.water) && face >= 0) {
				float hx = ox + dx * tEnter, hy = oy + dy * tEnter, hz = oz + dz * tEnter;
				float u, v;
				Tex tex;
				float nX = 0, nY = 0, nZ = 0;
				if (face == 1) {
					nY = -sy;
					tex = nY > 0 ? b.top : b.bottom;
					u = frac(hx);
					v = frac(hz);
				} else if (face == 0) {
					nX = -sx;
					tex = b.side;
					u = nX > 0 ? 1 - frac(hz) : frac(hz);
					v = 1 - frac(hy);
				} else {
					nZ = -sz;
					tex = b.side;
					u = nZ > 0 ? frac(hx) : 1 - frac(hx);
					v = 1 - frac(hy);
				}
				if (b.water && face == 1 && nY > 0) {
					// water: only its surface counts as a hit (shaded separately)
					best.t = tEnter;
					best.nx = 0;
					best.ny = 1;
					best.nz = 0;
					best.water = true;
					best.sprite = false;
					best.emit = 0;
					int i = tex.at(u, v);
					best.r = tex.px[i];
					best.g = tex.px[i + 1];
					best.b = tex.px[i + 2];
					return true;
				}
				if (!b.water) {
					int i = tex.at(u, v);
					if (!b.cutout || tex.px[i + 3] > 0.5f) {
						best.t = tEnter;
						best.nx = nX;
						best.ny = nY;
						best.nz = nZ;
						best.r = tex.px[i];
						best.g = tex.px[i + 1];
						best.b = tex.px[i + 2];
						best.emit = b.emit;
						best.water = false;
						best.sprite = false;
						return true;
					}
				}
			}
			if (tmx < tmy && tmx < tmz) {
				x += sx;
				tEnter = tmx;
				tmx += tdx;
				face = 0;
			} else if (tmy < tmz) {
				y += sy;
				tEnter = tmy;
				tmy += tdy;
				face = 1;
			} else {
				z += sz;
				tEnter = tmz;
				tmz += tdz;
				face = 2;
			}
		}
		return false;
	}

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}

	private static boolean traceSprites(Scene s, float ox, float oy, float oz, float dx, float dy, float dz, Hit best) {
		boolean found = false;
		for (Sprite sp : s.sprites) {
			int planes = sp.cross ? 2 : 1;
			for (int k = 0; k < planes; k++) {
				double yaw = Math.toRadians(sp.yaw + (sp.cross ? 45 + 90 * k : 0));
				float rx = (float) Math.cos(yaw), rz = (float) Math.sin(yaw);
				// up, leaned forward/back by tilt (degrees)
				double tilt = Math.toRadians(sp.tilt);
				float ux = (float) (-rz * Math.sin(tilt)), uy = (float) Math.cos(tilt), uz = (float) (rx * Math.sin(tilt));
				// normal = right x up
				float nx = 0 * uz - rz * uy, ny = rz * ux - rx * uz, nz = rx * uy - 0 * ux;
				float denom = dx * nx + dy * ny + dz * nz;
				if (Math.abs(denom) < 1e-6f) {
					continue;
				}
				float t = ((sp.x - ox) * nx + (sp.y - oy) * ny + (sp.z - oz) * nz) / denom;
				if (t <= EPS || t >= best.t) {
					continue;
				}
				float px = ox + dx * t - sp.x, py = oy + dy * t - sp.y, pz = oz + dz * t - sp.z;
				float lr = px * rx + pz * rz;
				float lu = px * ux + py * uy + pz * uz;
				float u = lr / sp.w + 0.5f;
				float v = 1 - lu / sp.h;
				if (u < 0 || u > 1 || v < 0 || v > 1) {
					continue;
				}
				int i = sp.tex.at(u, v);
				if (sp.tex.px[i + 3] < 0.5f) {
					continue;
				}
				best.t = t;
				float side = denom > 0 ? -1 : 1;
				best.nx = nx * side;
				best.ny = ny * side;
				best.nz = nz * side;
				best.r = sp.tex.px[i];
				best.g = sp.tex.px[i + 1];
				best.b = sp.tex.px[i + 2];
				best.emit = sp.emit;
				best.water = false;
				best.sprite = true;
				found = true;
			}
		}
		return found;
	}

	// ---------------------------------------------------------------- shading

	private static void sky(Scene s, float dx, float dy, float dz, float[] out) {
		float h = Math.max(0, dy);
		float k = (float) Math.pow(h, 0.55);
		for (int c = 0; c < 3; c++) {
			out[c] = s.skyHorizon[c] * (1 - k) + s.skyZenith[c] * k;
		}
		float sd = Math.max(0, dx * s.sun[0] + dy * s.sun[1] + dz * s.sun[2]);
		float disk = (float) Math.pow(sd, 1400) * 7 + (float) Math.pow(sd, 16) * 0.18f;
		for (int c = 0; c < 3; c++) {
			out[c] += s.sunColor[c] * disk * 0.25f;
		}
		if (dy < 0) {
			// below the horizon: the haze colour, a little darker
			for (int c = 0; c < 3; c++) {
				out[c] = s.skyHorizon[c] * 0.8f;
			}
		}
	}

	/** Light reaching a point: sun (shadowed) + sky (ambient occlusion) + point lights. */
	private static void light(Scene s, float px, float py, float pz, float nx, float ny, float nz, boolean sprite,
			SplittableRandom rnd, float[] out) {
		float ox = px + nx * 0.002f, oy = py + ny * 0.002f, oz = pz + nz * 0.002f;
		// sun: a slightly jittered direction gives soft shadow edges over the samples
		float lx = s.sun[0] + (float) (rnd.nextDouble() - 0.5) * 0.06f;
		float ly = s.sun[1] + (float) (rnd.nextDouble() - 0.5) * 0.06f;
		float lz = s.sun[2] + (float) (rnd.nextDouble() - 0.5) * 0.06f;
		float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= len;
		ly /= len;
		lz /= len;
		float ndl = nx * lx + ny * ly + nz * lz;
		// plants and items: light passes through a little (they're thin), so their back isn't black
		if (sprite) {
			ndl = Math.abs(ndl) * 0.75f + 0.25f;
		}
		float sun = 0;
		if (ndl > 0 && s.sun[1] > -0.05f) {
			sun = trace(s, ox, oy, oz, lx, ly, lz, 64, true) == null ? ndl : 0;
		}
		// sky light: a few cosine-weighted rays; whatever escapes sees the sky
		int samples = 3;
		float open = 0;
		for (int i = 0; i < samples; i++) {
			float[] d = cosineDir(nx, ny, nz, rnd);
			Hit h = trace(s, ox, oy, oz, d[0], d[1], d[2], 2.5f, true);
			if (h == null && (s.openSky || d[1] < 0.3f)) {
				open += 1;
			} else if (h != null) {
				open += Math.min(1, h.t / 2.5f) * 0.6f;
			}
		}
		float ao = open / samples;
		for (int c = 0; c < 3; c++) {
			out[c] = s.sunColor[c] * sun + s.ambient[c] * (0.25f + 0.75f * ao);
		}
		for (Light l : s.lights) {
			float vx = l.x() - px, vy = l.y() - py, vz = l.z() - pz;
			float dist = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
			if (dist > l.range() || dist < 1e-3f) {
				continue;
			}
			vx /= dist;
			vy /= dist;
			vz /= dist;
			float nd = sprite ? 0.7f : nx * vx + ny * vy + nz * vz;
			if (nd <= 0) {
				continue;
			}
			if (trace(s, ox, oy, oz, vx, vy, vz, dist - 0.3f, true) != null) {
				continue;
			}
			float fall = 1 - dist / l.range();
			float a = nd * fall * fall / (1 + dist * dist * 0.15f);
			out[0] += l.r() * a;
			out[1] += l.g() * a;
			out[2] += l.b() * a;
		}
	}

	private static float[] cosineDir(float nx, float ny, float nz, SplittableRandom rnd) {
		double u1 = rnd.nextDouble(), u2 = rnd.nextDouble();
		double r = Math.sqrt(u1), phi = 2 * Math.PI * u2;
		float lx = (float) (r * Math.cos(phi)), ly = (float) (r * Math.sin(phi)), lz = (float) Math.sqrt(1 - u1);
		// an orthonormal basis around n
		float tx, ty, tz;
		if (Math.abs(nx) > 0.9f) {
			tx = 0;
			ty = 1;
			tz = 0;
		} else {
			tx = 1;
			ty = 0;
			tz = 0;
		}
		float bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
		float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
		bx /= bl;
		by /= bl;
		bz /= bl;
		float cx = ny * bz - nz * by, cy = nz * bx - nx * bz, cz = nx * by - ny * bx;
		return new float[] {bx * lx + cx * ly + nx * lz, by * lx + cy * ly + ny * lz, bz * lx + cz * ly + nz * lz};
	}

	/** The colour seen along one ray (HDR, linear). */
	private static void shade(Scene s, float ox, float oy, float oz, float dx, float dy, float dz, SplittableRandom rnd, float[] out, int depth) {
		Hit h = trace(s, ox, oy, oz, dx, dy, dz, 200, false);
		if (h == null) {
			sky(s, dx, dy, dz, out);
			return;
		}
		float px = ox + dx * h.t, py = oy + dy * h.t, pz = oz + dz * h.t;
		float[] c = new float[3];
		if (h.water) {
			// mirror the scene/sky (fresnel) over the dark water and what lies under it
			float cos = Math.max(0, -dy);
			float fres = 0.04f + 0.96f * (float) Math.pow(1 - cos, 5);
			float[] refl = new float[3];
			if (depth < 1) {
				shade(s, px, py + 0.002f, pz, dx, -dy, dz, rnd, refl, depth + 1);
			} else {
				sky(s, dx, -dy, dz, refl);
			}
			float[] lit = new float[3];
			light(s, px, py, pz, 0, 1, 0, false, rnd, lit);
			for (int k = 0; k < 3; k++) {
				float body = (k == 0 ? h.r * 0.45f : k == 1 ? h.g * 0.7f : h.b * 0.85f) * lit[k] * 0.9f;
				c[k] = body * (1 - fres) + refl[k] * fres;
			}
		} else {
			float[] lit = new float[3];
			light(s, px, py, pz, h.nx, h.ny, h.nz, h.sprite, rnd, lit);
			c[0] = h.r * lit[0] + h.r * h.emit;
			c[1] = h.g * lit[1] + h.g * h.emit;
			c[2] = h.b * lit[2] + h.b * h.emit;
		}
		// distance haze towards the horizon colour
		float f = 1 - (float) Math.exp(-Math.max(0, h.t - 6) * s.fog);
		float[] haze = new float[3];
		sky(s, dx, Math.max(0.02f, dy * 0.3f), dz, haze);
		for (int k = 0; k < 3; k++) {
			out[k] = c[k] * (1 - f) + haze[k] * f;
		}
	}

	// ---------------------------------------------------------------- picture

	/** Renders the scene at {@code w} x {@code h} with {@code spp} jittered samples per pixel. */
	static BufferedImage render(Scene s, int w, int h, int spp) {
		float[] hdr = new float[w * h * 3];
		float[] cam = s.camera;
		float fx = s.target[0] - cam[0], fy = s.target[1] - cam[1], fz = s.target[2] - cam[2];
		float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
		fx /= fl;
		fy /= fl;
		fz /= fl;
		// right = forward x up(0,1,0), up = right x forward
		float rx = -fz, ry = 0, rz = fx;
		float rl = (float) Math.sqrt(rx * rx + rz * rz);
		rx /= rl;
		rz /= rl;
		float ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
		float tan = (float) Math.tan(Math.toRadians(s.fov) / 2);
		float aspect = w / (float) h;
		float sl = (float) Math.sqrt(s.sun[0] * s.sun[0] + s.sun[1] * s.sun[1] + s.sun[2] * s.sun[2]);
		s.sun[0] /= sl;
		s.sun[1] /= sl;
		s.sun[2] /= sl;
		float frx = rx, fry = ry, frz = rz, fux = ux, fuy = uy, fuz = uz, ffx = fx, ffy = fy, ffz = fz;
		IntStream.range(0, h).parallel().forEach(y -> {
			SplittableRandom rnd = new SplittableRandom(y * 7919L + 17);
			float[] col = new float[3];
			for (int x = 0; x < w; x++) {
				float r = 0, g = 0, b = 0;
				for (int k = 0; k < spp; k++) {
					float sx = ((x + (float) rnd.nextDouble()) / w * 2 - 1) * tan * aspect;
					float sy = (1 - (y + (float) rnd.nextDouble()) / h * 2) * tan;
					float dx = ffx + frx * sx + fux * sy, dy = ffy + fry * sx + fuy * sy, dz = ffz + frz * sx + fuz * sy;
					float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
					shade(s, cam[0], cam[1], cam[2], dx / dl, dy / dl, dz / dl, rnd, col, 0);
					r += col[0];
					g += col[1];
					b += col[2];
				}
				int i = (y * w + x) * 3;
				hdr[i] = r / spp * s.exposure;
				hdr[i + 1] = g / spp * s.exposure;
				hdr[i + 2] = b / spp * s.exposure;
			}
		});
		bloom(hdr, w, h, s.bloom);
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = (y * w + x) * 3;
				// vignette
				float vx = x / (float) w - 0.5f, vy = y / (float) h - 0.5f;
				float vig = 1 - (vx * vx + vy * vy) * 0.9f;
				float lr = hdr[i], lg = hdr[i + 1], lb = hdr[i + 2];
				float luma = lr * 0.2126f + lg * 0.7152f + lb * 0.0722f;
				lr = luma + (lr - luma) * 1.12f;
				lg = luma + (lg - luma) * 1.12f;
				lb = luma + (lb - luma) * 1.12f;
				int r = toSrgb(contrast(aces(Math.max(0, lr) * vig)));
				int g = toSrgb(contrast(aces(Math.max(0, lg) * vig)));
				int b = toSrgb(contrast(aces(Math.max(0, lb) * vig)));
				out.setRGB(x, y, r << 16 | g << 8 | b);
			}
		}
		return out;
	}

	/** Adds a soft glow around everything brighter than white (lanterns, lava, the sun). */
	private static void bloom(float[] hdr, int w, int h, float strength) {
		if (strength <= 0) {
			return;
		}
		int bw = Math.max(1, w / 4), bh = Math.max(1, h / 4);
		float[] small = new float[bw * bh * 3];
		for (int y = 0; y < bh; y++) {
			for (int x = 0; x < bw; x++) {
				for (int c = 0; c < 3; c++) {
					float sum = 0;
					for (int yy = 0; yy < 4; yy++) {
						for (int xx = 0; xx < 4; xx++) {
							int sx = Math.min(w - 1, x * 4 + xx), sy = Math.min(h - 1, y * 4 + yy);
							sum += Math.max(0, hdr[(sy * w + sx) * 3 + c] - 0.9f);
						}
					}
					small[(y * bw + x) * 3 + c] = sum / 16;
				}
			}
		}
		for (int pass = 0; pass < 3; pass++) {
			small = blur(small, bw, bh, 3);
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				// bilinear upsample
				float fx = Math.min(bw - 1.001f, Math.max(0, x / 4f - 0.5f)), fy = Math.min(bh - 1.001f, Math.max(0, y / 4f - 0.5f));
				int x0 = (int) fx, y0 = (int) fy;
				float ax = fx - x0, ay = fy - y0;
				for (int c = 0; c < 3; c++) {
					float a = small[(y0 * bw + x0) * 3 + c], b = small[(y0 * bw + x0 + 1) * 3 + c];
					float cc = small[((y0 + 1) * bw + x0) * 3 + c], d = small[((y0 + 1) * bw + x0 + 1) * 3 + c];
					float v = (a * (1 - ax) + b * ax) * (1 - ay) + (cc * (1 - ax) + d * ax) * ay;
					hdr[(y * w + x) * 3 + c] += v * strength * 3;
				}
			}
		}
	}

	private static float[] blur(float[] src, int w, int h, int r) {
		float[] tmp = new float[src.length];
		float[] dst = new float[src.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				for (int c = 0; c < 3; c++) {
					float sum = 0;
					int n = 0;
					for (int k = -r; k <= r; k++) {
						int xx = Math.min(w - 1, Math.max(0, x + k));
						sum += src[(y * w + xx) * 3 + c];
						n++;
					}
					tmp[(y * w + x) * 3 + c] = sum / n;
				}
			}
		}
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				for (int c = 0; c < 3; c++) {
					float sum = 0;
					int n = 0;
					for (int k = -r; k <= r; k++) {
						int yy = Math.min(h - 1, Math.max(0, y + k));
						sum += tmp[(yy * w + x) * 3 + c];
						n++;
					}
					dst[(y * w + x) * 3 + c] = sum / n;
				}
			}
		}
		return dst;
	}

	/** Filmic curve (ACES fit): bright areas roll off softly instead of clipping. */
	private static float aces(float x) {
		x = Math.max(0, x);
		return Math.min(1, (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f));
	}

	/** A gentle S-curve in display space: deeper shadows, a touch more punch. */
	private static float contrast(float v) {
		float g = (float) Math.pow(v, 1 / 2.2);
		g = g + (g - 0.5f) * 0.12f * (1 - Math.abs(2 * g - 1));
		return (float) Math.pow(Math.max(0, Math.min(1, g)), 2.2);
	}

	private static int toSrgb(float v) {
		return Math.min(255, Math.max(0, Math.round((float) Math.pow(v, 1 / 2.2) * 255)));
	}
}
