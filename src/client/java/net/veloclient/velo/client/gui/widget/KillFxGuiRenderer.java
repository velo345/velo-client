package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxEffects;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a kill effect flat in a GUI (Store cards and pages): the same {@link KillFxEffects} animation, seen a little
 * from above and turned, every sprite, beam and quad as an atlas cell stretched onto its projected shape (an affine
 * transform per piece, so beams stay beams and wings stay wings). A dim figure stands there until the hit.
 */
public final class KillFxGuiRenderer {

	private static final Identifier ATLAS = Identifier.of("velo-client", "textures/cosmetics/aura/atlas.png");
	private static final int CELL = AuraEffects.ATLAS_CELL;
	private static final int ATLAS_W = CELL * AuraEffects.ATLAS_COLUMNS;
	private static final int ATLAS_H = CELL * AuraEffects.ATLAS_ROWS;
	private static final double PITCH = Math.toRadians(16);

	/** One piece: the unit square (u right, v down) mapped onto the screen. */
	private record Piece(float depth, Matrix3x2f map, int cell, int argb) {
	}

	/** A looping preview: restarts a moment after the effect ends. */
	public static final class Loop {
		private final KillFxEffects.Kind kind;
		private KillFxEffects.Burst burst;
		private long lastNanos;
		private float idle;
		private boolean restarted;

		public Loop(KillFxEffects.Kind kind) {
			this.kind = kind;
		}

		/** Starts over now. */
		public void restart() {
			burst = null;
			idle = 0.3f;
		}

		/** True once each time the effect starts over (for its sound). */
		public boolean justStarted() {
			boolean r = restarted;
			restarted = false;
			return r;
		}

		KillFxEffects.Burst step(AuraEffects.Settings settings, double yaw) {
			long now = System.nanoTime();
			float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
			lastNanos = now;
			if (burst == null || burst.done()) {
				idle += dt;
				if (burst == null && idle < 0.35f || burst != null && idle < 0.7f) {
					return burst;
				}
				// Facing the GUI's camera.
				burst = new KillFxEffects.Burst(kind, now).facing(Math.sin(yaw), Math.cos(yaw));
				idle = 0;
				restarted = true;
			}
			burst.update(dt, settings);
			return burst;
		}
	}

	private KillFxGuiRenderer() {
	}

	/** Advances {@code loop} and draws it with the victim's feet at ({@code cx}, {@code feetY}), {@code scale} pixels per block. */
	public static void draw(DrawContext context, Loop loop, AuraEffects.Settings settings, float cx, float feetY, float scale, double yaw) {
		KillFxEffects.Burst burst = loop.step(settings, yaw);
		float cos = (float) Math.cos(yaw);
		float sin = (float) Math.sin(yaw);
		float cp = (float) Math.cos(PITCH);
		float sp = (float) Math.sin(PITCH);
		List<Piece> pieces = new ArrayList<>();
		if (burst != null) {
			burst.draw(settings, new AuraEffects.Out() {
				/** Screen x, y and depth (larger = nearer). */
				private float[] project(float x, float y, float z) {
					float xr = x * cos - z * sin;
					float zr = x * sin + z * cos;
					return new float[] {cx + xr * scale, feetY - (y * cp - zr * sp) * scale, zr};
				}

				private void add(float depth, float ox, float oy, float ux, float uy, float vx, float vy, int cell, int rgb, float alpha) {
					if (alpha <= 0.02f) {
						return;
					}
					int a = Math.round(Math.min(1, alpha) * 255);
					pieces.add(new Piece(depth, new Matrix3x2f(ux, uy, vx, vy, ox, oy), cell, (a << 24) | (rgb & 0xFFFFFF)));
				}

				@Override
				public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
					sprite(x, y, z, size, rgb, alpha, shape, 0);
				}

				@Override
				public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape, float rot) {
					if (size <= 0.001f) {
						return;
					}
					float[] p = project(x, y, z);
					float h = size * scale;
					float c = (float) Math.cos(rot);
					float s = (float) Math.sin(rot);
					// Lying flat it's seen from slightly above: squashed.
					float squash = AuraEffects.upright(shape) ? 1 : sp;
					float ux = 2 * h * c;
					float uy = 2 * h * -s * squash;
					float vx = 2 * h * s;
					float vy = 2 * h * c * squash;
					add(p[2], p[0] - (ux + vx) / 2, p[1] - (uy + vy) / 2, ux, uy, vx, vy, AuraEffects.cell(shape), rgb, alpha);
				}

				@Override
				public void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha) {
					float[] a = project(x1, y1, z1);
					float[] b = project(x2, y2, z2);
					float dx = b[0] - a[0];
					float dy = b[1] - a[1];
					float len = (float) Math.sqrt(dx * dx + dy * dy);
					if (len < 0.01f) {
						return;
					}
					float w = width * scale;
					float nx = -dy / len * w;
					float ny = dx / len * w;
					add((a[2] + b[2]) / 2, a[0] - nx / 2, a[1] - ny / 2, nx, ny, dx, dy, 2, rgb, alpha);
				}

				@Override
				public void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha) {
					float tx = -nz * w / 2;
					float tz = nx * w / 2;
					quad(new float[] {x + tx, y + h / 2, z + tz, x + tx, y - h / 2, z + tz, x - tx, y - h / 2, z - tz, x - tx, y + h / 2, z - tz}, cell, rgb, alpha);
				}

