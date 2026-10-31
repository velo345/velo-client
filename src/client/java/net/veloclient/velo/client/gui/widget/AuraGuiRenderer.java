package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws an aura flat in a GUI (Store cards): the same {@link AuraEffects} animation, turned slowly
 * and projected, as tinted glow sprites from the aura atlas around a dim player silhouette - what's
 * behind the player is drawn first, what's in front after. No 3D render target, so a page full of
 * these is cheap.
 */
public final class AuraGuiRenderer {

	private static final Identifier ATLAS = Identifier.of("velo-client", "textures/cosmetics/aura/atlas.png");

	private record Sprite(float x, float y, float depth, float size, int argb, int cell) {
	}

	private AuraGuiRenderer() {
	}

	/**
	 * Advances {@code emitter} (frozen in place: the "player" doesn't move) and draws it with the feet at
	 * ({@code cx}, {@code feetY}), {@code scale} pixels per block.
	 */
	public static void draw(DrawContext context, AuraEffects.Emitter emitter, AuraEffects.Settings settings, float cx, float feetY, float scale) {
		emitter.update(emitter.tick(System.nanoTime()), settings, 0, 0, 0);
		double yaw = System.currentTimeMillis() / 4000.0;
		double pitch = Math.toRadians(14);
		float cos = (float) Math.cos(yaw);
		float sin = (float) Math.sin(yaw);
		List<Sprite> sprites = new ArrayList<>();
		AuraEffects.Out out = new AuraEffects.Out() {
			@Override
			public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
				if (alpha <= 0.02f) {
					return;
				}
				float xr = x * cos - z * sin;
				float zr = x * sin + z * cos;
				float yr = (float) (y * Math.cos(pitch) - zr * Math.sin(pitch));
				int a = Math.round(Math.min(1, alpha) * 255);
				float px = AuraEffects.upright(shape) ? size : size * 0.6f;
				int cell = AuraEffects.cell(shape);
				sprites.add(new Sprite(cx + xr * scale, feetY - yr * scale, zr, px * scale * 2, (a << 24) | (rgb & 0xFFFFFF), cell));
			}

			@Override
			public void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha) {
				if (alpha <= 0.02f) {
					return;
				}
				float xr = x * cos - z * sin;
				float zr = x * sin + z * cos;
				float yr = (float) (y * Math.cos(pitch) - zr * Math.sin(pitch));
				int a = Math.round(Math.min(1, alpha) * 255);
				// Seen edge-on it gets narrow: scale by how much it faces the camera.
				float facing = Math.abs(nx * sin + nz * cos);
				float size = Math.max(w, h) * Math.max(0.35f, facing);
				sprites.add(new Sprite(cx + xr * scale, feetY - yr * scale, zr, size * scale, (a << 24) | (rgb & 0xFFFFFF), cell));
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
		sprites.sort((a, b) -> Float.compare(a.depth, b.depth));
		boolean silhouetteDrawn = false;
		for (Sprite s : sprites) {
			if (!silhouetteDrawn && s.depth > 0) {
				silhouette(context, cx, feetY, scale);
				silhouetteDrawn = true;
			}
			sprite(context, s);
		}
		if (!silhouetteDrawn) {
			silhouette(context, cx, feetY, scale);
		}
	}

	private static void sprite(DrawContext context, Sprite s) {
		int size = Math.max(2, Math.round(s.size));
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, ATLAS, Math.round(s.x - size / 2f), Math.round(s.y - size / 2f),
				(s.cell % AuraEffects.ATLAS_COLUMNS) * (float) AuraEffects.ATLAS_CELL, (s.cell / AuraEffects.ATLAS_COLUMNS) * (float) AuraEffects.ATLAS_CELL, size, size,
				AuraEffects.ATLAS_CELL, AuraEffects.ATLAS_CELL, AuraEffects.ATLAS_CELL * AuraEffects.ATLAS_COLUMNS, AuraEffects.ATLAS_CELL * AuraEffects.ATLAS_ROWS, s.argb);
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
