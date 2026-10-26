package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.emotes.EmoteGeometry;
import net.veloclient.velo.client.emotes.EmoteLibrary;
import net.veloclient.velo.client.emotes.Emotes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * A small shaded 3D-looking player figure doing an emote, drawn with flat GUI quads - so a Store or Locker grid can
 * animate every card at once (live 3D entity previews are limited to one per screen on Vulkan). The emote's effect
 * track plays along as flat glyphs (hearts, notes, sparkles...). Looping; one-shot emotes rest a moment between plays.
 */
public final class EmoteFigure {

	private static final float REST_SECONDS = 0.9f;
	private static final int SKIN = 0xFFE3B08E;
	private static final int PANTS = 0xFF3D3F86;
	private static final Map<Object, State> STATES = new WeakHashMap<>();
	private static final Random RANDOM = new Random();

	private static final class State {
		final long start = System.nanoTime();
		float firedUntil = -1f;
		final List<Sprite> sprites = new ArrayList<>();
	}

	private record Sprite(String glyph, int color, float x, float y, float vx, float vy, long born, float life) {
	}

	private EmoteFigure() {
	}

	/**
	 * Draws the figure standing on {@code footY}, centred on {@code cx}, {@code scale} GUI units per model pixel.
	 * {@code key} keeps each card's own clock and effects (any object that lives as long as the card).
	 */
	public static void draw(DrawContext context, Object key, String emoteId, float cx, float footY, float scale, int accent) {
		State state = STATES.computeIfAbsent(key, k -> new State());
		EmoteLibrary.Emote emote = EmoteLibrary.get(emoteId);
		float t = (System.nanoTime() - state.start) / 1e9f;
		float[] pose = EmoteGeometry.restPose();
		if (emote != null) {
			float cycle = emote.durationSeconds() + (emote.loop() ? 0f : REST_SECONDS);
			float local = t % cycle;
			if (local <= emote.durationSeconds()) {
				Emotes.poseAt(emote, local * emote.fps(), pose);
			}
			fireEffects(state, emote, t, cycle, pose, cx, footY, scale);
		}
		float yaw = (float) Math.toRadians(28 + Math.sin(t * 0.6) * 12);
		drawPose(context, pose, cx, footY, scale, yaw, accent);
		drawSprites(context, state);
		if (emote == null) {
			// still downloading: a small spinner over the head
			VeloDraw.strokeArc(context, cx, footY - 36 * scale, 4f, 1.5f, (t * 360f) % 360f, 260f, 0xC0FFFFFF);
		}
	}

	/**
	 * Only the flat effects of an emote played by a 3D preview ({@code Emotes.preview()}), laid over it: the preview's
	 * clock, its pose and anchors at {@code scale} GUI units per model pixel, feet on {@code footY}.
	 */
	public static void drawPreviewEffects(DrawContext context, Object key, float cx, float footY, float scale) {
		Emotes.Playing playing = Emotes.preview();
		State state = STATES.computeIfAbsent(key, k -> new State());
		if (playing != null && !playing.stopping()) {
			EmoteLibrary.Emote emote = EmoteLibrary.get(playing.id);
			float t = playing.seconds();
			if (emote != null && t >= 0f) {
				float[] pose = new float[EmoteLibrary.FLOATS_PER_FRAME];
				Emotes.poseAt(emote, (t * emote.fps()) % Math.max(1, emote.frameCount() - 1), pose);
				fireEffects(state, emote, t, emote.durationSeconds(), pose, cx, footY, scale);
			}
		}
		drawSprites(context, state);
	}

