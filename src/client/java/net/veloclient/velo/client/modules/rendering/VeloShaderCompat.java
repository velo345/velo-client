package net.veloclient.velo.client.modules.rendering;

/**
 * Velo Shaders: what differs between Minecraft 26.2 and 26.3 - 26.3 draws through render passes the caller opens
 * (no more output overrides), takes explicit target formats, keeps the sky colour as a vector and can say itself
 * whether a post chain is loaded. Everything else in the shader code is the same on both.
 */
//? if >=26.3 {
/*public final class VeloShaderCompat {

	private VeloShaderCompat() {
	}

	// A colour + depth target of size x size; the depth is what the light pass samples.
	public static com.mojang.blaze3d.pipeline.TextureTarget newTarget(String name, int size) {
		return new com.mojang.blaze3d.pipeline.TextureTarget(name, size, size, com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,
				com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);
	}

	// The sky colour as ARGB.
	public static int skyColor(net.minecraft.client.renderer.state.level.SkyRenderState state) {
		var c = state.skyColor;
		if (c == null) {
			return 0xFF78A7FF;
		}
		return 0xFF000000 | channel(c.x()) << 16 | channel(c.y()) << 8 | channel(c.z());
	}

	private static int channel(float v) {
		return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
	}

	// Whether the shader manager has this post chain loaded (asking for a missing one fails a resource reload).
	public static boolean postChainLoaded(net.minecraft.resources.Identifier id) {
		return net.minecraft.client.Minecraft.getInstance().getShaderManager()
				.isPostEffectValid(id, net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGETS);
	}

	// Draws what was submitted to {@code submits} into {@code target} (projection and model-view set by the caller).
	public static void drawFeatures(net.minecraft.client.renderer.feature.FeatureRenderDispatcher features,
			net.minecraft.client.renderer.SubmitNodeStorage submits, com.mojang.blaze3d.pipeline.TextureTarget target) {
		try (var frame = features.prepareFrame(submits)) {
			if (frame.isEmpty()) {
				return;
			}
			var pass = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Velo shadow entities",
					target.getColorTextureView(), java.util.Optional.empty(), target.getDepthTextureView(), java.util.OptionalDouble.empty());
			try {
				net.minecraft.client.renderer.feature.FeatureRenderDispatcher.renderAllFeatures(pass, frame);
			} finally {
				pass.close();
			}
		}
	}
}
*///?} else if >=26.2 {
/*public final class VeloShaderCompat {

	private VeloShaderCompat() {
	}

	public static com.mojang.blaze3d.pipeline.TextureTarget newTarget(String name, int size) {
		return new com.mojang.blaze3d.pipeline.TextureTarget(name, size, size, true, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);
	}

	public static int skyColor(net.minecraft.client.renderer.state.level.SkyRenderState state) {
		return state.skyColor;
	}

	private static java.lang.reflect.Field cacheField;
	private static java.lang.reflect.Field configsField;
	private static boolean reflectionFailed;

	// The shader manager's compiled configs (from the last finished reload) - its CompilationCache is a private class,
	// so no accessor can name it: plain reflection (26.x isn't obfuscated).
	public static boolean postChainLoaded(net.minecraft.resources.Identifier id) {
		if (reflectionFailed) {
			return true; // can't tell: behave as before
		}
		try {
			Object shaders = net.minecraft.client.Minecraft.getInstance().getShaderManager();
			if (cacheField == null) {
				cacheField = net.minecraft.client.renderer.ShaderManager.class.getDeclaredField("compilationCache");
				cacheField.setAccessible(true);
			}
			Object cache = cacheField.get(shaders);
			if (cache == null) {
				return false;
			}
			if (configsField == null) {
				configsField = cache.getClass().getDeclaredField("configs");
				configsField.setAccessible(true);
			}
			var configs = (net.minecraft.client.renderer.ShaderManager.Configs) configsField.get(cache);
			return configs != null && configs.postChains().containsKey(id);
		} catch (ReflectiveOperationException | RuntimeException e) {
			reflectionFailed = true;
			net.veloclient.velo.VeloClient.LOGGER.warn("Velo Shaders: can't read the shader manager's state", e);
			return true;
		}
	}

	public static void drawFeatures(net.minecraft.client.renderer.feature.FeatureRenderDispatcher features,
			net.minecraft.client.renderer.SubmitNodeStorage submits, com.mojang.blaze3d.pipeline.TextureTarget target) {
		var oldColor = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride;
		var oldDepth = com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride;
		try {
			com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = target.getColorTextureView();
			com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
			features.renderAllFeatures(submits);
		} finally {
			com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = oldColor;
			com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = oldDepth;
		}
	}
}
*///?} else {
public final class VeloShaderCompat {

	private VeloShaderCompat() {
	}
}
//?}
