package net.veloclient.velo.client.cosmetics.render;

//? if <26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModelLayers;
//? if >=1.21.2 {
import net.minecraft.client.render.entity.model.PlayerCapeModel;
//?}
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.Identifier;
*///?}
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.cosmetics.physics.ClothCape;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws cloaks (the Store's and your own imported ones; internally still "capes") as real cloth: a thin two-sided
 * mesh driven by {@link ClothCape}, a 2D cloth simulation per wearer - you and every other Velo player in view -
 * with air drag, gusts, hem flutter, inertia and collision with the back and the swinging legs. The texture keeps
 * vanilla's 64x32 cape layout (outside, inside and edges), so every cape GIF/PNG works unchanged.
 *
 * <p>Purely client-rendered: the real Minecraft server is never told about this, and players not running Velo
 * Client simply don't see it. Other players get a cloak here when they're online with Velo Client on the Velo
 * Network server and wear a Store cloak ({@link net.veloclient.velo.client.cosmetics.RemoteCapeCache}).
 */
//? if <1.21.2 {
/*public final class CapeFeatureRenderer extends FeatureRenderer<net.minecraft.client.network.AbstractClientPlayerEntity,
		net.minecraft.client.render.entity.model.PlayerEntityModel<net.minecraft.client.network.AbstractClientPlayerEntity>> {
*///?} else if <26.1 {
public final class CapeFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class CapeFeatureRenderer extends RenderLayer<AvatarRenderState, PlayerModel> {
*///?}

	/** Cloak thickness: a fifth of vanilla's 1 px. */
	private static final float THICKNESS = 0.2f / 16;
	/** The player model's scale (15/16) and its offset down from the entity origin. */
	private static final float MODEL_SCALE = 0.9375f;
	private static final float MODEL_DROP = 1.501f;
	private static final long FORGET_NANOS = 5_000_000_000L;

	/**
	 * One cloth per wearer and view: the world and GUI previews (inventory paper doll, Locker, Store) have their own,
	 * keyed apart by how big the pose is; several views of one kind are matched by body yaw. Sharing one cloth between
	 * two poses yanked it between them every frame.
	 */
	private static final Map<Integer, List<ClothCape>> CLOTHS = new HashMap<>();
	private static long lastSweep;
	/** Past this distance (blocks) the cloth steps at half rate - see {@link ClothCape#simulate}. */
	private static final double FAR_SQ = 24 * 24;
	/** Past this distance the cloth isn't simulated at all: it keeps its last shape (a few pixels on screen). */
	private static final double FROZEN_SQ = 48 * 48;

	/** The "Classic" style (Cloak settings): vanilla's own cape model and motion, 1 px thick (1.21.1: none - cloth instead). */
	//? if <1.21.2 {
	/*private final Object classic = null;
	*///?} else {
	private final PlayerCapeModel classic;
	//?}

	//? if <1.21.2 {
	/*public CapeFeatureRenderer(FeatureRendererContext<net.minecraft.client.network.AbstractClientPlayerEntity,
			net.minecraft.client.render.entity.model.PlayerEntityModel<net.minecraft.client.network.AbstractClientPlayerEntity>> context,
			net.minecraft.client.render.entity.EntityRendererFactory.Context models) {
		super(context);
	}

	@Override
	public void render(MatrixStack matrixStack, net.minecraft.client.render.VertexConsumerProvider consumers, int light,
			net.minecraft.client.network.AbstractClientPlayerEntity player, float limbAngle, float limbDistance, float tickDelta,
			float animationProgress, float headYaw, float headPitch) {
		draw(matrixStack, OrderedRenderCommandQueue.of(consumers), light, PlayerEntityRenderState.of(player, tickDelta));
	}
	*///?} else if <26.1 {
	public CapeFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context,
			net.minecraft.client.render.entity.EntityRendererFactory.Context models) {
		super(context);
		this.classic = new PlayerCapeModel(models.getPart(EntityModelLayers.PLAYER_CAPE));
	}

	@Override
	public void render(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light,
			PlayerEntityRenderState state, float limbAngle, float limbDistance) {
		draw(matrixStack, queue, light, state);
	}
	//?}
	//? if <26.1 {

	@SuppressWarnings("unchecked")
	public static void register() {
		CapeManager.loadLibrary();
		// Wavey Capes has no API to register with, but its own fallback renderer picks up whatever the local player's
		// real cape texture resolves to (see LocalPlayerSkinCapeMixin) - registering our own layer on top of that
		// would double-render the cloak, so we step aside and let it take over.
		// (dev build: the layer stays registered and steps aside per frame while Wavey Capes is the picked style)
		if (net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded() && !net.veloclient.velo.client.Preview.ON) {
			return;
		}
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != EntityType.PLAYER || !(entityRenderer instanceof FeatureRendererContext)) {
				return;
			}
			//? if <1.21.2 {
			/*helper.register(new CapeFeatureRenderer((FeatureRendererContext<net.minecraft.client.network.AbstractClientPlayerEntity,
					net.minecraft.client.render.entity.model.PlayerEntityModel<net.minecraft.client.network.AbstractClientPlayerEntity>>) entityRenderer, context));
			*///?} else {
			helper.register(new CapeFeatureRenderer(
					(FeatureRendererContext<PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel>) entityRenderer, context));
			//?}
		});
	}

	private void draw(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state) {
		if (net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule.waveyActive()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		PlayerEntity localPlayer = client.player;
		if (localPlayer != null && state.id == localPlayer.getId()) {
			CapeManager.renderCape().ifPresent(definition -> {
				if (localPlayer.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA) {
					return;
				}
				renderCloak(matrixStack, queue, light, state, definition, definition.physicsPreset());
			});
			return;
		}
		renderRemote(matrixStack, queue, light, state, client);
	}

	/** The other-player counterpart: same cloth, the cloak chosen by {@link net.veloclient.velo.client.network.VeloUserRegistry}. */
	private void renderRemote(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light,
			PlayerEntityRenderState state, MinecraftClient client) {
		if (client.world == null) {
			return;
		}
		net.minecraft.entity.Entity entity = client.world.getEntityById(state.id);
		if (!(entity instanceof PlayerEntity remotePlayer)) {
			return;
		}
		String capeId = net.veloclient.velo.client.network.VeloUserRegistry.capeIdFor(remotePlayer.getUuid(), remotePlayer.getGameProfile().name());
		if (capeId == null || remotePlayer.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA) {
			return;
		}
		net.veloclient.velo.client.cosmetics.RemoteCapeCache.resolve(capeId).ifPresent(definition -> {
			try {
				renderCloak(matrixStack, queue, light, state, definition, CapePhysicsPreset.defaults());
			} catch (RuntimeException e) {
				// Its art couldn't be (re)loaded this frame: load it again from scratch instead of failing every frame.
				net.veloclient.velo.client.cosmetics.RemoteCapeCache.forget(capeId);
			}
		});
	}

	private void renderCloak(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light,
			PlayerEntityRenderState state, CapeDefinition definition, CapePhysicsPreset preset) {
		// A gaze cape (Dragon Eye) gets its own texture per wearer, looking at whoever stands behind them.
		// Not decoded yet (loads in the background): no cloak for these few frames instead of a hitch.
		Identifier base = CapeManager.textureIfReady(definition);
		if (base == null) {
			return;
		}
		Identifier texture = net.veloclient.velo.client.cosmetics.GazeCapes.texture(definition, state.id, base);
		// A chestplate: back and down a little, like vanilla's cape - else the cloak sits inside the armour.
		boolean armour = !state.equippedChestStack.isEmpty();
		if (!net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule.clothPhysics() && this.classic != null) {
			// Classic: vanilla's cape model, posed by vanilla from the render state.
			matrixStack.push();
			if (armour) {
				matrixStack.translate(0.0F, -0.053125F, 0.06875F);
			}
			queue.submitModel(this.classic, state, matrixStack, RenderLayers.entityCutoutNoCull(texture), light, OverlayTexture.DEFAULT_UV, state.outlineColor, null);
			matrixStack.pop();
			return;
		}
		var model = this.getContextModel();
		var body = model.body;
		boolean gui = scaleOf(matrixStack.peek().getPositionMatrix()) > 3;
		float[] geometry = simulate(state.id, gui, state.bodyYaw, state.x, state.y, state.z, body.pitch,
				body.originX, body.originY, body.originZ, Math.max(model.leftLeg.pitch, model.rightLeg.pitch), preset,
				gui ? 0 : state.squaredDistanceToCamera);
		matrixStack.push();
		body.applyTransform(matrixStack);
		if (armour) {
			matrixStack.translate(0.0F, -0.053125F, 0.06875F);
		}
		queue.submitCustom(matrixStack, RenderLayers.entityCutoutNoCull(texture), (entry, consumer) ->
				emit(geometry, entry.getPositionMatrix(), entry.getNormalMatrix(), (x, y, z, u, v, nx, ny, nz) ->
						consumer.vertex(x, y, z, 0xFFFFFFFF, u, v, OverlayTexture.DEFAULT_UV, light, nx, ny, nz)));
		matrixStack.pop();
	}
	//?} else {
	/*public CapeFeatureRenderer(RenderLayerParent<AvatarRenderState, PlayerModel> context, net.minecraft.client.model.geom.EntityModelSet models) {
		super(context);
		this.classic = new PlayerCapeModel(models.bakeLayer(ModelLayers.PLAYER_CAPE));
	}

	public static void register() {
		CapeManager.loadLibrary();
		// (dev build: the layer stays registered and steps aside per frame while Wavey Capes is the picked style)
		if (net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded() && !net.veloclient.velo.client.Preview.ON) {
			return;
		}
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (entityType != EntityType.PLAYER || !(entityRenderer instanceof AvatarRenderer<?> avatarEntityRenderer)) {
				return;
			}
			registrationHelper.register(new CapeFeatureRenderer(avatarEntityRenderer, context.getModelSet()));
		});
	}

	@Override
	public void submit(PoseStack matrixStack, SubmitNodeCollector queue, int light,
			AvatarRenderState state, float limbAngle, float limbDistance) {
		if (net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule.waveyActive()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Player localPlayer = client.player;
		if (localPlayer != null && state.id == localPlayer.getId()) {
			CapeManager.renderCape().ifPresent(definition -> {
				if (localPlayer.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.world.item.Items.ELYTRA) {
					return;
				}
				renderCloak(matrixStack, queue, light, state, definition, definition.physicsPreset());
			});
			return;
		}
		renderRemote(matrixStack, queue, light, state, client);
	}

	private void renderRemote(PoseStack matrixStack, SubmitNodeCollector queue, int light,
			AvatarRenderState state, Minecraft client) {
		if (client.level == null) {
			return;
		}
		net.minecraft.world.entity.Entity entity = client.level.getEntity(state.id);
		if (!(entity instanceof Player remotePlayer)) {
			return;
		}
		String capeId = net.veloclient.velo.client.network.VeloUserRegistry.capeIdFor(remotePlayer.getUUID(), remotePlayer.getGameProfile().name());
		if (capeId == null || remotePlayer.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.world.item.Items.ELYTRA) {
			return;
		}
		net.veloclient.velo.client.cosmetics.RemoteCapeCache.resolve(capeId).ifPresent(definition -> {
			try {
				renderCloak(matrixStack, queue, light, state, definition, CapePhysicsPreset.defaults());
			} catch (RuntimeException e) {
				// Its art couldn't be (re)loaded this frame: load it again from scratch instead of failing every frame.
				net.veloclient.velo.client.cosmetics.RemoteCapeCache.forget(capeId);
			}
		});
	}

	private void renderCloak(PoseStack matrixStack, SubmitNodeCollector queue, int light,
			AvatarRenderState state, CapeDefinition definition, CapePhysicsPreset preset) {
		// A gaze cape (Dragon Eye) gets its own texture per wearer, looking at whoever stands behind them.
		// Not decoded yet (loads in the background): no cloak for these few frames instead of a hitch.
		Identifier base = CapeManager.textureIfReady(definition);
		if (base == null) {
			return;
		}
		Identifier texture = net.veloclient.velo.client.cosmetics.GazeCapes.texture(definition, state.id, base);
		// A chestplate: back and down a little, like vanilla's cape - else the cloak sits inside the armour.
		boolean armour = !state.chestEquipment.isEmpty();
		if (!net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule.clothPhysics()) {
			// Classic: vanilla's cape model, posed by vanilla from the render state.
			matrixStack.pushPose();
			if (armour) {
				matrixStack.translate(0.0F, -0.053125F, 0.06875F);
			}
			queue.submitModel(this.classic, state, matrixStack, RenderTypes.entitySolid(texture), light, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
			matrixStack.popPose();
			return;
		}
		PlayerModel model = this.getParentModel();
		var body = model.body;
		boolean gui = scaleOf(matrixStack.last().pose()) > 3;
		float[] geometry = simulate(state.id, gui, state.bodyRot, state.x, state.y, state.z, body.xRot,
				body.x, body.y, body.z, Math.max(model.leftLeg.xRot, model.rightLeg.xRot), preset,
				gui ? 0 : state.distanceToCameraSq);
		matrixStack.pushPose();
		body.translateAndRotate(matrixStack);
		if (armour) {
			matrixStack.translate(0.0F, -0.053125F, 0.06875F);
		}
		queue.submitCustomGeometry(matrixStack, RenderTypes.entityCutout(texture), (pose, consumer) ->
				emit(geometry, new Matrix4f(pose.pose()), new Matrix3f(pose.normal()), (x, y, z, u, v, nx, ny, nz) ->
						consumer.addVertex(x, y, z, 0xFFFFFFFF, u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz)));
		matrixStack.popPose();
	}
	*///?}

	// ---- Cloth ----

	/**
	 * Steps the wearer's cloth up to now and returns its shape in body space. The frame: the model is drawn at the
	 * entity's position, turned by 180 - body yaw, flipped (y down) and scaled by 15/16, dropped by 1.501, and the
	 * body part adds its pivot and its pitch (sneaking). The body part's own twist (the attack swing turns the torso
	 * back and forth on every hit) is left out on purpose: it's drawn, but the cloth doesn't feel it as a turn - with
	 * it, every click shook the cloak.
	 */
	private static float[] simulate(int id, boolean gui, float bodyYaw, double x, double y, double z, float pitch,
			float pivotX, float pivotY, float pivotZ, float legBack, CapePhysicsPreset preset, double distanceSq) {
		Matrix3f model = new Matrix3f().rotateY((float) Math.toRadians(180 - bodyYaw)).scale(-MODEL_SCALE, -MODEL_SCALE, MODEL_SCALE);
		Vector3f origin = model.transform(new Vector3f(pivotX / 16, pivotY / 16 - MODEL_DROP, pivotZ / 16));
		Matrix3f worldFromBody = new Matrix3f(model).rotateX(pitch);
		ClothCape cloth = cloth(gui ? ~id : id, bodyYaw);
		if (distanceSq > FROZEN_SQ && cloth.ready()) {
			return cloth.snapshot();
		}
		boolean far = distanceSq > FAR_SQ;
		cloth.frame(worldFromBody, x + origin.x, y + origin.y, z + origin.z);
		cloth.simulate(preset, legBack, false, far);
		cloth.lastYaw = bodyYaw;
		return cloth.snapshot();
	}

	/** How much a pose matrix scales: about 1 in the world, tens in a GUI preview (inventory, Locker, Store). */
	private static float scaleOf(Matrix4f m) {
		return (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
	}

	private static ClothCape cloth(int id, float bodyYaw) {
		long now = System.nanoTime();
		if (now - lastSweep > FORGET_NANOS) {
			lastSweep = now;
			CLOTHS.values().forEach(list -> list.removeIf(c -> now - c.lastUsedNanos > FORGET_NANOS));
			CLOTHS.values().removeIf(List::isEmpty);
		}
		List<ClothCape> list = CLOTHS.computeIfAbsent(id, k -> new ArrayList<>(2));
		ClothCape best = null;
		float bestTurn = 40;
		for (ClothCape c : list) {
			float turn = Math.abs(((bodyYaw - c.lastYaw) % 360 + 540) % 360 - 180);
			if (turn < bestTurn) {
				best = c;
				bestTurn = turn;
			}
		}
		if (best == null) {
			if (list.size() >= 3) {
				list.sort((a, b) -> Long.compare(a.lastUsedNanos, b.lastUsedNanos));
				list.remove(0);
			}
			best = new ClothCape(id * 31 + list.size());
			list.add(best);
		}
		return best;
	}

	// ---- Mesh ----

	@FunctionalInterface
	private interface VertexSink {
		void accept(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	/**
	 * Both faces of the cloth (outside = the cape template's 10x16 front at (1,1), inside = its back at (12,1)) and
	 * the thin side and bottom edges, as quads. Template u runs right-to-left across the outside, as on vanilla's
	 * cape model, so every cape texture lands the same way round.
	 */
	private static void emit(float[] g, Matrix4f position, Matrix3f normal, VertexSink sink) {
		int cols = ClothCape.COLS, rows = ClothCape.ROWS;
		float half = THICKNESS / 2;
		Vector3f p = new Vector3f(), n = new Vector3f();
		float[] out = new float[cols * rows * 3], in = new float[cols * rows * 3];
		for (int i = 0; i < cols * rows; i++) {
			for (int k = 0; k < 3; k++) {
				out[i * 3 + k] = g[i * 6 + k] + g[i * 6 + 3 + k] * half;
				in[i * 3 + k] = g[i * 6 + k] - g[i * 6 + 3 + k] * half;
			}
		}
		Emitter e = (pos, i, sign, u, v) -> {
			position.transformPosition(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], p);
			normal.transform(g[i * 6 + 3] * sign, g[i * 6 + 4] * sign, g[i * 6 + 5] * sign, n);
			float len = n.length();
			if (len > 1e-6f) {
				n.div(len);
			}
			sink.accept(p.x, p.y, p.z, u / 64f, v / 32f, n.x, n.y, n.z);
		};
		for (int r = 0; r + 1 < rows; r++) {
			float v0 = 1 + 16f * r / (rows - 1), v1 = 1 + 16f * (r + 1) / (rows - 1);
			for (int c = 0; c + 1 < cols; c++) {
				float s0 = c / (cols - 1f), s1 = (c + 1) / (cols - 1f);
				int a = r * cols + c, b = a + 1, d = a + cols, cc = d + 1;
				// Outside: (b - a) x (c - b) points out, as on vanilla's quads.
				e.vertex(out, a, 1, 11 - 10 * s0, v0);
				e.vertex(out, b, 1, 11 - 10 * s1, v0);
				e.vertex(out, cc, 1, 11 - 10 * s1, v1);
				e.vertex(out, d, 1, 11 - 10 * s0, v1);
				// Inside, wound the other way.
				e.vertex(in, a, -1, 12 + 10 * s0, v0);
				e.vertex(in, d, -1, 12 + 10 * s0, v1);
				e.vertex(in, cc, -1, 12 + 10 * s1, v1);
				e.vertex(in, b, -1, 12 + 10 * s1, v0);
			}
		}
		// Side edges (a sliver of the template's 1 px edge columns) and the hem.
		for (int r = 0; r + 1 < rows; r++) {
			float v0 = 1 + 16f * r / (rows - 1), v1 = 1 + 16f * (r + 1) / (rows - 1);
			int l0 = r * cols, l1 = l0 + cols, r0 = l0 + cols - 1, r1 = r0 + cols;
			e.vertex(in, l0, 1, 11.5f, v0);
			e.vertex(out, l0, 1, 11.5f, v0);
			e.vertex(out, l1, 1, 11.5f, v1);
			e.vertex(in, l1, 1, 11.5f, v1);
			e.vertex(out, r0, 1, 0.5f, v0);
			e.vertex(in, r0, 1, 0.5f, v0);
			e.vertex(in, r1, 1, 0.5f, v1);
			e.vertex(out, r1, 1, 0.5f, v1);
		}
		int last = (rows - 1) * cols;
		for (int c = 0; c + 1 < cols; c++) {
			float u0 = 11 + 10f * c / (cols - 1), u1 = 11 + 10f * (c + 1) / (cols - 1);
			e.vertex(out, last + c, 1, u0, 0.5f);
			e.vertex(in, last + c, 1, u0, 0.5f);
			e.vertex(in, last + c + 1, 1, u1, 0.5f);
			e.vertex(out, last + c + 1, 1, u1, 0.5f);
		}
	}

	@FunctionalInterface
	private interface Emitter {
		void vertex(float[] positions, int index, float normalSign, float u, float v);
	}
}
