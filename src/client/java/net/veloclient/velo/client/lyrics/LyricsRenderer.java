package net.veloclient.velo.client.lyrics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import org.joml.Matrix3x2f;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out the lyrics letter by letter - which lines show, every letter's animation, colour and where each line sits -
 * and hands each letter to a {@link Sink}: the world pass ({@link LyricsWorld}, real 3D with the depth buffer, so the
 * sky text sits behind terrain and clouds) or the HUD (2D style and the R-Shift card preview).
 */
final class LyricsRenderer {

	static final float EM = 9f; // font units per line height
	private static final Map<String, Text> GLYPHS = new HashMap<>();
	private static final Map<String, Integer> WIDTHS = new HashMap<>();
	/** Where each line sits once it first shows (spread modes): {yaw offset or x, elevation/height offset or y, distance factor}. */
	private static final Map<Integer, float[]> PLACES = new HashMap<>();
	private static float smoothYaw = Float.NaN;
	private static double[] fixedCam;
	private static float fixedYaw;
	private static long lastNow = Long.MIN_VALUE;
	/** The Lyrics Studio moves the drawing right, into the free space next to its panel (fraction of half the screen). */
	static float previewShift;

	private LyricsRenderer() {
	}

	/** Where a line is drawn. 2D: screen point + pixels per font unit. 3D: world point (camera-relative) + plane axes. */
	static final class Anchor {
		boolean world;
		float sx;
		float sy;
		float unit;
		double ax;
		double ay;
		double az;
		double rx;
		double ry;
		double rz;
		double ux;
		double uy;
		double uz;
		double nx;
		double ny;
		double nz;
		double s;
		double maxWidth;
	}

	/** Gets every letter to draw. {@code cx, cy}: letter centre in line space (font units, y down); {@code dz}: depth offset. */
	interface Sink {
		void glyph(Anchor a, Text glyph, int w, float cx, float cy, float dz, float rot, float scaleX, float scaleY, int color, float alpha,
				LyricsStyle st);
	}

	/** Per-letter animation result. */
	private static final class Fx {
		float dx;
		float dy;
		float dz;
		float rot;
		float sx = 1f;
		float sy = 1f;
		float alpha = 1f;
		boolean flicker;
	}

	// ---------------------------------------------------------------- frame

	/**
	 * One frame of lyrics. {@code view}: {camX, camY, camZ, yaw} for 3D (null in 2D); {@code sw, sh}: screen size in GUI
	 * pixels (2D) - in 3D only used for the preview shift.
	 */
	static void frame(Sink sink, LyricsStyle st, boolean demo, double[] view, int sw, int sh, float dt, boolean cards) {
		List<LyricsFetcher.Line> lines = LyricsSync.lines(demo);
		long now = LyricsSync.now(demo, st.offsetMs);
		if (now < lastNow - 1500) {
			PLACES.clear(); // seek back / the demo looped
		}
		lastNow = now;
		boolean world = view != null;
		if (world) {
			follow(st, view, dt);
		}
		float t = (System.currentTimeMillis() % 1_000_000L) / 1000f;
		float enterDur = (float) (550 / st.speed);
		float exitDur = (float) (450 / st.speed);
		// lines start their entrance a little early, so they're readable right when they're sung
		long lead = (long) (120 + enterDur * 0.35f);
		long shown = now + lead;
		boolean oneSpot = "One Spot".equals(st.spread);
		if (lines != null && !lines.isEmpty()) {
			int i = -1;
			for (int k = 0; k < lines.size(); k++) {
				if (lines.get(k).startMs() <= shown) {
					i = k;
				} else {
					break;
				}
			}
			long songEnd = LyricsSync.length(demo);
			if (i >= 1) {
				LyricsFetcher.Line prev = lines.get(i - 1);
				long since = shown - lines.get(i).startMs();
				Anchor a = anchor(st, i - 1, world, view, sw, sh);
				if (a != null && !prev.text().isEmpty()) {
					if (since < exitDur) {
						drawLine(sink, a, st, prev.text(), i - 1, 0f, 1f, since / exitDur, prev.startMs(), lines.get(i).startMs(), now, shown, t, false);
					} else if (st.showPrevious && oneSpot) {
						drawLine(sink, a, st, prev.text(), i - 1, -(float) st.lineGap, 0.7f, -1f, prev.startMs(), lines.get(i).startMs(), now, shown, t, true);
					}
				}
			}
			if (i >= 0) {
				LyricsFetcher.Line cur = lines.get(i);
				long end = i + 1 < lines.size() ? lines.get(i + 1).startMs() : Math.min(songEnd, cur.startMs() + 6000);
				Anchor a = anchor(st, i, world, view, sw, sh);
				if (a != null && !cur.text().isEmpty()) {
					float exit = shown >= end ? Math.min(1f, (shown - end) / exitDur) : -1f;
					if (exit < 1f) {
						drawLine(sink, a, st, cur.text(), i, 0f, 1f, exit, cur.startMs(), end, now, shown, t, false);
					}
				}
			}
			if (st.showNext && i + 1 < lines.size()) {
				LyricsFetcher.Line next = lines.get(i + 1);
				Anchor a = anchor(st, oneSpot ? i : i + 1, world, view, sw, sh);
				if (a != null && !next.text().isEmpty()) {
					long since = i >= 0 ? shown - lines.get(i).startMs() : shown;
					float fade = Math.min(1f, Math.max(0f, (since - enterDur * 0.5f) / enterDur));
					drawLine(sink, a, st, next.text(), i + 1, oneSpot ? (float) st.lineGap : 0f, oneSpot ? 0.7f : 0.85f, -2f - fade, next.startMs(),
							next.startMs(), now, shown, t, true);
				}
			}
		}
		if (cards) {
			Anchor a = anchor(st, -1, world, view, sw, sh);
			if (a != null) {
				drawCards(sink, a, st, demo);
			}
		}
	}

