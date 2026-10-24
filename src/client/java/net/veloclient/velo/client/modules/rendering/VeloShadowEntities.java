package net.veloclient.velo.client.modules.rendering;

/**
 * Velo Shaders: entities in the sun shadow maps - players (cape, wings and the other cosmetics come
 * along, they're render layers), mobs, items. After the terrain shadow pass the frame's entity
 * render states are submitted again into a storage of our own and drawn from the sun into the
 * shadow map. Your own player is extracted extra in first person (vanilla leaves it out there),
 * so you see your own shadow. Name tags, text and vanilla's blob shadows don't cast anything.
 */
public final class VeloShadowEntities {

	/** Set when the pass failed once: the light pass then stops reading the near map. */
	public static boolean disabled;

	private VeloShadowEntities() {
	}

	//? if >=26.2 {
	/*// The collector for the shadow pass: everything but text and blob shadows.
	private static final class ShadowSubmits extends net.minecraft.client.renderer.SubmitNodeStorage {
		@Override
		public void submitShadow(com.mojang.blaze3d.vertex.PoseStack pose, float radius,
				java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> pieces) {
		}

		@Override
		public void submitNameTag(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.world.phys.Vec3 pos, int offset,
				net.minecraft.network.chat.Component text, boolean seeThrough, int light, net.minecraft.client.renderer.state.level.CameraRenderState camera) {
		}

	}

	private static final ShadowSubmits NEAR_SUBMITS = new ShadowSubmits();
	private static final ShadowSubmits FAR_SUBMITS = new ShadowSubmits();
	// This frame's entities (vanilla clears its list once submitted - VeloShadowEntityStatesMixin keeps a copy).
	public static final java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState> FRAME_STATES = new java.util.ArrayList<>();
	private static net.minecraft.client.renderer.ProjectionMatrixBuffer nearProjection;
	private static net.minecraft.client.renderer.ProjectionMatrixBuffer farProjection;

	// Draws this frame's entities from the sun (render thread, after the world, before the light pass): the
	// ones close by into the near map (every frame, cleared first), the rest into the main map when it was
	// redrawn this frame.
	public static void render(net.minecraft.client.renderer.feature.FeatureRenderDispatcher features,
			net.minecraft.client.renderer.state.level.LevelRenderState levelState, float partialTick) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var camera = levelState.cameraRenderState;
		var near = VeloShadowMap.nearTarget();
		com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(near.getColorTexture(),
				new org.joml.Vector4f(0f, 0f, 0f, 0f), near.getDepthTexture(), 0.0); // reversed Z: 0 = far
		if (camera == null || camera.pos == null) {
			return;
		}
		var dispatcher = mc.getEntityRenderDispatcher();
		var pose = new com.mojang.blaze3d.vertex.PoseStack();
		double cx = camera.pos.x;
		double cy = camera.pos.y;
		double cz = camera.pos.z;
		float nearReach = (VeloShadowMap.NEAR_RADIUS - 2.5f) * (VeloShadowMap.NEAR_RADIUS - 2.5f);
		float farReach = VeloShadowMap.RADIUS * VeloShadowMap.RADIUS;
		boolean far = VeloShadowMap.renderThisFrame;
		int nearCount = 0;
		int farCount = 0;
		for (var state : FRAME_STATES) {
			double dx = state.x - cx;
			double dy = state.y - cy;
			double dz = state.z - cz;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 <= nearReach) {
				dispatcher.submit(state, camera, dx, dy, dz, pose, NEAR_SUBMITS);
				nearCount++;
			} else if (far && dx * dx + dz * dz <= farReach) {
				dispatcher.submit(state, camera, dx, dy, dz, pose, FAR_SUBMITS);
				farCount++;
			}
		}
		if (mc.player != null && !mc.player.isInvisible() && mc.options.getCameraType().isFirstPerson()) {
			// Vanilla leaves you out in first person; your shadow (and your cosmetics') still falls on the ground.
			var self = dispatcher.extractEntity(mc.player, partialTick);
			dispatcher.submit(self, camera, self.x - cx, self.y - cy, self.z - cz, pose, NEAR_SUBMITS);
			nearCount++;
		}
		if (Boolean.getBoolean("velo.shaderDebugLog") && (++debugFrames % 300) == 0) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo shadow entities: {} states, {} near, {} far", FRAME_STATES.size(), nearCount, farCount);
		}
		if (nearCount > 0) {
			if (nearProjection == null) {
				nearProjection = new net.minecraft.client.renderer.ProjectionMatrixBuffer("Velo near shadow entities");
			}
			draw(features, NEAR_SUBMITS, near, nearProjection, VeloShadowMap.NEAR_PROJECTION, VeloShadowMap.NEAR_VIEW);
		}
		if (farCount > 0) {
			if (farProjection == null) {
				farProjection = new net.minecraft.client.renderer.ProjectionMatrixBuffer("Velo shadow entities");
			}
			draw(features, FAR_SUBMITS, VeloShadowMap.target(), farProjection, VeloShadowMap.PROJECTION, VeloShadowMap.VIEW);
		}
	}

	private static int debugFrames;

	private static void draw(net.minecraft.client.renderer.feature.FeatureRenderDispatcher features, ShadowSubmits submits,
			com.mojang.blaze3d.pipeline.TextureTarget target, net.minecraft.client.renderer.ProjectionMatrixBuffer buffer,
			org.joml.Matrix4f projection, org.joml.Matrix4f view) {
		var oldProjection = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrixBuffer();
		var oldType = com.mojang.blaze3d.systems.RenderSystem.getProjectionType();
		var modelView = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		try {
			com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(buffer.getBuffer(projection),
					com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
			modelView.set(view);
			VeloShaderCompat.drawFeatures(features, submits, target);
		} finally {
			modelView.popMatrix();
			if (oldProjection != null) {
				com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(oldProjection, oldType);
			}
		}
	}
	*///?}
}
