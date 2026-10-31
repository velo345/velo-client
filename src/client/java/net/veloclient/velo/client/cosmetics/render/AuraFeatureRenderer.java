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

	/** Geometry for one player: x y z u v color per vertex, 4 vertices per quad - every quad both ways round (the render type culls). */
	static final class Mesh implements AuraEffects.Out {
		float[] data = new float[6 * 4 * 1024];
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

		/** Half a texel, so linear filtering never reaches into the neighbouring cell. */
		private static final float INSET_U = 0.5f / (AuraEffects.ATLAS_CELL * AuraEffects.ATLAS_COLUMNS);
		private static final float INSET_V = 0.5f / (AuraEffects.ATLAS_CELL * AuraEffects.ATLAS_ROWS);

		/** A quad from 4 corners (x y z each) showing atlas {@code cell} (8 x 4 grid), seen from both sides. */
		private void quad(float[] c, int cell, int color) {
			float u0 = (cell % AuraEffects.ATLAS_COLUMNS) / (float) AuraEffects.ATLAS_COLUMNS + INSET_U;
			float v0 = (cell / AuraEffects.ATLAS_COLUMNS) / (float) AuraEffects.ATLAS_ROWS + INSET_V;
			float u1 = u0 + 1f / AuraEffects.ATLAS_COLUMNS - 2 * INSET_U;
			float v1 = v0 + 1f / AuraEffects.ATLAS_ROWS - 2 * INSET_V;
			vertex(c[0], c[1], c[2], u0, v0, color);
			vertex(c[3], c[4], c[5], u0, v1, color);
			vertex(c[6], c[7], c[8], u1, v1, color);
			vertex(c[9], c[10], c[11], u1, v0, color);
			vertex(c[9], c[10], c[11], u1, v0, color);
			vertex(c[6], c[7], c[8], u1, v1, color);
			vertex(c[3], c[4], c[5], u0, v1, color);
			vertex(c[0], c[1], c[2], u0, v0, color);
		}

		@Override
		public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape) {
			sprite(x, y, z, size, rgb, alpha, shape, 0);
		}

		@Override
		public void sprite(float x, float y, float z, float size, int rgb, float alpha, int shape, float rot) {
			if (alpha <= 0.01f || size <= 0.001f) {
				return;
			}
			int color = color(rgb, alpha);
			int cell = AuraEffects.cell(shape);
			// Corner offsets turned by rot: (-h, +h) top-left, (-h, -h) bottom-left, (+h, -h), (+h, +h).
			float c = (float) Math.cos(rot) * size;
			float s = (float) Math.sin(rot) * size;
			float ax = -c - s;
			float ay = -s + c;
			float bx = -c + s;
			float by = -s - c;
			// Upright quads: v = 0 at the top of the cell.
			if (AuraEffects.upright(shape)) {
				quad(new float[] {x + ax, y + ay, z, x + bx, y + by, z, x - ax, y - ay, z, x - bx, y - by, z}, cell, color);
				quad(new float[] {x, y + ay, z + ax, x, y + by, z + bx, x, y - ay, z - ax, x, y - by, z - bx}, cell, color);
			}
			// Ground plane: the cell's top towards -z, turned by rot.
			if (AuraEffects.flat(shape)) {
				quad(new float[] {x + ax, y, z - ay, x + bx, y, z - by, x - ax, y, z + ay, x - bx, y, z + by}, cell, color);
			}
		}

		@Override
		public void quad(float[] corners, int cell, int rgb, float alpha) {
			if (alpha > 0.01f) {
				quad(corners, cell, color(rgb, alpha));
			}
		}

		@Override
		public void panel(float x, float y, float z, float w, float h, float nx, float nz, int cell, int rgb, float alpha) {
			if (alpha <= 0.01f) {
				return;
			}
			// Tangent to the ring (left to right seen from outside).
			float tx = -nz * w / 2;
			float tz = nx * w / 2;
			quad(new float[] {x + tx, y + h / 2, z + tz, x + tx, y - h / 2, z + tz, x - tx, y - h / 2, z - tz, x - tx, y + h / 2, z - tz}, cell,
					color(rgb, alpha));
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
				// The beam cell's soft profile runs across u.
				quad(new float[] {x1 - n.x, y1 - n.y, z1 - n.z, x2 - n.x, y2 - n.y, z2 - n.z, x2 + n.x, y2 + n.y, z2 + n.z, x1 + n.x, y1 + n.y, z1 + n.z},
						2, color);
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
		// The ground under them (blocks only - never another player below), for things that lie on it.
		// (No world - the title screen's avatar: its feet are the ground.)
		double ground = net.veloclient.velo.client.pvp.PvpCompat.localPlayer() == null ? y
				: net.veloclient.velo.client.pvp.PvpCompat.groundBelow(x, y + 0.01, z, 24);
		emitter.ground(Double.isNaN(ground) ? Float.NaN : (float) Math.min(0, ground - y));
		float dt = emitter.tick(System.nanoTime());
		emitter.update(dt, worn.settings(), x, y, z);
		Mesh mesh = new Mesh();
		emitter.draw(worn.settings(), x, y, z, mesh);
		return java.util.Arrays.copyOf(mesh.data, mesh.size);
	}

	interface Sink {
		void accept(float x, float y, float z, int color, float u, float v);
	}

	static void emit(float[] g, Matrix4f pose, Sink sink) {
		Vector3f p = new Vector3f();
		for (int i = 0; i < g.length; i += 6) {
			pose.transformPosition(g[i], g[i + 1], g[i + 2], p);
			sink.accept(p.x, p.y, p.z, Float.floatToRawIntBits(g[i + 5]), g[i + 3], g[i + 4]);
		}
	}

	static final String TEXTURE_PATH = "textures/cosmetics/aura/atlas.png";

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
		if (state.invisible) {
			return;
		}
		// The title screen's menu avatar has no entity: it's you.
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.entity.player.PlayerEntity player = menu || client.world == null ? null : client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		AuraManager.Worn worn = self ? AuraManager.wornByLocalPlayer()
				: AuraManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.auraIdFor(player.getUuid(), player.getGameProfile().name()));
		// Auras module: hidden for you or for others (previews always show); calmer particles on others if asked.
		if (!menu && !net.veloclient.velo.client.modules.cosmetics.AuraCosmeticsModule.shows(self)) {
			return;
		}
		if (worn != null && !self && net.veloclient.velo.client.modules.cosmetics.AuraCosmeticsModule.fewerForOthers()) {
			AuraEffects.Settings base = worn.settings();
			worn = new AuraManager.Worn(worn.id(), new AuraEffects.Settings(base.size(), base.speed(), base.density() * 0.5f, base.color()));
		}
		// Light Cosmetics (Performance Boost): fewer particles on everyone.
		if (worn != null && net.veloclient.velo.client.modules.performance.PerformanceBoostModule.lightCosmetics) {
			AuraEffects.Settings base = worn.settings();
			worn = new AuraManager.Worn(worn.id(), new AuraEffects.Settings(base.size(), base.speed(), base.density() * 0.4f, base.color()));
		}
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
		if (state.isInvisible) {
			return;
		}
		// The title screen's menu avatar has no entity: it's you.
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.world.entity.player.Player player = menu || client.level == null ? null : client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		AuraManager.Worn worn = self ? AuraManager.wornByLocalPlayer()
				: AuraManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.auraIdFor(player.getUUID(), player.getGameProfile().name()));
		// Auras module: hidden for you or for others (previews always show); calmer particles on others if asked.
		if (!menu && !net.veloclient.velo.client.modules.cosmetics.AuraCosmeticsModule.shows(self)) {
			return;
		}
		if (worn != null && !self && net.veloclient.velo.client.modules.cosmetics.AuraCosmeticsModule.fewerForOthers()) {
			AuraEffects.Settings base = worn.settings();
			worn = new AuraManager.Worn(worn.id(), new AuraEffects.Settings(base.size(), base.speed(), base.density() * 0.5f, base.color()));
		}
		// Light Cosmetics (Performance Boost): fewer particles on everyone.
		if (worn != null && net.veloclient.velo.client.modules.performance.PerformanceBoostModule.lightCosmetics) {
			AuraEffects.Settings base = worn.settings();
			worn = new AuraManager.Worn(worn.id(), new AuraEffects.Settings(base.size(), base.speed(), base.density() * 0.4f, base.color()));
		}
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
