package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws equipped auras ({@link AuraEffects}) around players: your own, and other Velo Client
 * players' (their choice comes with the online list). Everything is glowing quads with the
 * {@code eyes} render type (full-bright, translucent, no depth writes) using one small atlas: a
 * soft glow, a four-pointed star and a beam profile. Glows are three crossed quads, so they look
 * round from any angle - no camera needed, which also makes them work in the GUI's 3D previews.
 *
 * <p>The feature renderer's space is the body's (turned with the body, upside down, 1.501 blocks
 * up); {@link #toWorld} undoes that so the aura is drawn in world directions around the feet and
 * doesn't spin when you turn.
 */
//? if <26.1 {
public final class AuraFeatureRenderer extends net.minecraft.client.render.entity.feature.FeatureRenderer<
		net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class AuraFeatureRenderer extends net.minecraft.client.renderer.entity.layers.RenderLayer<
		net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> {
*///?}

	/** Full brightness (block and sky light 15). */
	private static final int LIGHT = 0xF000F0;
	/** Atlas cells: glow, star, beam - each a third of the texture. */
	private static final float CELL = 1f / 3f;

	/** Geometry for one player: x y z u v color per vertex, 4 vertices per quad. */
	private static final class Mesh implements AuraEffects.Out {
		float[] data = new float[6 * 4 * 512];
		int size;

		void vertex(float x, float y, float z, float u, float v, int color) {
			if (size + 6 > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			data[size++] = x;
			data[size++] = y;
			data[size++] = z;
			data[size++] = u;
			data[size++] = v;
			data[size++] = Float.intBitsToFloat(color);
		}

		/** Full colour, faded by alpha (the eyes render type blends translucently; the atlas is white with soft alpha). */
		private static int color(int rgb, float alpha) {
			int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
			return (a << 24) | (rgb & 0xFFFFFF);
		}

		private void quad(float[] c, float u0, int color) {
			// c: 4 corners (x y z), mapped to the cell's corners.
			vertex(c[0], c[1], c[2], u0, 0, color);
			vertex(c[3], c[4], c[5], u0, 1, color);
			vertex(c[6], c[7], c[8], u0 + CELL, 1, color);
			vertex(c[9], c[10], c[11], u0 + CELL, 0, color);
		}

		@Override
		public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
			if (alpha <= 0.01f || size <= 0.001f) {
				return;
			}
			int color = color(rgb, alpha);
			float h = size;
			float u0 = shape == AuraEffects.STAR ? CELL : 0;
			// Ground plane.
			quad(new float[] {x - h, y, z - h, x - h, y, z + h, x + h, y, z + h, x + h, y, z - h}, u0, color);
			if (shape == AuraEffects.DISC) {
				return;
			}
			quad(new float[] {x - h, y - h, z, x - h, y + h, z, x + h, y + h, z, x + h, y - h, z}, u0, color);
			quad(new float[] {x, y - h, z - h, x, y + h, z - h, x, y + h, z + h, x, y - h, z + h}, u0, color);
		}

		@Override
		public void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha) {
			if (alpha <= 0.01f) {
				return;
			}
			Vector3f d = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
			if (d.lengthSquared() < 1e-8f) {
				return;
			}
			Vector3f n1 = new Vector3f(d).cross(0, 1, 0);
			if (n1.lengthSquared() < 1e-6f) {
				n1.set(1, 0, 0);
			}
			n1.normalize(width / 2);
			Vector3f n2 = new Vector3f(d).cross(n1).normalize(width / 2);
			int color = color(rgb, alpha);
			for (Vector3f n : new Vector3f[] {n1, n2}) {
				// u runs across the beam (the soft profile), v along it.
				vertex(x1 - n.x, y1 - n.y, z1 - n.z, 2 * CELL, 0, color);
				vertex(x2 - n.x, y2 - n.y, z2 - n.z, 2 * CELL, 1, color);
				vertex(x2 + n.x, y2 + n.y, z2 + n.z, 1f, 1, color);
				vertex(x1 + n.x, y1 + n.y, z1 + n.z, 1f, 0, color);
			}
		}
	}

	/** Builds this frame's geometry for a player (or null when they wear no aura). */
	private static float[] geometry(Object key, AuraManager.Worn worn, double x, double y, double z) {
		AuraEffects.Kind kind = worn == null ? null : AuraEffects.kind(worn.id());
		if (kind == null) {
			return null;
		}
		AuraEffects.Emitter emitter = AuraManager.emitter(key, kind);
		float dt = emitter.tick(System.nanoTime());
		emitter.update(dt, worn.settings(), x, y, z);
		Mesh mesh = new Mesh();
		emitter.draw(worn.settings(), x, y, z, mesh);
		return java.util.Arrays.copyOf(mesh.data, mesh.size);
	}

	private interface Sink {
		void accept(float x, float y, float z, int color, float u, float v);
	}

	private static void emit(float[] g, Matrix4f pose, Sink sink) {
		Vector3f p = new Vector3f();
		for (int i = 0; i < g.length; i += 6) {
			pose.transformPosition(g[i], g[i + 1], g[i + 2], p);
			sink.accept(p.x, p.y, p.z, Float.floatToRawIntBits(g[i + 5]), g[i + 3], g[i + 4]);
		}
	}

	private static final String TEXTURE_PATH = "textures/cosmetics/aura/atlas.png";

	//? if <26.1 {
	public AuraFeatureRenderer(net.minecraft.client.render.entity.feature.FeatureRendererContext<
			net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context) {
		super(context);
	}

	@SuppressWarnings("unchecked")
	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != net.minecraft.entity.EntityType.PLAYER || !(entityRenderer instanceof net.minecraft.client.render.entity.feature.FeatureRendererContext)) {
				return;
			}
			helper.register(new AuraFeatureRenderer((net.minecraft.client.render.entity.feature.FeatureRendererContext<
					net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel>) entityRenderer));
		});
	}

	@Override
	public void render(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light,
			net.minecraft.client.render.entity.state.PlayerEntityRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world == null || state.invisible) {
			return;
		}
		if (!(client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity player)) {
			return;
		}
		AuraManager.Worn worn = player == client.player ? AuraManager.wornByLocalPlayer()
				: AuraManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.auraIdFor(player.getUuid(), player.getGameProfile().name()));
		float[] geometry = geometry(state.id, worn, state.x, state.y, state.z);
		if (geometry == null || geometry.length == 0) {
			return;
		}
		matrices.push();
		toWorld(matrices, state.bodyYaw);
		var texture = net.minecraft.util.Identifier.of("velo-client", TEXTURE_PATH);
		queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(texture), (entry, consumer) ->
				emit(geometry, entry.getPositionMatrix(), (x, y, z, color, u, v) -> consumer.vertex(x, y, z, color, u, v,
						net.minecraft.client.render.OverlayTexture.DEFAULT_UV, LIGHT, 0, 1, 0)));
		matrices.pop();
	}

	/** Body space -> world directions at the feet: undoes the renderer's 1.501 offset, body turn and flip. */
	private static void toWorld(net.minecraft.client.util.math.MatrixStack matrices, float bodyYaw) {
		matrices.translate(0, 1.501f, 0);
		matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(180f - bodyYaw));
		matrices.scale(-1, -1, 1);
	}
	//?} else {
	/*public AuraFeatureRenderer(net.minecraft.client.renderer.entity.RenderLayerParent<
			net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> context) {
		super(context);
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (!(entityRenderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatarRenderer)) {
				return;
			}
			registrationHelper.register(new AuraFeatureRenderer(avatarRenderer));
		});
	}

	@Override
	public void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null || state.isInvisible) {
			return;
		}
		if (!(client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) {
			return;
		}
		AuraManager.Worn worn = player == client.player ? AuraManager.wornByLocalPlayer()
				: AuraManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.auraIdFor(player.getUUID(), player.getGameProfile().name()));
		float[] geometry = geometry(state.id, worn, state.x, state.y, state.z);
		if (geometry == null || geometry.length == 0) {
			return;
		}
		matrices.pushPose();
		toWorld(matrices, state.bodyRot);
		var texture = net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", TEXTURE_PATH);
		queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(texture), (pose2, consumer) ->
				emit(geometry, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) -> consumer.addVertex(x, y, z, color, u, v,
						net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, LIGHT, 0, 1, 0)));
		matrices.popPose();
	}

	private static void toWorld(com.mojang.blaze3d.vertex.PoseStack matrices, float bodyRot) {
		matrices.translate(0, 1.501f, 0);
		// Straight on the pose matrix: PoseStack's rotate method is named differently on 26.3.
		matrices.last().pose().rotateY((float) Math.toRadians(180f - bodyRot));
		matrices.scale(-1, -1, 1);
	}
	*///?}
}
