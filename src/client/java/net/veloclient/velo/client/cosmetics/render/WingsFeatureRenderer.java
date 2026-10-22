package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.cosmetics.wings.WingAnimator;
import net.veloclient.velo.client.cosmetics.wings.WingModel;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws equipped 3D wings on players, Lunar-style: attached to the body (so they follow sneaking
 * and turning), drawn together with any cape (they sit just outside it), animated by
 * {@link WingAnimator} from how the player moves. Your own wings show for you; other players' show
 * when they run Velo Client on the same Velo Network server (their choice comes with the online
 * list). Hidden while an elytra is worn.
 *
 * <p>Each triangle goes out as a quad with its last corner repeated, since the entity render types
 * draw quads.
 */
//? if <26.1 {
public final class WingsFeatureRenderer extends net.minecraft.client.render.entity.feature.FeatureRenderer<
		net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class WingsFeatureRenderer extends net.minecraft.client.renderer.entity.layers.RenderLayer<
		net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> {
*///?}

	/** Reused per frame and per player: the deformed vertices (submitted geometry gets its own copy). */
	private float[][] scratch;

	//? if <26.1 {
	public WingsFeatureRenderer(net.minecraft.client.render.entity.feature.FeatureRendererContext<
			net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context) {
		super(context);
	}

	@SuppressWarnings("unchecked")
	public static void register() {
		WingsManager.load();
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != net.minecraft.entity.EntityType.PLAYER || !(entityRenderer instanceof net.minecraft.client.render.entity.feature.FeatureRendererContext)) {
				return;
			}
			helper.register(new WingsFeatureRenderer((net.minecraft.client.render.entity.feature.FeatureRendererContext<
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
		// The title screen's menu avatar has no entity: it's you, standing still.
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.entity.player.PlayerEntity player = menu || client.world == null ? null
				: client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		net.veloclient.velo.client.cosmetics.wings.WingsPreview.Tag tag = net.veloclient.velo.client.cosmetics.wings.WingsPreview.get(state);
		WingsManager.Worn worn = tag != null ? tag.worn() : self ? WingsManager.wornByLocalPlayer()
				: WingsManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.wingsIdFor(player.getUuid(), player.getGameProfile().name()));
		String id = worn == null ? null : worn.id();
		// Wings module: hidden for you or for others (previews always show).
		if (tag == null && !menu && !net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.shows(self)) {
			return;
		}
		if (id == null || tag == null && player != null && player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA) {
			return;
		}
		var type = WingsManager.type(id).orElse(null);
		WingModel model = type == null ? null : WingsManager.model(id);
		if (model == null) {
			return;
		}
		WingAnimator.Motion motion = net.veloclient.velo.client.cosmetics.wings.WingsPreview.Showcase.IDLE.motion;
		if (player != null) {
			double dx = player.getX() - player.lastX;
			double dz = player.getZ() - player.lastZ;
			motion = new WingAnimator.Motion(Math.sqrt(dx * dx + dz * dz), player.getY() - player.lastY, player.isOnGround(), player.isGliding(),
					player.getAbilities().flying, player.isSneaking(), player.isSwimming() || player.isTouchingWater() && !player.isOnGround());
		}
		if (tag != null && tag.showcase() != null) {
			motion = tag.showcase().motion;
		} else if (!net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.flaps()) {
			// Flapping off: the wings rest as if standing still.
			motion = new WingAnimator.Motion(0, 0, true, false, false, false, false);
		}
		WingAnimator.Pose pose = WingAnimator.pose(tag != null ? tag.key() : state.id, motion, type.style(), worn.settings());
		scratch = WingAnimator.deform(model, pose, worn.settings(), type.offsetY(), scratch);
		float[][] geometry = copy(scratch);
		var texture = WingsManager.texture(id);
		if (texture == null) {
			return; // a server-only pair whose texture is still downloading
		}
		// Neon pairs glow: full brightness whatever the light around.
		int drawLight = type.glow() ? 0xF000F0 : light;
		matrices.push();
		this.getContextModel().body.applyTransform(matrices);
		queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(texture), (entry, consumer) -> {
			Matrix4f position = entry.getPositionMatrix();
			Matrix3f normal = entry.getNormalMatrix();
			emit(model, geometry, position, normal, (x, y, z, u, v, nx, ny, nz) -> consumer.vertex(x, y, z, 0xFFFFFFFF, u, v,
					net.minecraft.client.render.OverlayTexture.DEFAULT_UV, drawLight, nx, ny, nz));
		});
		matrices.pop();
	}
	//?} else {
	/*public WingsFeatureRenderer(net.minecraft.client.renderer.entity.RenderLayerParent<
			net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> context) {
		super(context);
	}

	public static void register() {
		WingsManager.load();
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			// Avatar renderers draw players (and mannequins, which the entity check in submit skips).
			if (!(entityRenderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatarRenderer)) {
				return;
			}
			registrationHelper.register(new WingsFeatureRenderer(avatarRenderer));
		});
	}

	@Override
	public void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (state.isInvisible) {
			return;
		}
		// The title screen's menu avatar has no entity: it's you, standing still.
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.world.entity.player.Player player = menu || client.level == null ? null
				: client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		net.veloclient.velo.client.cosmetics.wings.WingsPreview.Tag tag = net.veloclient.velo.client.cosmetics.wings.WingsPreview.get(state);
		WingsManager.Worn worn = tag != null ? tag.worn() : self ? WingsManager.wornByLocalPlayer()
				: WingsManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.wingsIdFor(player.getUUID(), player.getGameProfile().name()));
		String id = worn == null ? null : worn.id();
		// Wings module: hidden for you or for others (previews always show).
		if (tag == null && !menu && !net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.shows(self)) {
			return;
		}
		if (id == null || tag == null && player != null && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.world.item.Items.ELYTRA) {
			return;
		}
		var type = WingsManager.type(id).orElse(null);
		WingModel model = type == null ? null : WingsManager.model(id);
		if (model == null) {
			return;
		}
		WingAnimator.Motion motion = net.veloclient.velo.client.cosmetics.wings.WingsPreview.Showcase.IDLE.motion;
		if (player != null) {
			double dx = player.getX() - player.xo;
			double dz = player.getZ() - player.zo;
			motion = new WingAnimator.Motion(Math.sqrt(dx * dx + dz * dz), player.getY() - player.yo, player.onGround(), player.isFallFlying(),
					player.getAbilities().flying, player.isShiftKeyDown(), player.isSwimming() || player.isInWater() && !player.onGround());
		}
		if (tag != null && tag.showcase() != null) {
			motion = tag.showcase().motion;
		} else if (!net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.flaps()) {
			// Flapping off: the wings rest as if standing still.
			motion = new WingAnimator.Motion(0, 0, true, false, false, false, false);
		}
		WingAnimator.Pose pose = WingAnimator.pose(tag != null ? tag.key() : state.id, motion, type.style(), worn.settings());
		scratch = WingAnimator.deform(model, pose, worn.settings(), type.offsetY(), scratch);
		float[][] geometry = copy(scratch);
		var texture = WingsManager.texture(id);
		if (texture == null) {
			return; // a server-only pair whose texture is still downloading
		}
		// Neon pairs glow: full brightness whatever the light around.
		int drawLight = type.glow() ? 0xF000F0 : light;
		matrices.pushPose();
		this.getParentModel().body.translateAndRotate(matrices);
		queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture), (pose2, consumer) -> {
			Matrix4f position = new Matrix4f(pose2.pose());
			Matrix3f normal = new Matrix3f(pose2.normal());
			emit(model, geometry, position, normal, (x, y, z, u, v, nx, ny, nz) -> consumer.addVertex(x, y, z, 0xFFFFFFFF, u, v,
					net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, drawLight, nx, ny, nz));
		});
		matrices.popPose();
	}
	*///?}

	@FunctionalInterface
	private interface VertexSink {
		void accept(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	private static float[][] copy(float[][] source) {
		float[][] out = new float[source.length][];
		for (int i = 0; i < source.length; i++) {
			out[i] = source[i].clone();
		}
		return out;
	}

	/** Every triangle of both halves as a quad (last corner twice), transformed by the pose. */
	private static void emit(WingModel model, float[][] geometry, Matrix4f position, Matrix3f normal, VertexSink sink) {
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			float[] g = geometry[h];
			int[] tris = half.triangles;
			for (int t = 0; t < tris.length; t += 3) {
				for (int corner = 0; corner < 4; corner++) {
					int v = tris[t + Math.min(corner, 2)];
					int i = v * 6;
					position.transformPosition(g[i], g[i + 1], g[i + 2], p);
					normal.transform(g[i + 3], g[i + 4], g[i + 5], n);
					float length = n.length();
					if (length > 1e-6f) {
						n.div(length);
					}
					sink.accept(p.x, p.y, p.z, half.uv[v * 2], half.uv[v * 2 + 1], n.x, n.y, n.z);
				}
			}
		}
	}
}