	/** The R-Shift card preview: flat, inside the card (demo lines when nothing plays). */
	static void renderCard(DrawContext context, int x, int y, int w, int h, LyricsStyle st) {
		LyricsStyle flat = st.copy();
		flat.display = "2D Screen";
		flat.spread = "One Spot";
		flat.screenSize = Math.max(0.4, Math.min(0.75, w / 220.0));
		Anchor a = new Anchor();
		a.unit = (float) (2.2 * flat.screenSize);
		a.sx = x + w / 2f;
		a.sy = y + h * 0.5f;
		a.maxWidth = w * 0.9f / a.unit;
		context.enableScissor(x, y, x + w, y + h);
		try {
			HudSink sink = new HudSink(context);
			cardAnchor = a;
			frame(sink, flat, true, null, w, h, 0.016f, false);
		} finally {
			cardAnchor = null;
			context.disableScissor();
		}
	}

	private static Anchor cardAnchor;

	// ---------------------------------------------------------------- placement

	private static void follow(LyricsStyle st, double[] view, float dt) {
		float yaw = (float) view[3];
		if (Float.isNaN(smoothYaw)) {
			smoothYaw = yaw;
		}
		if (st.followView) {
			float diff = wrap(yaw - smoothYaw);
			float rate = st.sky() ? 1.6f : 4.5f;
			smoothYaw += diff * (1f - (float) Math.exp(-rate * dt));
			fixedCam = null;
		} else if (fixedCam == null) {
			fixedCam = new double[] {view[0], view[1], view[2]};
			fixedYaw = yaw;
		}
	}

