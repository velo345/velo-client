package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws an aura flat in a GUI (Store cards, Locker tiles): the same {@link AuraEffects} animation, turned slowly
 * and projected, as tinted glow sprites from the aura atlas around a dim player silhouette - what's
 * behind the player is drawn first, what's in front after. No 3D render target, so a page full of
 * these is cheap.
 *
 * <p>Kept cheap on purpose - a Locker page has a dozen of these on screen at once: each card simulates at
 * {@link #STEPS_PER_SECOND} and redraws its last frame in between, freezes while a list is scrolling, keeps only
 * the brightest {@link #budget} sprites for its size, and builds frames into reused arrays (no garbage per frame).
 * Emitters come from {@link #shared} so the tiles a screen rebuilds after every click carry on warm instead of
 * starting empty.
 */
public final class AuraGuiRenderer {

	private static final Identifier ATLAS = Identifier.of("velo-client", "textures/cosmetics/aura/atlas.png");
	private static final int STEPS_PER_SECOND = 30;
	private static final long STEP_NANOS = 1_000_000_000L / STEPS_PER_SECOND;
	/** Marks a sprite lying flat (kept next to its atlas cell), drawn {@link #FLAT_SQUASH} as tall as wide. */
	private static final int FLAT_BIT = 1 << 16;
	private static final float FLAT_SQUASH = 0.32f;

	/** One card's last projected frame, in blocks around the feet (so it can be redrawn wherever the card moved to). */
	private static final class Frame {
		int count = -1;
		long stepped;
		float[] x = new float[128];
		float[] y = new float[128];
		float[] depth = new float[128];
		float[] size = new float[128];
		int[] argb = new int[128];
		int[] cell = new int[128];
		long[] order = new long[128];

		void add(float px, float py, float pz, float s, int color, int c) {
			if (count == x.length) {
				int n = count * 2;
				x = java.util.Arrays.copyOf(x, n);
				y = java.util.Arrays.copyOf(y, n);
				depth = java.util.Arrays.copyOf(depth, n);
				size = java.util.Arrays.copyOf(size, n);
				argb = java.util.Arrays.copyOf(argb, n);
				cell = java.util.Arrays.copyOf(cell, n);
				order = new long[n];
			}
			x[count] = px;
			y[count] = py;
			depth[count] = pz;
			size[count] = s;
			argb[count] = color;
			cell[count] = c;
			count++;
		}
	}

	private static final Map<AuraEffects.Emitter, Frame> FRAMES = new WeakHashMap<>();
	private static final Map<String, AuraEffects.Emitter> SHARED = new java.util.LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, AuraEffects.Emitter> eldest) {
			return size() > 64;
		}
	};

	private AuraGuiRenderer() {
	}

	/** A warm emitter for a GUI card of aura {@code id} worn with {@code settings}, kept across screen rebuilds. */
	public static AuraEffects.Emitter shared(String id, AuraEffects.Kind kind, AuraEffects.Settings settings) {
		return SHARED.computeIfAbsent(id + "|" + kind + "|" + settings.encode(), k -> {
			AuraEffects.Emitter emitter = new AuraEffects.Emitter(kind);
			// A second in, so the card opens with the aura already there.
			for (int i = 0; i < STEPS_PER_SECOND; i++) {
				emitter.update(1f / STEPS_PER_SECOND, settings, 0, 0, 0);
			}
			return emitter;
		});
	}

	/** How many sprites a card {@code scale} pixels per block shows: small tiles can't show more than this anyway. */
	private static int budget(float scale) {
		return Math.max(70, Math.min(260, Math.round(scale * 3.2f)));
	}

	/**
	 * Advances {@code emitter} (frozen in place: the "player" doesn't move) and draws it with the feet at
	 * ({@code cx}, {@code feetY}), {@code scale} pixels per block.
	 */
	public static void draw(DrawContext context, AuraEffects.Emitter emitter, AuraEffects.Settings settings, float cx, float feetY, float scale) {
		Frame frame = FRAMES.computeIfAbsent(emitter, e -> new Frame());
		long now = System.nanoTime();
		if (frame.count < 0 || (now - frame.stepped >= STEP_NANOS && !VeloScrollRegion.scrolling())) {
			frame.stepped = now;
			step(frame, emitter, settings, now, budget(scale), 1.2f / Math.max(1f, scale));
		}
		boolean silhouetteDrawn = false;
		for (int k = 0; k < frame.count; k++) {
			int i = (int) frame.order[k];
			if (!silhouetteDrawn && frame.depth[i] > 0) {
				silhouette(context, cx, feetY, scale);
				silhouetteDrawn = true;
			}
			sprite(context, cx + frame.x[i] * scale, feetY - frame.y[i] * scale, frame.size[i] * scale, frame.argb[i], frame.cell[i]);
		}
		if (!silhouetteDrawn) {
			silhouette(context, cx, feetY, scale);
		}
	}

	/** One simulation step, projected into {@code frame}, trimmed to {@code budget} sprites and sorted back to front. */
	private static void step(Frame frame, AuraEffects.Emitter emitter, AuraEffects.Settings settings, long now, int budget, float minSize) {
		emitter.update(emitter.tick(now), settings, 0, 0, 0);
		double yaw = System.currentTimeMillis() / 4000.0;
		float cosP = (float) Math.cos(Math.toRadians(14));
		float sinP = (float) Math.sin(Math.toRadians(14));
		float cos = (float) Math.cos(yaw);
		float sin = (float) Math.sin(yaw);
		frame.count = 0;
		AuraEffects.Out out = new AuraEffects.Out() {
			@Override
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
				if (alpha <= 0.02f) {
					return;
				}
				float px = size * 2;
				if (px < minSize) {
					return;
				}
				float xr = x * cos - z * sin;
				float zr = x * sin + z * cos;
				float yr = y * cosP - zr * sinP;
				int a = Math.round(Math.min(1, alpha) * 255);
				// Rings and discs lying flat (a halo, the glow at your feet) are seen from slightly above: squashed, not standing up.
				frame.add(xr, yr, zr, px, (a << 24) | (rgb & 0xFFFFFF), AuraEffects.cell(shape) | (AuraEffects.upright(shape) ? 0 : FLAT_BIT));
			}

			@Override
			public void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha) {
				if (alpha <= 0.02f) {
					return;
				}
				// Seen edge-on it gets narrow: scale by how much it faces the camera.
				float facing = Math.abs(nx * sin + nz * cos);
				float size = Math.max(w, h) * Math.max(0.35f, facing);
				if (size < minSize) {
					return;
				}
				float xr = x * cos - z * sin;
				float zr = x * sin + z * cos;
				float yr = y * cosP - zr * sinP;
				int a = Math.round(Math.min(1, alpha) * 255);
				frame.add(xr, yr, zr, size, (a << 24) | (rgb & 0xFFFFFF), cell);
			}

			@Override
			public void quad(float[] c, int cell, int rgb, float alpha) {
				float x = (c[0] + c[3] + c[6] + c[9]) / 4;
				float y = (c[1] + c[4] + c[7] + c[10]) / 4;
				float z = (c[2] + c[5] + c[8] + c[11]) / 4;
				float w = (float) Math.sqrt((c[9] - c[0]) * (c[9] - c[0]) + (c[10] - c[1]) * (c[10] - c[1]) + (c[11] - c[2]) * (c[11] - c[2]));
				float h = (float) Math.sqrt((c[3] - c[0]) * (c[3] - c[0]) + (c[4] - c[1]) * (c[4] - c[1]) + (c[5] - c[2]) * (c[5] - c[2]));
				panel(x, y, z, w, h, 0, 1, cell, rgb, alpha);
			}

			@Override
			public void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha) {
				for (int i = 0; i <= 5; i++) {
					float t = i / 5f;
					sprite(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, width * 1.4f, rgb, alpha * 0.6f, AuraEffects.GLOW);
				}
			}
		};
		emitter.draw(settings, 0, 0, 0, out);
		int n = frame.count;
		long[] order = frame.order;
		if (n > budget) {
			// Keep the ones that show most (bright and big), dropping faint specks first.
			for (int i = 0; i < n; i++) {
				// Non-negative floats sort like their bits: biggest weight first.
				float weight = (frame.argb[i] >>> 24) * frame.size[i];
				order[i] = (long) (Integer.MAX_VALUE - Float.floatToIntBits(weight)) << 32 | i;
			}
			java.util.Arrays.sort(order, 0, n);
			n = budget;
			for (int k = 0; k < n; k++) {
				order[k] = (int) order[k];
			}
		} else {
			for (int i = 0; i < n; i++) {
				order[i] = i;
			}
		}
		// Back to front by depth (sortable float bits in the high half, sprite index in the low half).
		for (int k = 0; k < n; k++) {
			int i = (int) order[k];
			int bits = Float.floatToIntBits(frame.depth[i]);
			long key = bits < 0 ? ~bits & 0xFFFFFFFFL : (bits | 0x80000000L) & 0xFFFFFFFFL;
			order[k] = key << 32 | i;
		}
		java.util.Arrays.sort(order, 0, n);
		for (int k = 0; k < n; k++) {
			order[k] = order[k] & 0xFFFFFFFFL;
		}
		frame.count = n;
	}

	private static void sprite(DrawContext context, float x, float y, float s, int argb, int cellAndFlags) {
		int cell = cellAndFlags & 0xFFFF;
		int size = Math.max(2, Math.round(s));
		int height = (cellAndFlags & FLAT_BIT) != 0 ? Math.max(1, Math.round(s * FLAT_SQUASH)) : size;
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, ATLAS, Math.round(x - size / 2f), Math.round(y - height / 2f),
				(cell % AuraEffects.ATLAS_COLUMNS) * (float) AuraEffects.ATLAS_CELL, (cell / AuraEffects.ATLAS_COLUMNS) * (float) AuraEffects.ATLAS_CELL, size, height,
				AuraEffects.ATLAS_CELL, AuraEffects.ATLAS_CELL, AuraEffects.ATLAS_CELL * AuraEffects.ATLAS_COLUMNS, AuraEffects.ATLAS_CELL * AuraEffects.ATLAS_ROWS, argb);
	}

	/** A dim, simple player shape for scale. */
	private static void silhouette(DrawContext context, float cx, float feetY, float scale) {
		int color = 0xFF2A2A34;
		float px = scale / 16f;
		VeloDraw.fillRounded(context, cx - 4 * px, feetY - 32 * px, 8 * px, 8 * px, px, color);
		VeloDraw.fillRounded(context, cx - 4 * px, feetY - 24 * px, 8 * px, 12 * px, px * 0.6f, color);
		VeloDraw.fillRounded(context, cx - 8 * px, feetY - 24 * px, 4 * px, 12 * px, px * 0.6f, 0xFF24242D);
		VeloDraw.fillRounded(context, cx + 4 * px, feetY - 24 * px, 4 * px, 12 * px, px * 0.6f, 0xFF24242D);
		VeloDraw.fillRounded(context, cx - 4 * px, feetY - 12 * px, 8 * px, 12 * px, px * 0.6f, 0xFF26262F);
	}
}
