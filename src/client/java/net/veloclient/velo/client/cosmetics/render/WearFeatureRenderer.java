package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.cosmetics.wear.WearManager;
import net.veloclient.velo.client.emotes.EmoteProps;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws bodywear ({@link WearManager}) on players: each item rides on its bone of the player model (head, body, an arm
 * or both legs), so it follows walking, sneaking and looking around. Yours show for you, other players' when they run
 * Velo Client (their choice comes with the online list). Hats and ears give way to a helmet, shoes to boots; on slim
 * skins arm items are squeezed to the thinner arm. Chains swing a little as you move; glasses, masks and back items can sit a little lower or higher, glasses and masks nearer or further.
 *
 * <p>Meshes are in the bone's space in skin pixels (y down, the face towards -z); each triangle goes out as a quad with
 * its last corner repeated, since the entity render types draw quads.
 */
//? if <26.1 {
public final class WearFeatureRenderer extends net.minecraft.client.render.entity.feature.FeatureRenderer<
		net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class WearFeatureRenderer extends net.minecraft.client.renderer.entity.layers.RenderLayer<
		net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> {
*///?}

	//? if <26.1 {
	public WearFeatureRenderer(net.minecraft.client.render.entity.feature.FeatureRendererContext<
			net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context) {
		super(context);
	}

	@SuppressWarnings("unchecked")
	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != net.minecraft.entity.EntityType.PLAYER || !(entityRenderer instanceof net.minecraft.client.render.entity.feature.FeatureRendererContext)) {
				return;
			}
			helper.register(new WearFeatureRenderer((net.minecraft.client.render.entity.feature.FeatureRendererContext<
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
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.entity.player.PlayerEntity player = menu || client.world == null ? null
				: client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		if (net.veloclient.velo.client.devtools.CosmeticsBench.hideWear) {
			return;
		}
		if (!self && player != null && client.player != null && velo$far(player, client.player)) {
			return; // someone else's bodywear 32+ blocks away is a pixel or two: not worth thousands of vertices
		}
		java.util.List<WearManager.Worn> worn = self ? WearManager.wornByLocalPlayer()
				: WearManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.wearFor(player.getUuid(), player.getGameProfile().name()));
		if (worn.isEmpty()) {
			return;
		}
		boolean helmet = player != null && !player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isEmpty();
		boolean boots = player != null && !player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET).isEmpty();
		boolean slim = state.skinTextures != null && state.skinTextures.model() == net.minecraft.entity.player.PlayerSkinType.SLIM;
		var model = this.getContextModel();
		float[] swing = player != null && worn.stream().anyMatch(w -> WearManager.type(w.id()).map(t -> "neck".equals(t.slot())).orElse(false))
				? net.veloclient.velo.client.cosmetics.wear.NeckSwing.update(state.id, state.x, state.y, state.z, state.bodyYaw) : null;
		for (WearManager.Worn item : worn) {
			WearManager.WearType type = WearManager.type(item.id()).orElse(null);
			WearManager.Model art = type == null ? null : WearManager.model(item.id());
			var texture = art == null ? null : WearManager.texture(item.id(), item.color());
			if (texture == null || hidden(type.slot(), helmet, boots)) {
				continue;
			}
			for (int side = 0; side < (type.mirrorBone() != null ? 2 : 1); side++) {
				String boneName = side == 0 ? type.bone() : type.mirrorBone();
				EmoteProps.Mesh mesh = side == 0 ? art.mesh() : art.mirrored();
				net.minecraft.client.model.ModelPart bone = switch (boneName) {
					case "body" -> model.body;
					case "rightArm" -> model.rightArm;
					case "leftArm" -> model.leftArm;
					case "rightLeg" -> model.rightLeg;
					case "leftLeg" -> model.leftLeg;
					default -> model.head;
				};
				float squeeze = slim ? armSqueeze(boneName) : 0;
				matrices.push();
				bone.applyTransform(matrices);
				if (item.offset() != 0 || item.depth() != 0) {
					// skin pixels: down, and out from the face (-z)
					matrices.translate(0, item.offset() / 64f, -item.depth() / 64f);
				}
				if (swing != null && "neck".equals(type.slot())) {
					swing(matrices.peek().getPositionMatrix(), matrices.peek().getNormalMatrix(), swing);
				}
				queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(texture), (entry, consumer) ->
						emit(mesh, squeeze, entry.getPositionMatrix(), entry.getNormalMatrix(), (x, y, z, u, v, nx, ny, nz) -> consumer.vertex(x, y, z, 0xFFFFFFFF, u, v,
								net.minecraft.client.render.OverlayTexture.DEFAULT_UV, light, nx, ny, nz)));
				matrices.pop();
			}
		}
	}
	//?} else {
	/*public WearFeatureRenderer(net.minecraft.client.renderer.entity.RenderLayerParent<
			net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> context) {
		super(context);
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (!(entityRenderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatarRenderer)) {
				return;
			}
			registrationHelper.register(new WearFeatureRenderer(avatarRenderer));
		});
	}

	@Override
	public void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (state.isInvisible) {
			return;
		}
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.world.entity.player.Player player = menu || client.level == null ? null
				: client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		if (net.veloclient.velo.client.devtools.CosmeticsBench.hideWear) {
			return;
		}
		if (!self && player != null && client.player != null && velo$far(player, client.player)) {
			return; // someone else's bodywear 32+ blocks away is a pixel or two: not worth thousands of vertices
		}
		java.util.List<WearManager.Worn> worn = self ? WearManager.wornByLocalPlayer()
				: WearManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.wearFor(player.getUUID(), player.getGameProfile().name()));
		if (worn.isEmpty()) {
			return;
		}
		boolean helmet = player != null && !player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
		boolean boots = player != null && !player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty();
		boolean slim = state.skin != null && state.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		var model = this.getParentModel();
		float[] swing = player != null && worn.stream().anyMatch(w -> WearManager.type(w.id()).map(t -> "neck".equals(t.slot())).orElse(false))
				? net.veloclient.velo.client.cosmetics.wear.NeckSwing.update(state.id, state.x, state.y, state.z, state.bodyRot) : null;
		for (WearManager.Worn item : worn) {
			WearManager.WearType type = WearManager.type(item.id()).orElse(null);
			WearManager.Model art = type == null ? null : WearManager.model(item.id());
			var texture = art == null ? null : WearManager.texture(item.id(), item.color());
			if (texture == null || hidden(type.slot(), helmet, boots)) {
				continue;
			}
			for (int side = 0; side < (type.mirrorBone() != null ? 2 : 1); side++) {
				String boneName = side == 0 ? type.bone() : type.mirrorBone();
				EmoteProps.Mesh mesh = side == 0 ? art.mesh() : art.mirrored();
				net.minecraft.client.model.geom.ModelPart bone = switch (boneName) {
					case "body" -> model.body;
					case "rightArm" -> model.rightArm;
					case "leftArm" -> model.leftArm;
					case "rightLeg" -> model.rightLeg;
					case "leftLeg" -> model.leftLeg;
					default -> model.head;
				};
				float squeeze = slim ? armSqueeze(boneName) : 0;
				matrices.pushPose();
				bone.translateAndRotate(matrices);
				if (item.offset() != 0 || item.depth() != 0) {
					// skin pixels: down, and out from the face (-z)
					matrices.translate(0, item.offset() / 64f, -item.depth() / 64f);
				}
				if (swing != null && "neck".equals(type.slot())) {
					swing(matrices.last().pose(), matrices.last().normal(), swing);
				}
				var cutout = net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture);
				queue.submitCustomGeometry(matrices, cutout, (pose2, consumer) -> {
					float[] mv = mesh.vertices();
					// GPU path (26.2): the mesh is uploaded once and only its matrix goes out each frame - see GpuCosmetics.
					if (GpuCosmetics.record(mesh, MeshLod.quads(mesh), mv, 1f / 16f, squeeze, cutout, pose2.pose(), light,
							net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)) {
						return;
					}
					if (!CosmeticVertexWriter.writeQuads(consumer, MeshLod.quads(mesh), mv.length / 8, mv, 8, 0, 3, mv, 8, 6, 1f / 16f, squeeze,
							new Matrix4f(pose2.pose()), new Matrix3f(pose2.normal()), 0xFFFFFFFF, null,
							net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, light)) {
						emit(mesh, squeeze, new Matrix4f(pose2.pose()), new Matrix3f(pose2.normal()), (x, y, z, u, v, nx, ny, nz) -> consumer.addVertex(x, y, z,
								0xFFFFFFFF, u, v, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, light, nx, ny, nz));
					}
				});
				matrices.popPose();
			}
		}
	}
	*///?}

	/** A chain's swing ({@link net.veloclient.velo.client.cosmetics.wear.NeckSwing}): turned about the base of the neck. */
	private static void swing(Matrix4f pose, Matrix3f normal, float[] swing) {
		float pivot = 0.5f / 16f;
		pose.translate(0, pivot, 0).rotateX(-swing[0]).rotateZ(-swing[1]).translate(0, -pivot, 0);
		normal.rotateX(-swing[0]).rotateZ(-swing[1]);
	}

	private static boolean velo$far(Object a, Object b) {
		//? if <26.1 {
		return net.veloclient.velo.client.modules.performance.CosmeticsQualityModule.hideWear(false,
				((net.minecraft.entity.Entity) a).squaredDistanceTo((net.minecraft.entity.Entity) b));
		//?} else {
		/*return net.veloclient.velo.client.modules.performance.CosmeticsQualityModule.hideWear(false,
				((net.minecraft.world.entity.Entity) a).distanceToSqr((net.minecraft.world.entity.Entity) b));
		*///?}
	}

	/** Hats and ears make way for a helmet, shoes for boots. */
	private static boolean hidden(String slot, boolean helmet, boolean boots) {
		return helmet && ("head".equals(slot) || "ears".equals(slot)) || boots && "feet".equals(slot);
	}

	/**
	 * Slim skins' arms are 3 pixels wide instead of 4, missing on the outside: arm items are squeezed towards the inner
	 * edge. Returns that edge's x (+1 left of the right arm's inside, -1 for the left arm), 0 for other bones.
	 */
	private static float armSqueeze(String bone) {
		return "leftArm".equals(bone) ? -1f : "rightArm".equals(bone) ? 1f : 0f;
	}

	@FunctionalInterface
	private interface VertexSink {
		void accept(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	/** The mesh as quads (paired triangles, see {@link MeshLod#quads}); skin pixels to blocks, squeezed to a slim arm when {@code squeeze} != 0. */
	private static void emit(EmoteProps.Mesh mesh, float squeeze, Matrix4f position, Matrix3f normal, VertexSink sink) {
		float[] v = mesh.vertices();
		int[] quads = MeshLod.quads(mesh);
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (int k = 0; k < quads.length; k++) {
			int i = quads[k] * 8;
			float x = v[i];
			if (squeeze != 0) {
				x = squeeze + (x - squeeze) * 0.75f;
			}
			position.transformPosition(x / 16f, v[i + 1] / 16f, v[i + 2] / 16f, p);
			normal.transform(v[i + 3], v[i + 4], v[i + 5], n);
			float length = n.length();
			if (length > 1e-6f) {
				n.div(length);
			}
			sink.accept(p.x, p.y, p.z, v[i + 6], v[i + 7], n.x, n.y, n.z);
		}
	}
}