	/** The anchor of line {@code index} (-1 = the song card): spread lines each get their own spot, kept while they show. */
	private static Anchor anchor(LyricsStyle st, int index, boolean world, double[] view, int sw, int sh) {
		if (cardAnchor != null) {
			return cardAnchor;
		}
		float[] place = place(st, index, world ? (st.followView ? smoothYaw : fixedYaw) : 0f);
		Anchor a = new Anchor();
		if (!world) {
			a.unit = (float) (2.2 * st.screenSize);
			float baseY = switch (st.screenSpot) {
				case "Top" -> 34 + EM * a.unit;
				case "Center" -> sh * 0.4f;
				default -> sh - 64 - EM * a.unit * 1.4f;
			};
			a.sx = sw / 2f + place[0] * sw * 0.32f + previewShift * sw / 2f;
			a.sy = baseY + place[1] * sh * 0.25f;
			a.maxWidth = sw * (1f - previewShift) * 0.86f / a.unit;
			return a;
		}
		double yawDeg = place[0] + previewShift * 60f;
		double yawR = Math.toRadians(yawDeg);
		double dist;
		if (st.sky()) {
			// as far as the view reaches before the fog - further text would just fade into the fog colour
			double reach = Math.max(32, LyricsMc.renderDistanceBlocks() * 0.78);
			dist = Math.min(Math.max(30, st.distance), reach) * place[2];
			double el = Math.toRadians(Math.max(0, Math.min(75, st.height + place[1])));
			a.ax = -Math.sin(yawR) * Math.cos(el) * dist;
			a.ay = Math.sin(el) * dist;
			a.az = Math.cos(yawR) * Math.cos(el) * dist;
		} else {
			dist = Math.max(2, Math.min(14, st.distance)) * place[2];
			double[] base = st.followView || fixedCam == null ? new double[] {view[0], view[1], view[2]} : fixedCam;
			// camera-relative: the world pass draws relative to the camera
			a.ax = base[0] - view[0] - Math.sin(yawR) * dist;
			a.ay = base[1] - view[1] + st.height + place[1];
			a.az = base[2] - view[2] + Math.cos(yawR) * dist;
		}
		// the plane faces the camera and stays upright
		double len = Math.sqrt(a.ax * a.ax + a.ay * a.ay + a.az * a.az);
		if (len < 0.5) {
			return null;
		}
		double vx = a.ax / len;
		double vy = a.ay / len;
		double vz = a.az / len;
		double rx = -vz;
		double rz = vx;
		double rl = Math.max(1e-6, Math.sqrt(rx * rx + rz * rz));
		rx /= rl;
		rz /= rl;
		double ux = -rz * vy;
		double uy = rz * vx - rx * vz;
		double uz = rx * vy;
		// the line's tilt turns the axes within the plane
		double tilt = Math.toRadians(st.tilt);
		double c = Math.cos(tilt);
		double s = Math.sin(tilt);
		a.rx = rx * c + ux * s;
		a.ry = uy * s;
		a.rz = rz * c + uz * s;
		a.ux = ux * c - rx * s;
		a.uy = uy * c;
		a.uz = uz * c - rz * s;
		a.nx = vx;
		a.ny = vy;
		a.nz = vz;
		double letterHeight = st.sky() ? dist * 0.11 * st.size : 0.6 * st.size;
		a.s = letterHeight / EM;
		a.maxWidth = dist * (st.sky() ? 1.3 : 1.6) / a.s;
		a.world = true;
		return a;
	}

	/** {yaw (deg) or 2D x, elevation (deg) / height (blocks) or 2D y, distance factor} for a line. */
	private static float[] place(LyricsStyle st, int index, float baseYaw) {
		boolean world = st.world3d();
		if ("One Spot".equals(st.spread) || index < 0) {
			return new float[] {world ? baseYaw : 0f, 0f, 1f};
		}
		float[] p = PLACES.get(index);
		if (p == null) {
			boolean every = "Everywhere".equals(st.spread);
			float r1 = rand(index * 7919 + 13) - 0.5f;
			float r2 = rand(index * 104729 + 7) - 0.5f;
			float r3 = rand(index * 31337 + 3) - 0.5f;
			if (world) {
				p = new float[] {baseYaw + r1 * (every ? 300f : 110f), r2 * (st.sky() ? (every ? 30f : 16f) : (every ? 2.4f : 1.4f)), 1f + r3 * 0.3f};
			} else {
				p = new float[] {r1 * (every ? 1.6f : 0.9f), r2 * (every ? 1.6f : 0.6f), 1f};
			}
			PLACES.put(index, p);
			if (PLACES.size() > 200) {
				PLACES.clear();
			}
		}
		return p;
	}

	private static float wrap(float deg) {
		deg %= 360f;
		if (deg > 180f) {
			deg -= 360f;
		} else if (deg < -180f) {
			deg += 360f;
		}
		return deg;
	}

	// ---------------------------------------------------------------- one line

