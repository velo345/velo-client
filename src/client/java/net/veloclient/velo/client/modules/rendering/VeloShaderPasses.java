package net.veloclient.velo.client.modules.rendering;

/**
 * Velo Shaders' two screen passes, called from VeloShadersPostMixin: the light chain (shadows, AO, coloured light,
 * clouds, rays - needs the world's depth, so it runs right after the world, before the hand) and the grade chain
 * (bloom, exposure/contrast/saturation, tint, vignette - after the hand, before the entity outline and the GUI).
 */
//? if >=26.2 {
/*public final class VeloShaderPasses {

	private static final net.minecraft.resources.Identifier GRADE_CHAIN = net.minecraft.resources.Identifier.withDefaultNamespace("velo_shaders");
	private static final net.minecraft.resources.Identifier LIGHT_CHAIN = net.minecraft.resources.Identifier.withDefaultNamespace("velo_shaders_light");
	private static boolean failed;
	private static boolean missingLogged;
	private static boolean entityShadowsFailed;

	private VeloShaderPasses() {
	}

	public static void light(net.minecraft.client.renderer.feature.FeatureRenderDispatcher features,
			net.minecraft.client.renderer.state.GameRenderState gameState, com.mojang.blaze3d.pipeline.RenderTarget main,
			com.mojang.blaze3d.resource.CrossFrameResourcePool pool) {
		if (failed || !VeloShadersModule.lightActive()) {
			return;
		}
		// The light chain samples the shadow maps by name: they have to exist before the chain loads.
		VeloShadowMap.target();
		VeloShadowMap.nearTarget();
		if (!entityShadowsFailed && VeloShadersModule.entityShadowsActive() && VeloShadowMap.valid) {
			try {
				VeloShadowEntities.render(features, gameState.levelRenderState,
						net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
			} catch (Throwable t) {
				entityShadowsFailed = true;
				VeloShadowEntities.disabled = true;
				net.veloclient.velo.VeloClient.LOGGER.error("Velo Shaders: entity shadows failed - off for this session", t);
			}
		}
		process(LIGHT_CHAIN, main, pool);
	}

	public static void grade(com.mojang.blaze3d.pipeline.RenderTarget main, com.mojang.blaze3d.resource.CrossFrameResourcePool pool) {
		if (failed || !VeloShadersModule.postActive()) {
			return;
		}
		process(GRADE_CHAIN, main, pool);
	}

	private static void process(net.minecraft.resources.Identifier id, com.mojang.blaze3d.pipeline.RenderTarget main,
			com.mojang.blaze3d.resource.CrossFrameResourcePool pool) {
		// Never ask for a chain the shader manager hasn't loaded: vanilla treats the failed lookup as a broken
		// resource pack - turning the shaders on in-game failed the reload and dropped every selected pack.
		if (!VeloShaderCompat.postChainLoaded(id)) {
			return;
		}
		try {
			var chain = net.minecraft.client.Minecraft.getInstance().getShaderManager().getPostChain(id,
					net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGETS);
			if (chain != null) {
				chain.process(main, pool);
			} else if (!missingLogged) {
				missingLogged = true;
				net.veloclient.velo.VeloClient.LOGGER.warn("Velo Shaders: post chain {} not loaded - is the pack selected?", id);
			}
		} catch (Throwable t) {
			failed = true;
			net.veloclient.velo.VeloClient.LOGGER.error("Velo Shaders: post pass failed - turned off for this session", t);
		}
	}
}
*///?} else {
public final class VeloShaderPasses {

	private VeloShaderPasses() {
	}
}
//?}
