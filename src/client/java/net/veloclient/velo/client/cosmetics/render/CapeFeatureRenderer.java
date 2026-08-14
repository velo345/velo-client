package net.veloclient.velo.client.cosmetics.render;

//? if <26.1 {
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerCapeModel;
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
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.model.geom.EntityModelSet;
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
import net.veloclient.velo.client.cosmetics.physics.ClothSimulator;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Renders the locally-equipped cosmetic cape on the local player, using
 * vanilla's own {@link PlayerCapeModel} mesh/UV (so the texture template
 * matches vanilla's 64x32 cape layout) with its sway angle driven by a real
 * {@link ClothSimulator} instead of vanilla's canned animation.
 *
 * <p>Purely client-rendered per design spec section 6.5: the real Minecraft
 * server is never told about this, and players not running Velo Client
 * simply don't see it. The local player's own cape (this class's original,
 * still-unconditional behavior) always renders with full {@link
 * ClothSimulator} physics from {@link CapeManager#renderCape()}. Other
 * players only get a cape rendered here if they're currently online with
 * Velo Client on the same optional community server this client is
 * configured to talk to (see {@code net.veloclient.velo.client.network} -
 * on by default, see server/README.md) - and only if it's one of
 * the built-in Store capes ({@link net.veloclient.velo.client.cosmetics.RemoteCapeCache}),
 * since a custom imported cape's texture never leaves its owner's machine.
 * That remote path deliberately skips {@link ClothSimulator} (a single
 * instance shared by whichever player renders this frame, so it can't hold
 * independent per-remote-player physics state) in favor of a fixed rest
 * lean - full physics parity for other players is a natural follow-up, not
 * a v1 requirement.
 */
//? if <26.1 {
public final class CapeFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class CapeFeatureRenderer extends RenderLayer<AvatarRenderState, PlayerModel> {
*///?}

	private final PlayerCapeModel model;
	private final ClothSimulator simulator;
	private CapePhysicsPreset lastPreset;
	private long lastStepMillis = -1;

	//? if <26.1 {
	public CapeFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context,
			EntityRendererFactory.Context rendererFactoryContext) {
		super(context);
		this.model = new PlayerCapeModel(rendererFactoryContext.getPart(EntityModelLayers.PLAYER_CAPE));
		this.lastPreset = CapePhysicsPreset.defaults();
		this.simulator = new ClothSimulator(lastPreset, 0.25f);
	}
	//?} else {
	/*public CapeFeatureRenderer(RenderLayerParent<AvatarRenderState, PlayerModel> context, EntityModelSet modelSet) {
		super(context);
		this.model = new PlayerCapeModel(modelSet.bakeLayer(ModelLayers.PLAYER_CAPE));
		this.lastPreset = CapePhysicsPreset.defaults();
		this.simulator = new ClothSimulator(lastPreset, 0.25f);
	}
	*///?}

	//? if <26.1 {
	@SuppressWarnings("unchecked")
	public static void register() {
		CapeManager.loadLibrary();
		// Wavey Capes has no API to register with, but its own fallback
		// renderer picks up whatever the local player's real cape texture
		// resolves to (see LocalPlayerSkinCapeMixin) - registering our own
		// render layer on top of that would double-render the cape, so we
		// step aside entirely and let it take over with its own (nicer,
		// dedicated) wave physics instead of ClothSimulator's.
		if (net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded()) {
			return;
		}
		LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != EntityType.PLAYER || !(entityRenderer instanceof FeatureRendererContext)) {
				return;
			}
			helper.register(new CapeFeatureRenderer(
					(FeatureRendererContext<PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel>) entityRenderer,
					context));
		});
	}
	//?} else {
	/*public static void register() {
		CapeManager.loadLibrary();
		if (net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded()) {
			return;
		}
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (entityType != EntityType.PLAYER || !(entityRenderer instanceof AvatarRenderer<?> avatarEntityRenderer)) {
				return;
			}
			registrationHelper.register(new CapeFeatureRenderer(avatarEntityRenderer, context.getModelSet()));
		});
	}
	*///?}

	//? if <26.1 {
	@Override
	public void render(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light,
			PlayerEntityRenderState state, float limbAngle, float limbDistance) {
		MinecraftClient client = MinecraftClient.getInstance();
		PlayerEntity localPlayer = client.player;
		if (localPlayer != null && state.id == localPlayer.getId()) {
			CapeManager.renderCape().ifPresent(definition -> {
				if (localPlayer.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA) {
					return;
				}
				advanceSimulation(definition, localPlayer);
				float restLean = 0.14f;
				float pitch = restLean + simulator.firstSegmentPitchRadians() * 1.35f;
				float roll = simulator.firstSegmentRollRadians() * 0.8f;
				renderCapeModel(matrixStack, queue, light, state, definition, pitch, roll);
			});
			return;
		}
		renderRemote(matrixStack, queue, light, state, client);
	}

	/**
	 * The other-player counterpart of the block above: no {@link
	 * ClothSimulator} (that field is single-instance, shared across whatever
	 * player renders this frame, so it can't hold per-remote-player physics
	 * state - see class doc), just the same constant rest lean every cape
	 * already has, driven by {@link net.veloclient.velo.client.network.VeloUserRegistry}
	 * instead of the local {@link CapeManager}.
	 */
	private void renderRemote(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light,
			PlayerEntityRenderState state, MinecraftClient client) {
		if (client.world == null) {
			return;
		}
		net.minecraft.entity.Entity entity = client.world.getEntityById(state.id);
		if (!(entity instanceof PlayerEntity remotePlayer)) {
			return;
		}
		String capeId = net.veloclient.velo.client.network.VeloUserRegistry.capeIdFor(remotePlayer.getUuid());
		if (capeId == null || remotePlayer.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA) {
			return;
		}
		net.veloclient.velo.client.cosmetics.RemoteCapeCache.resolve(capeId)
				.ifPresent(definition -> renderCapeModel(matrixStack, queue, light, state, definition, 0.14f, 0f));
	}

	private void renderCapeModel(MatrixStack matrixStack, OrderedRenderCommandQueue queue, int light,
			PlayerEntityRenderState state, CapeDefinition definition, float pitch, float roll) {
		Identifier texture = CapeManager.textureIdentifier(definition);
		matrixStack.push();
		// No extra translate here - vanilla's own cape model pivot already
		// places it correctly against the back; an earlier version of this
		// added an unconditional offset copied from vanilla's *equipment
		// layer* edge case, which pushed the cape out of position.
		matrixStack.multiply(new Quaternionf().rotateX(pitch).rotateZ(roll));
		queue.submitModel(this.model, state, matrixStack, RenderLayers.entityCutoutNoCull(texture),
				light, OverlayTexture.DEFAULT_UV, state.outlineColor, null);
		matrixStack.pop();
	}
	//?} else {
	/*@Override
	public void submit(PoseStack matrixStack, SubmitNodeCollector queue, int light,
			AvatarRenderState state, float limbAngle, float limbDistance) {
		Minecraft client = Minecraft.getInstance();
		Player localPlayer = client.player;
		if (localPlayer != null && state.id == localPlayer.getId()) {
			CapeManager.renderCape().ifPresent(definition -> {
				if (localPlayer.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.world.item.Items.ELYTRA) {
					return;
				}
				advanceSimulation(definition, localPlayer);
				float restLean = 0.14f;
				float pitch = restLean + simulator.firstSegmentPitchRadians() * 1.35f;
				float roll = simulator.firstSegmentRollRadians() * 0.8f;
				renderCapeModel(matrixStack, queue, light, state, definition, pitch, roll);
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
		String capeId = net.veloclient.velo.client.network.VeloUserRegistry.capeIdFor(remotePlayer.getUUID());
		if (capeId == null || remotePlayer.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.world.item.Items.ELYTRA) {
			return;
		}
		net.veloclient.velo.client.cosmetics.RemoteCapeCache.resolve(capeId)
				.ifPresent(definition -> renderCapeModel(matrixStack, queue, light, state, definition, 0.14f, 0f));
	}

	private void renderCapeModel(PoseStack matrixStack, SubmitNodeCollector queue, int light,
			AvatarRenderState state, CapeDefinition definition, float pitch, float roll) {
		Identifier texture = CapeManager.textureIdentifier(definition);
		matrixStack.pushPose();
		// No extra translate here - vanilla's own cape model pivot already
		// places it correctly against the back; an earlier version of this
		// added an unconditional offset copied from vanilla's *equipment
		// layer* edge case, which pushed the cape out of position.
		matrixStack.mulPose(new Quaternionf().rotateX(pitch).rotateZ(roll));
		queue.submitModel(this.model, state, matrixStack, RenderTypes.entitySolid(texture),
				light, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
		matrixStack.popPose();
	}
	*///?}

	//? if <26.1 {
	private void advanceSimulation(CapeDefinition definition, PlayerEntity player) {
		if (!definition.physicsPreset().equals(lastPreset)) {
			simulator.setPreset(definition.physicsPreset());
			lastPreset = definition.physicsPreset();
		}
		long now = System.currentTimeMillis();
		float deltaSeconds = lastStepMillis < 0 ? 0.05f : Math.min(0.1f, (now - lastStepMillis) / 1000f);
		lastStepMillis = now;

		Vector3f pin = new Vector3f(0, 0, 0);
		Vector3f velocity = new Vector3f((float) -player.getVelocity().x, (float) -player.getVelocity().y * 0.2f, (float) -player.getVelocity().z);
		simulator.step(pin, velocity, deltaSeconds);
	}
	//?} else {
	/*private void advanceSimulation(CapeDefinition definition, Player player) {
		if (!definition.physicsPreset().equals(lastPreset)) {
			simulator.setPreset(definition.physicsPreset());
			lastPreset = definition.physicsPreset();
		}
		long now = System.currentTimeMillis();
		float deltaSeconds = lastStepMillis < 0 ? 0.05f : Math.min(0.1f, (now - lastStepMillis) / 1000f);
		lastStepMillis = now;

		Vector3f pin = new Vector3f(0, 0, 0);
		Vector3f velocity = new Vector3f((float) -player.getDeltaMovement().x, (float) -player.getDeltaMovement().y * 0.2f, (float) -player.getDeltaMovement().z);
		simulator.step(pin, velocity, deltaSeconds);
	}
	*///?}
}