	/**
	 * @param row  line offset in line heights (0 = where the line sits)
	 * @param exit -1 = not leaving, 0..1 = leaving; below -1.5 = a dim preview line (-2 - fade)
	 */
	private static void drawLine(Sink sink, Anchor a, LyricsStyle st, String raw, int lineIndex, float row, float lineScale, float exit,
			long start, long end, long now, long shown, float t, boolean dim) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		String text = switch (st.textCase) {
			case "UPPERCASE" -> raw.toUpperCase(Locale.ROOT);
			case "lowercase" -> raw.toLowerCase(Locale.ROOT);
			default -> raw;
		};
		int[] cps = text.codePoints().toArray();
		int n = cps.length;
		if (n == 0) {
			return;
		}
		float gap = (float) st.spacing;
		int[] widths = new int[n];
		int[] wordOf = new int[n];
		int words = 0;
		float total = 0;
		for (int k = 0; k < n; k++) {
			widths[k] = width(textRenderer, st.font, cps[k]);
			total += widths[k] + gap;
			if (cps[k] == ' ') {
				words++;
			}
			wordOf[k] = words;
		}
		words++;
		// too long for one row: two rows, split at the space nearest the middle
		int split = -1;
		if (total * lineScale > a.maxWidth && words > 1) {
			float acc = 0;
			float best = Float.MAX_VALUE;
			for (int k = 0; k < n; k++) {
				if (cps[k] == ' ' && Math.abs(acc - total / 2f) < best) {
					best = Math.abs(acc - total / 2f);
					split = k;
				}
				acc += widths[k] + gap;
			}
		}
		float alphaLine = (float) st.opacity;
		boolean preview = exit < -1.5f;
		if (preview) {
			alphaLine *= 0.38f * (-exit - 2f);
		} else if (dim) {
			alphaLine *= 0.38f;
		}
		if (alphaLine <= 0.01f) {
			return;
		}
		float enterDur = (float) (550 / st.speed);
		float stagger = st.perLetter ? Math.min(45f, enterDur * 0.9f / Math.max(1, n)) : 0f;
		boolean typewriter = "Typewriter".equals(st.enter);
		boolean byWord = "Word by Word".equals(st.enter);
		float span = Math.max(300f, end - start);
		if (typewriter) {
			stagger = Math.min(60f, Math.max(16f, span * 0.55f / Math.max(1, n)));
		}
		float karaoke = end > start ? (now - start) / (float) (end - start) : 1f;
		int parts = split < 0 ? 1 : 2;
		for (int part = 0; part < parts; part++) {
			int from = part == 0 ? 0 : split + 1;
			int to = parts == 1 ? n : part == 0 ? split : n;
			float partW = 0;
			for (int k = from; k < to; k++) {
				partW += widths[k] + gap;
			}
			float fit = partW * lineScale > a.maxWidth ? (float) (a.maxWidth / (partW * lineScale)) : 1f;
			float scale = lineScale * fit;
			float partRow = parts == 1 ? row : row + (part == 0 ? -0.58f : 0.58f) * lineScale;
			float x = -partW * scale / 2f;
			for (int k = from; k < to; k++) {
				int cp = cps[k];
				float w = widths[k] * scale;
				if (cp == ' ') {
					x += (widths[k] + gap) * scale;
					continue;
				}
				Fx fx = new Fx();
				int seed = lineIndex * 131 + k;
				if (!dim && !preview) {
					float delay = byWord ? wordOf[k] * Math.min(320f, span * 0.8f / words) : k * stagger;
					float local = (shown - start - delay) / (byWord ? enterDur * 0.6f : enterDur);
					enter(fx, byWord ? "Pop" : st.enter, clamp(local), seed, t);
					if (exit >= 0f) {
						float q = clamp(exit * 1.25f - (st.perLetter ? (k / (float) n) * 0.25f : 0f));
						exitFx(fx, st.exit, q, lineIndex * 977 + k, t);
					}
					idle(fx, st.idle, t, k);
				}
				if (st.chaos > 0) {
					fx.rot += (rand(seed * 17) - 0.5f) * (float) st.chaos * 1.2f;
					fx.dy += (rand(seed * 29) - 0.5f) * (float) st.chaos * 5f;
				}
				float alpha = fx.alpha * alphaLine;
				if (alpha > 0.02f) {
					int base = color(st, k, n, wordOf[k], lineIndex, t);
					if (st.karaoke && !dim && !preview) {
						float lit = clamp((karaoke * n - k) * 1.5f);
						base = lerpColor(base, st.highlight, lit);
					}
					if (fx.flicker && ((int) (t * 30 + k) & 3) == 0) {
						base = st.glow ? st.glowColor : st.color2;
					}
					float cx = x + w / 2f + fx.dx * scale;
					float cy = partRow * EM + fx.dy * scale;
					sink.glyph(a, glyph(st.font, cp), widths[k], cx, cy, fx.dz * scale, fx.rot, fx.sx * scale, fx.sy * scale, base, alpha, st);
				}
				x += (widths[k] + gap) * scale;
			}
		}
	}

	private static int color(LyricsStyle st, int k, int n, int word, int line, float t) {
		float f = n <= 1 ? 0f : k / (float) (n - 1);
		return switch (st.colorMode) {
			case "Gradient" -> lerpColor(st.color, st.color2, f);
			case "Flowing Gradient" -> lerpColor(st.color, st.color2, 0.5f + 0.5f * (float) Math.sin(f * 4.0 - t * 2.2));
			case "Rainbow" -> 0xFF000000 | java.awt.Color.HSBtoRGB(((f * 0.6f + t * 0.25f) % 1f + 1f) % 1f, 0.65f, 1f);
			case "Per Word" -> ((word + line) & 1) == 0 ? st.color : st.color2;
			default -> st.color;
		};
	}

	// ---------------------------------------------------------------- effects

	private static void enter(Fx fx, String kind, float p, int seed, float t) {
		if (p >= 1f) {
			return;
		}
		float e = easeOutCubic(p);
		switch (kind) {
			case "Rise" -> {
				fx.dy = (1 - e) * 7f;
				fx.alpha = e;
			}
			case "Drop" -> {
				fx.dy = -(1 - easeOutBounce(p)) * 12f;
				fx.alpha = Math.min(1f, p * 3f);
			}
			case "Slide" -> {
				fx.dx = (1 - e) * -14f;
				fx.alpha = e;
			}
			case "Zoom" -> {
				float z = 0.2f + 0.8f * easeOutBack(p);
				fx.sx = z;
				fx.sy = z;
				fx.alpha = e;
			}
			case "Pop" -> {
				float z = easeOutElastic(p);
				fx.sx = z;
				fx.sy = z;
				fx.alpha = Math.min(1f, p * 4f);
			}
			case "Bounce" -> {
				fx.dy = -(float) Math.abs(Math.sin(p * Math.PI * 2.5)) * 9f * (1 - p);
				fx.sy = 0.7f + 0.3f * easeOutBack(p);
				fx.alpha = Math.min(1f, p * 3f);
			}
			case "Wave" -> {
				fx.dy = (float) Math.sin((1 - e) * Math.PI) * -8f;
				fx.alpha = e;
			}
			case "Spin" -> {
				fx.rot = (1 - e) * (float) Math.PI * 1.5f;
				fx.sx = 0.3f + 0.7f * e;
				fx.sy = fx.sx;
				fx.alpha = e;
			}
			case "Flip" -> {
				fx.sx = (float) Math.cos((1 - e) * Math.PI / 2);
				fx.alpha = Math.min(1f, p * 2.5f);
			}
			case "Fly In" -> {
				fx.dz = (1 - e) * 80f;
				fx.dy = (1 - e) * -4f;
				fx.alpha = e;
			}
			case "Typewriter" -> fx.alpha = p > 0f ? 1f : 0f;
			case "Glitch" -> {
				float r = rand(seed + (int) (t * 24));
				fx.dx = (r - 0.5f) * 8f * (1 - p);
				fx.dy = (rand(seed * 7 + (int) (t * 24)) - 0.5f) * 3f * (1 - p);
				fx.alpha = p > 0f ? (r > 0.3f * (1 - p) ? 1f : 0.2f) : 0f;
				fx.flicker = p < 0.8f;
			}
			case "Blur In" -> {
				float z = 1f + (1 - e) * 0.9f;
				fx.sx = z;
				fx.sy = z;
				fx.alpha = e * e;
			}
			default -> fx.alpha = e;
		}
	}

	private static void exitFx(Fx fx, String kind, float q, int seed, float t) {
		if (q <= 0f) {
			return;
		}
		float e = q * q;
		switch (kind) {
			case "Rise" -> {
				fx.dy -= e * 9f;
				fx.alpha *= 1 - q;
			}
			case "Fall" -> {
				fx.dy += e * 18f;
				fx.rot += (rand(seed) - 0.5f) * e * 1.6f;
				fx.alpha *= 1 - q;
			}
			case "Slide" -> {
				fx.dx += e * 16f;
				fx.alpha *= 1 - q;
			}
			case "Zoom" -> {
				fx.sx *= 1 + e * 0.8f;
				fx.sy *= 1 + e * 0.8f;
				fx.alpha *= 1 - q;
			}
			case "Shrink" -> {
				fx.sx *= 1 - e;
				fx.sy *= 1 - e;
			}
			case "Scatter" -> {
				fx.dx += (rand(seed) - 0.5f) * 40f * e;
				fx.dy += (rand(seed * 3) - 0.5f) * 30f * e;
				fx.rot += (rand(seed * 5) - 0.5f) * 3f * e;
				fx.alpha *= 1 - q;
			}
			case "Explode" -> {
				float ang = rand(seed) * 6.283f;
				fx.dx += (float) Math.cos(ang) * 60f * q;
				fx.dy += (float) Math.sin(ang) * 60f * q;
				fx.dz -= 40f * q;
				fx.rot += (rand(seed * 5) - 0.5f) * 6f * q;
				fx.alpha *= 1 - e;
			}
			case "Fly Away" -> {
				fx.dz += e * 120f;
				fx.dy -= e * 10f;
				fx.alpha *= 1 - q;
			}
			case "Dissolve" -> {
				float r = rand(seed) * 0.9f;
				fx.alpha *= q > r ? Math.max(0f, 1f - (q - r) * 6f) : 1f;
			}
			case "Glitch" -> {
				fx.dx += (rand(seed + (int) (t * 30)) - 0.5f) * 12f * q;
				fx.alpha *= rand(seed * 3 + (int) (t * 30)) > q ? 1f - q * 0.5f : 0f;
				fx.flicker = true;
			}
			default -> fx.alpha *= 1 - q;
		}
	}

	private static void idle(Fx fx, String kind, float t, int k) {
		switch (kind) {
			case "Float" -> fx.dy += (float) Math.sin(t * 1.6) * 0.8f;
			case "Wave" -> fx.dy += (float) Math.sin(t * 3.2 + k * 0.45) * 1.3f;
			case "Pulse" -> {
				float z = 1f + 0.05f * (float) Math.sin(t * 5.0);
				fx.sx *= z;
				fx.sy *= z;
			}
			case "Sway" -> fx.rot += (float) Math.sin(t * 1.8 + k * 0.2) * 0.08f;
			case "Breathe" -> fx.alpha *= 0.75f + 0.25f * (float) Math.sin(t * 2.0 + k * 0.3);
			case "Shake" -> {
				fx.dx += (rand(k * 13 + (int) (t * 20)) - 0.5f) * 0.9f;
				fx.dy += (rand(k * 29 + (int) (t * 20)) - 0.5f) * 0.9f;
			}
			case "Jelly" -> {
				float w = (float) Math.sin(t * 6.0 + k * 0.6);
				fx.sx *= 1f + 0.08f * w;
				fx.sy *= 1f - 0.08f * w;
			}
			default -> {
			}
		}
	}

	// ---------------------------------------------------------------- song card

	private static void drawCards(Sink sink, Anchor a, LyricsStyle st, boolean demo) {
		String top = null;
		String sub = null;
		long since = LyricsSync.sinceSongStart();
		LyricsSync.Status status = LyricsSync.status();
		if (LyricsSync.showingDemo(demo)) {
			top = "♪ Lyrics preview";
			sub = status == LyricsSync.Status.NOT_FOUND ? "No synced lyrics for " + LyricsSync.title() : "Play a song to see its lyrics";
			since = 2000;
		} else if (st.songCard && status != LyricsSync.Status.NOTHING_PLAYING && since < 5000) {
			top = "♪ " + LyricsSync.title();
			sub = status == LyricsSync.Status.NOT_FOUND ? "No synced lyrics for this song" : LyricsSync.artist();
		} else if (status == LyricsSync.Status.NOT_FOUND && since < 4000) {
			sub = "No synced lyrics for this song";
		}
		if (top == null && sub == null) {
			return;
		}
		float fade = LyricsSync.showingDemo(demo) ? 1f : Math.min(1f, Math.min(since / 400f, (5000 - since) / 600f));
		if (fade <= 0.02f) {
			return;
		}
		float rowTop = st.showPrevious ? -(float) st.lineGap - 1.6f : -2.1f;
		if (top != null) {
			plain(sink, a, st, top, rowTop, 0.62f, st.color, fade * (float) st.opacity);
		}
		if (sub != null && !sub.isBlank()) {
			plain(sink, a, st, sub, top != null ? rowTop + 0.85f : rowTop, 0.48f, 0xFFB8B8C2, fade * 0.85f * (float) st.opacity);
		}
	}

	/** A string without effects (the song card): letter by letter so it uses the same path. */
	private static void plain(Sink sink, Anchor a, LyricsStyle st, String s, float row, float scale, int color, float alpha) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int[] cps = s.codePoints().toArray();
		float total = 0;
		int[] widths = new int[cps.length];
		for (int k = 0; k < cps.length; k++) {
			widths[k] = width(textRenderer, st.font, cps[k]);
			total += widths[k];
		}
		float fit = total * scale > a.maxWidth ? (float) (a.maxWidth / (total * scale)) : 1f;
		float sc = scale * fit;
		float x = -total * sc / 2f;
		for (int k = 0; k < cps.length; k++) {
			if (cps[k] != ' ') {
				sink.glyph(a, glyph(st.font, cps[k]), widths[k], x + widths[k] * sc / 2f, row * EM, 0f, 0f, sc, sc, color, alpha, st);
			}
			x += widths[k] * sc;
		}
	}

	// ---------------------------------------------------------------- 2D drawing (HUD)

	/** Draws letters flat on the HUD with a 2D transform each (2D style and the card preview). */
	static final class HudSink implements Sink {
		private final DrawContext context;

		HudSink(DrawContext context) {
			this.context = context;
		}

		@Override
		public void glyph(Anchor a, Text glyph, int w, float cx, float cy, float dz, float rot, float scaleX, float scaleY, int color, float alpha,
				LyricsStyle st) {
			float depth = 1f / Math.max(0.15f, 1f + dz / 80f);
			float cos = (float) Math.cos(rot);
			float sin = (float) Math.sin(rot);
			float ax = cos * a.unit * scaleX * depth;
			float ay = sin * a.unit * scaleX * depth;
			float bx = -sin * a.unit * scaleY * depth;
			float by = cos * a.unit * scaleY * depth;
			float px = a.sx + cx * a.unit;
			float py = a.sy + cy * a.unit;
			float[] m = {ax, ay, bx, by, px - ax * w / 2f - bx * EM / 2f, py - ay * w / 2f - by * EM / 2f};
			effects(st, alpha, (ox, oy, sc, c, al) -> {
				if (sc == 1f) {
					layer(context, m, glyph, ox, oy, c, al);
				} else {
					// grown around the letter's centre
					float cx0 = w / 2f;
					float cy0 = EM / 2f;
					float tx = cx0 * (1 - sc) + ox;
					float ty = cy0 * (1 - sc) + oy;
					float[] g = {m[0] * sc, m[1] * sc, m[2] * sc, m[3] * sc, m[4] + m[0] * tx + m[2] * ty, m[5] + m[1] * tx + m[3] * ty};
					layer(context, g, glyph, 0f, 0f, c, al);
				}
			});
			layer(context, m, glyph, 0f, 0f, color, alpha);
		}
	}

	/** The shading layers of one letter, as offsets in font units: glow rings, outline, shadow. */
	interface Layer {
		/** {@code scale}: the copy grows around the letter's centre (soft halo); {@code depth}: 0 = nearest shading layer. */
		void draw(float ox, float oy, float scale, int color, float alpha);
	}

	/**
	 * The shading layers of one letter, drawn back to front: glow (soft halo copies growing around the letter plus fine
	 * rings - more of them the bigger the glow, so big glows stay smooth instead of showing single copies), shadow, outline.
	 */
	static void effects(LyricsStyle st, float alpha, Layer out) {
		if (st.glow) {
			float size = (float) st.glowSize;
			float strength = (float) st.glowStrength;
			int halos = Math.max(3, Math.min(8, Math.round(2 + size * 2.2f)));
			for (int i = halos; i >= 1; i--) {
				float f = i / (float) halos;
				// outer copies are bigger and fainter: a smooth falloff
				float grow = 1f + f * 0.16f * size;
				float al = alpha * strength * 0.5f * (1f - f * 0.75f) / halos * 2.2f;
				out.draw(0f, 0f, grow, st.glowColor, al);
			}
			int rings = size > 1.3f ? 2 : 1;
			for (int ring = rings; ring >= 1; ring--) {
				float r = size * 0.9f * ring / rings;
				float al = alpha * strength * 0.13f * (1.15f - ring / (float) rings * 0.6f);
				double turn = ring * 0.6180339 * Math.PI;
				for (int i = 0; i < 10; i++) {
					double ang = i * Math.PI / 5 + turn;
					out.draw((float) Math.cos(ang) * r, (float) Math.sin(ang) * r, 1f, st.glowColor, al);
				}
			}
		}
		if (st.shadow && !st.solid()) {   // solid letters carry their own shading: no flat shadow or outline copies
			out.draw(0.9f, 0.9f, 1f, st.shadowColor, alpha * 0.75f);
		}
		if (st.outline && !st.solid()) {
			float r = 0.55f * (float) st.outlineSize;
			int steps = st.outlineSize > 1.4 ? 16 : 8;
			for (int i = 0; i < steps; i++) {
				double ang = i * 2 * Math.PI / steps;
				out.draw((float) Math.cos(ang) * r, (float) Math.sin(ang) * r, 1f, st.outlineColor, alpha);
			}
		}
	}

	/** How many layers {@link #effects} draws (the world pass spaces them in depth). */
	static int effectLayers(LyricsStyle st) {
		int[] n = {0};
		effects(st, 1f, (ox, oy, sc, c, al) -> n[0]++);
		return n[0];
	}

	private static void layer(DrawContext context, float[] m, Text glyph, float ox, float oy, int color, float alpha) {
		int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * ((color >>> 24) & 0xFF));
		if (a < 5) {
			return; // nearly invisible (and older versions draw alpha < 4 fully opaque)
		}
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.mul(new Matrix3x2f(m[0], m[1], m[2], m[3], m[4] + m[0] * ox + m[2] * oy, m[5] + m[1] * ox + m[3] * oy));
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		MenuKit.text(context, textRenderer, glyph, 0, 0, (a << 24) | (color & 0xFFFFFF));
		matrices.popMatrix();
	}

	// ---------------------------------------------------------------- fonts

	static String fontId(String font) {
		return switch (font) {
			case "Archivo" -> "lyrics_archivo";
			case "Inter" -> "lyrics_inter";
			case "Sora" -> "lyrics_sora";
			case "Space Grotesk" -> "lyrics_grotesk";
			case "Poppins" -> "lyrics_poppins";
			case "Audiowide" -> "lyrics_audiowide";
			case "JetBrains Mono" -> "lyrics_mono";
			default -> null;
		};
	}

	static Text fontText(String font, String s) {
		String id = fontId(font);
		return id == null ? Text.literal(s) : MenuKit.inNamedFont(s, id);
	}

	private static Text glyph(String font, int cp) {
		String key = font + '\u0000' + cp;
		Text t = GLYPHS.get(key);
		if (t == null) {
			if (GLYPHS.size() > 4000) {
				GLYPHS.clear();
				WIDTHS.clear();
			}
			t = fontText(font, new String(Character.toChars(cp)));
			GLYPHS.put(key, t);
		}
		return t;
	}

	private static int width(TextRenderer textRenderer, String font, int cp) {
		String key = font + '\u0000' + cp;
		Integer w = WIDTHS.get(key);
		if (w == null) {
			w = Math.max(1, textRenderer.getWidth(glyph(font, cp)));
			WIDTHS.put(key, w);
		}
		return w;
	}

	// ---------------------------------------------------------------- maths

	static float clamp(float v) {
		return v < 0f ? 0f : Math.min(v, 1f);
	}

	static float rand(int seed) {
		int h = seed * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return (h & 0xFFFF) / 65535f;
	}

	private static float easeOutCubic(float p) {
		float q = 1 - p;
		return 1 - q * q * q;
	}

	private static float easeOutBack(float p) {
		float c1 = 1.70158f;
		float c3 = c1 + 1;
		float q = p - 1;
		return 1 + c3 * q * q * q + c1 * q * q;
	}

	private static float easeOutElastic(float p) {
		if (p <= 0f) {
			return 0f;
		}
		if (p >= 1f) {
			return 1f;
		}
		return (float) (Math.pow(2, -10 * p) * Math.sin((p * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
	}

	private static float easeOutBounce(float p) {
		float n1 = 7.5625f;
		float d1 = 2.75f;
		if (p < 1 / d1) {
			return n1 * p * p;
		} else if (p < 2 / d1) {
			p -= 1.5f / d1;
			return n1 * p * p + 0.75f;
		} else if (p < 2.5 / d1) {
			p -= 2.25f / d1;
			return n1 * p * p + 0.9375f;
		}
		p -= 2.625f / d1;
		return n1 * p * p + 0.984375f;
	}

	static int lerpColor(int a, int b, float t) {
		t = clamp(t);
		int aa = (a >>> 24) & 0xFF;
		int ar = (a >> 16) & 0xFF;
		int ag = (a >> 8) & 0xFF;
		int ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF;
		int br = (b >> 16) & 0xFF;
		int bg = (b >> 8) & 0xFF;
		int bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
				| Math.round(ab + (bb - ab) * t);
	}

	/** Called when the style changes: placement starts fresh. */
	static void reset() {
		smoothYaw = Float.NaN;
		fixedCam = null;
		PLACES.clear();
	}
}
