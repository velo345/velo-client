package net.veloclient.velo.client.lyrics;

import net.minecraft.text.Text;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The 3D lyrics in the world pass: every letter becomes text in the world with its own matrix, depth-tested ("Behind
 * Blocks": terrain and clouds cover it, so sky lyrics really sit in the sky) or drawn see-through ("Always In Front").
 */
final class LyricsWorld {

	private static final int FULL_BRIGHT = 0xF000F0;
	private static boolean failed;
	private static long lastNanos;

	/** One letter layer, gathered first and then submitted with the version's queue. */
	private record Piece(Matrix4f pose, Text glyph, float x, float y, int color) {
	}

	private LyricsWorld() {
	}

	/** This frame's letters (empty when Lyrics shows nothing in 3D right now). */
	private static List<Piece> collect() {
		List<Piece> pieces = new ArrayList<>();
		if (!LyricsModule.showing3d()) {
			return pieces;
		}
		double[] cam = LyricsMc.cameraPosition();
		float[] rot = net.veloclient.velo.client.pvp.PvpCompat.cameraRotation();
		if (cam == null || rot == null) {
			return pieces;
		}
		long nanos = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (nanos - lastNanos) / 1e9f);
		lastNanos = nanos;
		LyricsStyle st = LyricsModule.style;
		LyricsRenderer.frame((a, glyph, w, cx, cy, dz, rotation, scaleX, scaleY, color, alpha, style) -> {
			int baseA = Math.round(LyricsRenderer.clamp(alpha) * ((color >>> 24) & 0xFF));
			if (baseA < 5) {
				return;
			}
			float cos = (float) Math.cos(rotation);
			float sin = (float) Math.sin(rotation);
			double s = a.s;
			// the letter's axes: x along the line, y down the letter, z away from the camera
			float xx = (float) ((a.rx * cos - a.ux * sin) * s * scaleX);
			float xy = (float) ((a.ry * cos - a.uy * sin) * s * scaleX);
			float xz = (float) ((a.rz * cos - a.uz * sin) * s * scaleX);
			float yx = (float) ((-a.rx * sin - a.ux * cos) * s * scaleY);
			float yy = (float) ((-a.ry * sin - a.uy * cos) * s * scaleY);
			float yz = (float) ((-a.rz * sin - a.uz * cos) * s * scaleY);
			float zx = (float) (a.nx * s);
			float zy = (float) (a.ny * s);
			float zz = (float) (a.nz * s);
			double px = a.ax + a.rx * cx * s - a.ux * cy * s + a.nx * dz * s;
			double py = a.ay + a.ry * cx * s - a.uy * cy * s + a.ny * dz * s;
			double pz = a.az + a.rz * cx * s - a.uz * cy * s + a.nz * dz * s;
			Matrix4f m = new Matrix4f(xx, xy, xz, 0f, yx, yy, yz, 0f, zx, zy, zz, 0f, (float) px, (float) py, (float) pz, 1f)
					.translate(-w / 2f, -LyricsRenderer.EM / 2f, 0f);
			// shading layers sit a hair behind the letter, so the depth buffer keeps them under it
			double dist = Math.sqrt(px * px + py * py + pz * pz);
			float gap = (float) (Math.max(0.0025, dist * 0.00018) / Math.max(1e-4, s));
			// every shading layer gets its own depth, back to front, so they never fight over the same depth (that
			// showed as grain in big glows); the letter itself stays in front of all of them
			int[] left = {LyricsRenderer.effectLayers(style)};
			float half = LyricsRenderer.EM / 2f;
			LyricsRenderer.effects(style, alpha, (ox, oy, sc, c, al) -> {
				float z = gap * left[0]--;
				int la = Math.round(LyricsRenderer.clamp(al) * ((c >>> 24) & 0xFF));
				if (la >= 5) {
					Matrix4f pose = sc == 1f ? new Matrix4f(m).translate(0f, 0f, z)
							: new Matrix4f(m).translate(w / 2f + ox, half + oy, z).scale(sc, sc, 1f).translate(-w / 2f, -half, 0f);
					pieces.add(new Piece(pose, glyph, sc == 1f ? ox : 0f, sc == 1f ? oy : 0f, (la << 24) | (c & 0xFFFFFF)));
				}
			});
			pieces.add(new Piece(m, glyph, 0f, 0f, (baseA << 24) | (color & 0xFFFFFF)));
		}, st, LyricsModule.studioOpen, new double[] {cam[0], cam[1], cam[2], rot[0]}, 0, 0, dt, true);
		return pieces;
	}

	//? if <26.1 {
	static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (failed) {
				return;
			}
			try {
				submit(context.matrices(), context.commandQueue());
			} catch (RuntimeException | LinkageError e) {
				fail(e);
			}
		});
	}

	private static void submit(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue) {
		List<Piece> pieces = collect();
		if (pieces.isEmpty()) {
			return;
		}
		var mode = LyricsModule.style.seeThrough() ? net.minecraft.client.font.TextRenderer.TextLayerType.SEE_THROUGH
				: net.minecraft.client.font.TextRenderer.TextLayerType.NORMAL;
		for (Piece p : pieces) {
			matrices.push();
			matrices.multiplyPositionMatrix(p.pose());
			queue.submitText(matrices, p.x(), p.y(), p.glyph().asOrderedText(), false, mode, FULL_BRIGHT, p.color(), 0, 0);
			matrices.pop();
		}
	}
	//?} else {
	/*static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (failed) {
				return;
			}
			try {
				submit(context.poseStack(), context.submitNodeCollector());
			} catch (RuntimeException | LinkageError e) {
				fail(e);
			}
		});
	}

	private static void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue) {
		List<Piece> pieces = collect();
		if (pieces.isEmpty()) {
			return;
		}
		var mode = LyricsModule.style.seeThrough() ? net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH
				: net.minecraft.client.gui.Font.DisplayMode.NORMAL;
		for (Piece p : pieces) {
			matrices.pushPose();
			matrices.mulPose(p.pose());
			queue.submitText(matrices, p.x(), p.y(), p.glyph().getVisualOrderText(), false, mode, FULL_BRIGHT, p.color(), 0, 0);
			matrices.popPose();
		}
	}
	*///?}

	private static void fail(Throwable t) {
		if (!failed) {
			failed = true;
			net.veloclient.velo.VeloClient.LOGGER.warn("Lyrics: 3D drawing failed - turned off for this session", t);
		}
	}
}