	/** The posed boxes, shaded, back to front. */
	public static void drawPose(DrawContext context, float[] pose, float cx, float footY, float scale, float yaw, int accent) {
		float cos = (float) Math.cos(yaw);
		float sin = (float) Math.sin(yaw);
		record Face(float depth, float[] xy, int color) {
		}
		List<Face> faces = new ArrayList<>();
		int shirt = accent | 0xFF000000;
		int[] colors = {SKIN, shirt, SKIN, SKIN, PANTS, PANTS};
		for (int part = 0; part < 6; part++) {
			float[] c = EmoteGeometry.corners(pose, part);
			float[] r = new float[24];
			for (int i = 0; i < 8; i++) {
				float x = c[i * 3];
				float z = c[i * 3 + 2];
				r[i * 3] = x * cos + z * sin;
				r[i * 3 + 1] = c[i * 3 + 1];
				r[i * 3 + 2] = -x * sin + z * cos;
			}
			for (int[] f : EmoteGeometry.FACES) {
				float[] a = {r[f[0] * 3], r[f[0] * 3 + 1], r[f[0] * 3 + 2]};
				float[] b = {r[f[1] * 3], r[f[1] * 3 + 1], r[f[1] * 3 + 2]};
				float[] d = {r[f[3] * 3], r[f[3] * 3 + 1], r[f[3] * 3 + 2]};
				// face normal; the viewer sits at -Z (the player faces it)
				float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
				float vx = d[0] - a[0], vy = d[1] - a[1], vz = d[2] - a[2];
				float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
				float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
				if (len < 1e-4f) {
					continue;
				}
				nx /= len;
				ny /= len;
				nz /= len;
				// make it point out of the box (away from the part's centre)
				float mx = 0, my = 0, mz = 0;
				for (int i = 0; i < 8; i++) {
					mx += r[i * 3] / 8f;
					my += r[i * 3 + 1] / 8f;
					mz += r[i * 3 + 2] / 8f;
				}
				float fx = (a[0] + r[f[2] * 3]) / 2f - mx, fy = (a[1] + r[f[2] * 3 + 1]) / 2f - my, fz = (a[2] + r[f[2] * 3 + 2]) / 2f - mz;
				if (nx * fx + ny * fy + nz * fz < 0) {
					nx = -nx;
					ny = -ny;
					nz = -nz;
				}
				if (nz > 0.02f) {
					continue; // faces away from the viewer
				}
				// light from the upper left front (y is down)
				float light = 0.55f + 0.45f * Math.max(0f, -0.45f * nx - 0.6f * ny - 0.65f * nz);
				float[] xy = new float[8];
				float depth = 0;
				for (int k = 0; k < 4; k++) {
					int corner = f[k];
					xy[k * 2] = cx + r[corner * 3] * scale;
					xy[k * 2 + 1] = footY + (r[corner * 3 + 1] - 24f) * scale;
					depth += r[corner * 3 + 2];
				}
				faces.add(new Face(depth / 4f, xy, shade(colors[part], light)));
			}
		}
		faces.sort(Comparator.comparingDouble(f -> -f.depth()));
		VeloDraw.beginBatch();
		for (Face f : faces) {
			float[] p = f.xy();
			VeloDraw.fillQuad(context, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], f.color());
		}
		VeloDraw.endBatch(context);
	}

	private static int shade(int argb, float light) {
		int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * light));
		int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * light));
		int b = Math.min(255, Math.round((argb & 0xFF) * light));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	// ---- flat effects ----

	private static void fireEffects(State state, EmoteLibrary.Emote emote, float t, float cycle, float[] pose, float cx, float footY, float scale) {
		float from = state.firedUntil;
		state.firedUntil = t;
		if (from < 0f) {
			from = -0.001f;
		}
		int first = (int) Math.floor(Math.max(0f, from) / cycle);
		int last = (int) Math.floor(t / cycle);
		float[] point = new float[3];
		for (int k = first; k <= last; k++) {
			for (EmoteLibrary.Event e : emote.events()) {
				float at = k * cycle + e.t();
				if (e.fx() == null || at <= from || at > t) {
					continue;
				}
				EmoteGeometry.anchor(pose, anchorOf(e.at()), point);
				float ax = cx + point[0] * scale;
				float ay = footY + (point[1] - 24f) * scale;
				int n = Math.min(6, Math.max(1, e.fx().count() / 4));
				for (int i = 0; i < n; i++) {
					String glyph = glyph(e.fx().cells()[RANDOM.nextInt(e.fx().cells().length)], e.fx().text());
					int color = e.fx().colors()[RANDOM.nextInt(e.fx().colors().length)];
					if (color == net.veloclient.velo.client.emotes.EmoteFx.RAINBOW) {
						color = java.awt.Color.HSBtoRGB(RANDOM.nextFloat(), 0.6f, 1f);
					}
					float spread = Math.max(3f, Math.min(1.2f, e.fx().radius() + e.fx().speed() * 0.3f) * 16f * scale);
					state.sprites.add(new Sprite(glyph, 0xFF000000 | color, ax + (RANDOM.nextFloat() - 0.5f) * spread,
							ay + (RANDOM.nextFloat() - 0.5f) * spread, (RANDOM.nextFloat() - 0.5f) * 10f, -10f - RANDOM.nextFloat() * 10f,
							System.nanoTime(), 0.9f + RANDOM.nextFloat() * 0.5f));
				}
			}
		}
		if (state.sprites.size() > 40) {
			state.sprites.subList(0, state.sprites.size() - 40).clear();
		}
	}

	/** Effect anchors the flat figure knows (ground-level ones sit at its feet). */
	private static String anchorOf(String at) {
		if (at != null && at.startsWith("@")) {
			return "chest";
		}
		return switch (at == null ? "chest" : at) {
			case "ground", "front" -> "feet";
			case "above" -> "head";
			default -> at;
		};
	}

	/** A text glyph standing in for an effect sprite on the flat cards. */
	private static String glyph(int cell, String text) {
		return switch (cell) {
			case 3 -> "❤";
			case 4 -> "♪";
			case 5 -> "♫";
			case 7, 0 -> "●";
			case 9 -> "❄";
			case 10 -> "ᴥ";
			case 11 -> "☁";
			case 12 -> "Z";
			case 13 -> "•";
			case 14 -> "▲";
			case 15 -> "■";
			case 16 -> "☠";
			case 17 -> "⚡";
			case 18 -> "✖";
			case 19 -> "@";
			case 21 -> "○";
			case 8 -> "◯";
			default -> cell >= 22 && cell <= 31 ? (text != null && !text.isBlank() ? text.substring(0, 1) : "!") : RANDOM.nextBoolean() ? "✦" : "✧";
		};
	}

	private static void drawSprites(DrawContext context, State state) {
		long now = System.nanoTime();
		var renderer = MinecraftClient.getInstance().textRenderer;
		state.sprites.removeIf(s -> (now - s.born()) / 1e9f > s.life());
		for (Sprite s : state.sprites) {
			float age = (now - s.born()) / 1e9f;
			float fade = 1f - age / s.life();
			int alpha = Math.max(8, Math.round(255 * fade));
			int x = Math.round(s.x() + s.vx() * age);
			int y = Math.round(s.y() + s.vy() * age);
			context.drawText(renderer, s.glyph(), x - renderer.getWidth(s.glyph()) / 2, y - 4, (alpha << 24) | (s.color() & 0xFFFFFF), true);
		}
	}
}