				@Override
				public void quad(float[] q, int cell, int rgb, float alpha) {
					float[] c0 = project(q[0], q[1], q[2]);
					float[] c1 = project(q[3], q[4], q[5]);
					float[] c3 = project(q[9], q[10], q[11]);
					add((c0[2] + c1[2] + c3[2]) / 3, c0[0], c0[1], c3[0] - c0[0], c3[1] - c0[1], c1[0] - c0[0], c1[1] - c0[1], cell, rgb, alpha);
				}
			});
		}
		pieces.sort((a, b) -> Float.compare(a.depth, b.depth));
		// The victim, there until the hit.
		float t = burst == null ? 0 : burst.time();
		int figure = Math.round(255 * Math.max(0, 1 - t * 5));
		boolean figureDrawn = figure <= 0;
		for (Piece p : pieces) {
			if (!figureDrawn && p.depth > 0) {
				figure(context, cx, feetY, scale, figure);
				figureDrawn = true;
			}
			context.getMatrices().pushMatrix();
			context.getMatrices().mul(p.map);
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, ATLAS, 0, 0,
					(p.cell % AuraEffects.ATLAS_COLUMNS) * (float) CELL + 0.5f, (p.cell / AuraEffects.ATLAS_COLUMNS) * (float) CELL + 0.5f, 1, 1,
					CELL - 1, CELL - 1, ATLAS_W, ATLAS_H, p.argb);
			context.getMatrices().popMatrix();
		}
		if (!figureDrawn) {
			figure(context, cx, feetY, scale, figure);
		}
	}

	/** A dim player shape, {@code alpha} 0-255. */
	private static void figure(DrawContext context, float cx, float feetY, float scale, int alpha) {
		int a = alpha << 24;
		float px = scale / 16f;
		VeloDraw.fillRounded(context, cx - 4 * px, feetY - 32 * px, 8 * px, 8 * px, px, a | 0x2A2A34);
		VeloDraw.fillRounded(context, cx - 4 * px, feetY - 24 * px, 8 * px, 12 * px, px * 0.6f, a | 0x2A2A34);
		VeloDraw.fillRounded(context, cx - 8 * px, feetY - 24 * px, 4 * px, 12 * px, px * 0.6f, a | 0x24242D);
		VeloDraw.fillRounded(context, cx + 4 * px, feetY - 24 * px, 4 * px, 12 * px, px * 0.6f, a | 0x24242D);
		VeloDraw.fillRounded(context, cx - 4 * px, feetY - 12 * px, 8 * px, 12 * px, px * 0.6f, a | 0x26262F);
	}
}
