package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxEffects;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws playing kill effects ({@link KillFxEffects}) where someone died - glowing quads in the world with the auras'
 * atlas and {@code eyes} render type ({@link AuraFeatureRenderer.Mesh}), submitted after the entities each frame.
 * Effects are purely client-side; each is facing the camera as it was when it started (gravestones, slashes...).
 */
public final class KillFxRenderer {

	/** Full brightness (block and sky light 15). */
	private static final int LIGHT = 0xF000F0;
	private static final int MAX_ACTIVE = 12;

	private static final class Active {
		final KillFxEffects.Burst burst;
		final AuraEffects.Settings settings;
		final double x;
		final double y;
		final double z;
		long lastNanos;

		Active(KillFxEffects.Burst burst, AuraEffects.Settings settings, double x, double y, double z) {
			this.burst = burst;
			this.settings = settings;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private static final List<Active> ACTIVE = new ArrayList<>();

	private KillFxRenderer() {
	}

	/** Starts an effect with the victim's feet at (x, y, z). */
	public static synchronized void spawn(KillFxEffects.Kind kind, AuraEffects.Settings settings, double x, double y, double z) {
		double[] cam = net.veloclient.velo.client.pvp.PvpCompat.cameraPos();
		KillFxEffects.Burst burst = new KillFxEffects.Burst(kind, System.nanoTime()).facing(cam[0] - x, cam[2] - z);
		if (ACTIVE.size() >= MAX_ACTIVE) {
			ACTIVE.remove(0);
		}
		ACTIVE.add(new Active(burst, settings, x, y, z));
	}

	/** This frame's geometry for every playing effect, each relative to the camera; finished ones are dropped. */
	private static synchronized List<Object[]> frame() {
		if (ACTIVE.isEmpty()) {
			return List.of();
		}
		long now = System.nanoTime();
		List<Object[]> out = new ArrayList<>();
		for (int i = ACTIVE.size() - 1; i >= 0; i--) {
			Active a = ACTIVE.get(i);
			float dt = a.lastNanos == 0 ? 0.001f : Math.min(0.1f, (now - a.lastNanos) / 1e9f);
			a.lastNanos = now;
			a.burst.update(dt, a.settings);
			if (a.burst.done()) {
				ACTIVE.remove(i);
				continue;
			}
			AuraFeatureRenderer.Mesh mesh = new AuraFeatureRenderer.Mesh();
			a.burst.draw(a.settings, mesh);
			if (mesh.size > 0) {
				out.add(new Object[] {java.util.Arrays.copyOf(mesh.data, mesh.size), a});
			}
		}
		return out;
	}

	//? if <26.1 {
	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(context ->
				submit(context.matrices(), context.commandQueue()));
	}

	private static void submit(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue) {
		List<Object[]> frame = frame();
		if (frame.isEmpty()) {
			return;
		}
		double[] cam = net.veloclient.velo.client.pvp.PvpCompat.cameraPos();
		var texture = net.minecraft.util.Identifier.of("velo-client", AuraFeatureRenderer.TEXTURE_PATH);
		for (Object[] f : frame) {
			float[] geometry = (float[]) f[0];
			Active a = (Active) f[1];
			matrices.push();
			matrices.translate(a.x - cam[0], a.y - cam[1], a.z - cam[2]);
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(texture), (entry, consumer) ->
					AuraFeatureRenderer.emit(geometry, entry.getPositionMatrix(), (x, y, z, color, u, v) -> consumer.vertex(x, y, z, color, u, v,
							net.minecraft.client.render.OverlayTexture.DEFAULT_UV, LIGHT, 0, 1, 0)));
			matrices.pop();
		}
	}
	//?} else {
	/*public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context ->
				submit(context.poseStack(), context.submitNodeCollector()));
	}

	private static void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue) {
		List<Object[]> frame = frame();
		if (frame.isEmpty()) {
			return;
		}
		double[] cam = net.veloclient.velo.client.pvp.PvpCompat.cameraPos();
		var texture = net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", AuraFeatureRenderer.TEXTURE_PATH);
		for (Object[] f : frame) {
			float[] geometry = (float[]) f[0];
			Active a = (Active) f[1];
			matrices.pushPose();
			matrices.translate(a.x - cam[0], a.y - cam[1], a.z - cam[2]);
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(texture), (pose2, consumer) ->
					AuraFeatureRenderer.emit(geometry, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) -> consumer.addVertex(x, y, z, color, u, v,
							net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, LIGHT, 0, 1, 0)));
			matrices.popPose();
		}
	}
	*///?}
}
